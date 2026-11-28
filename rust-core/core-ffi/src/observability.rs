//! Structured, line-per-event logging for the FFI layer.
//!
//! Events are JSON objects with fixed fields (`ts`, `level`, `target`,
//! `event`, `message`) so a phone can be diagnosed without shipping frames or
//! credentials. The formatter never writes raw messages: every string goes
//! through [`redact`] first.

use std::fmt::Write as _;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write as _};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::SystemTime;

use tracing::field::{Field, Visit};
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::fmt::format::Writer;
use tracing_subscriber::fmt::writer::MakeWriter;
use tracing_subscriber::fmt::{FmtContext, FormatEvent, FormatFields};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::registry::LookupSpan;

/// Bytes one log file may reach before it rotates. `docs/monitoring.md` §2.3
/// budgets 3 x 5 MB of local diagnosis space.
pub const LOG_FILE_MAX_BYTES: u64 = 5 * 1024 * 1024;

/// Number of retained local log files: the live file plus its rotated
/// predecessors.
pub const LOG_FILE_COUNT: usize = 3;

/// Logs one table transition without frame data or credentials.
pub fn log_table_event(table_id: &str, status: &str, persons: u32) {
    tracing::info!(
        event = "table_status",
        table_id = table_id,
        status = status,
        persons = persons,
        "table status changed"
    );
}

/// Logs why the stream became unavailable. `status` must already be a failure
/// status; url/password never reach this function.
pub fn log_stream_failure(status: &str) {
    tracing::warn!(
        event = "stream_failure",
        status = status,
        "stream marked unavailable"
    );
}

/// Logs an inference failure without the model path or its parameters.
pub fn log_inference_failure() {
    tracing::error!(event = "inference_failed", "inference failed");
}

/// Logs a listener panic. Kotlin listeners run in the JVM, so a panic there
/// only reaches the native log; `stderr` is not captured on an Android device.
pub fn log_listener_panic() {
    tracing::warn!(event = "listener_panic", "listener panicked; event dropped");
}

/// Logs a new engine session. `camera_id` is validated by the caller and can
/// never contain a URL or credential.
pub fn log_engine_started(generation: u64, camera_id: &str) {
    tracing::info!(
        event = "engine_started",
        generation = generation,
        camera_id = camera_id,
        "engine session started"
    );
}

static INITIALIZED: OnceLock<()> = OnceLock::new();

/// Installs a JSON line logger writing to a size-rotated file.
///
/// Safe to call repeatedly: only the first call installs the subscriber.
/// Returns `false` when another subscriber was already installed.
pub fn init(directory: &Path, file_prefix: &str) -> bool {
    if INITIALIZED.set(()).is_err() {
        return false;
    }
    let Some(writer) = try_writer(directory, file_prefix) else {
        return false;
    };
    let subscriber = tracing_subscriber::registry().with(
        tracing_subscriber::fmt::layer()
            .with_ansi(false)
            .with_writer(writer)
            .event_format(JsonFormat),
    );
    match tracing::subscriber::set_global_default(subscriber) {
        Ok(()) => true,
        Err(_) => false,
    }
}

/// Creates the directory and opens the live log file.
///
/// A phone where `filesDir` is unavailable must keep running; losing the native
/// log is preferable to panicking inside `Engine::new`.
fn try_writer(directory: &Path, file_prefix: &str) -> Option<RollingLogWriter> {
    RollingLogWriter::with_limit(directory, file_prefix, LOG_FILE_MAX_BYTES)
}

/// Appends log lines and rotates by size, never by clock.
///
/// `tracing-appender` 0.2.5 only rotates on time, so a minute of dense events
/// could pass the 5 MB budget in `docs/monitoring.md` §2.3. Here the budget is
/// enforced on bytes written, which also means no background watcher runs on a
/// phone that is already CPU-bound. Files are `<prefix>.log` (live) plus
/// `<prefix>.1.log` and `<prefix>.2.log`, at most [`LOG_FILE_COUNT`] in total.
pub struct RollingLogWriter {
    directory: PathBuf,
    prefix: String,
    max_bytes: u64,
    state: Mutex<OpenFile>,
}

/// The live file plus how many bytes this process has put in it.
struct OpenFile {
    file: File,
    written: u64,
}

impl RollingLogWriter {
    /// Opens `<prefix>.log` for appending, or `None` when the directory cannot
    /// be created or the file cannot be opened.
    ///
    /// Bytes already in the file count towards the budget, so restarting the
    /// engine does not hand the writer a fresh 5 MB.
    fn with_limit(directory: &Path, prefix: &str, max_bytes: u64) -> Option<Self> {
        fs::create_dir_all(directory).ok()?;
        let file = open_log(&live_path(directory, prefix)).ok()?;
        let written = file.metadata().map_or(0, |meta| meta.len());
        Some(Self {
            directory: directory.to_path_buf(),
            prefix: prefix.to_owned(),
            max_bytes: max_bytes.max(1),
            state: Mutex::new(OpenFile { file, written }),
        })
    }

    fn write_bytes(&self, buf: &[u8]) -> io::Result<usize> {
        let mut state = self.lock();
        let projected = state.written.saturating_add(buf.len() as u64);
        if state.written > 0 && projected > self.max_bytes {
            self.rotate(&mut state);
        }
        let written = state.file.write(buf)?;
        state.written = state.written.saturating_add(written as u64);
        Ok(written)
    }

    /// Shifts the rotated files down by one and opens a fresh live file.
    ///
    /// Every step is best effort: a failed rename or reopen must not drop the
    /// event, so the open handle keeps writing. In that case the process keeps
    /// appending to the file it already holds and only a restart retries.
    fn rotate(&self, state: &mut OpenFile) {
        let _ = fs::remove_file(self.path_for(LOG_FILE_COUNT - 1));
        for index in (1..LOG_FILE_COUNT - 1).rev() {
            let _ = fs::rename(self.path_for(index), self.path_for(index + 1));
        }
        let live = live_path(&self.directory, &self.prefix);
        let _ = fs::rename(&live, self.path_for(1));
        if let Ok(file) = open_log(&live) {
            state.file = file;
        }
        state.written = 0;
    }

    fn path_for(&self, index: usize) -> PathBuf {
        self.directory.join(format!("{}.{index}.log", self.prefix))
    }

    fn lock(&self) -> MutexGuard<'_, OpenFile> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// One logging call's writer.
///
/// The handle borrows the writer, so the file and its byte budget stay behind
/// one mutex and threads cannot interleave half a line.
pub struct RollingLogHandle<'a> {
    writer: &'a RollingLogWriter,
}

impl io::Write for RollingLogHandle<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.writer.write_bytes(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.writer.lock().file.flush()
    }
}

impl<'a> MakeWriter<'a> for RollingLogWriter {
    type Writer = RollingLogHandle<'a>;

    fn make_writer(&'a self) -> Self::Writer {
        RollingLogHandle { writer: self }
    }
}

fn live_path(directory: &Path, prefix: &str) -> PathBuf {
    directory.join(format!("{prefix}.log"))
}

fn open_log(path: &Path) -> io::Result<File> {
    OpenOptions::new().create(true).append(true).open(path)
}

struct JsonFormat;

impl<S, N> FormatEvent<S, N> for JsonFormat
where
    S: Subscriber + for<'a> LookupSpan<'a>,
    N: for<'a> FormatFields<'a> + 'static,
{
    fn format_event(
        &self,
        _ctx: &FmtContext<'_, S, N>,
        mut writer: Writer<'_>,
        event: &Event<'_>,
    ) -> std::fmt::Result {
        let mut visitor = JsonVisitor::default();
        event.record(&mut visitor);
        let timestamp = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map(|duration| duration.as_secs())
            .unwrap_or_default();
        write!(
            writer,
            "{{\"ts\":{timestamp},\"level\":\"{}\",\"target\":\"{}\",\"message\":\"{}\"",
            level_name(event.metadata().level()),
            json_escape(event.metadata().target()),
            json_escape(&visitor.message),
        )?;
        for (key, value) in &visitor.fields {
            write!(
                writer,
                ",\"{}\":\"{}\"",
                json_escape(key),
                json_escape(&redact(value)),
            )?;
        }
        writeln!(writer, "}}")
    }
}

fn level_name(level: &Level) -> &'static str {
    match *level {
        Level::TRACE => "TRACE",
        Level::DEBUG => "DEBUG",
        Level::INFO => "INFO",
        Level::WARN => "WARN",
        Level::ERROR => "ERROR",
    }
}

#[derive(Default)]
struct JsonVisitor {
    message: String,
    fields: Vec<(String, String)>,
}

impl Visit for JsonVisitor {
    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        let rendered = format!("{value:?}");
        if field.name() == "message" {
            self.message = redact(&rendered);
        } else {
            self.fields.push((field.name().to_owned(), rendered));
        }
    }

    fn record_str(&mut self, field: &Field, value: &str) {
        if field.name() == "message" {
            self.message = redact(value);
        } else {
            self.fields
                .push((field.name().to_owned(), value.to_owned()));
        }
    }

    fn record_i64(&mut self, field: &Field, value: i64) {
        self.fields
            .push((field.name().to_owned(), value.to_string()));
    }

    fn record_u64(&mut self, field: &Field, value: u64) {
        self.fields
            .push((field.name().to_owned(), value.to_string()));
    }

    fn record_bool(&mut self, field: &Field, value: bool) {
        self.fields
            .push((field.name().to_owned(), value.to_string()));
    }
}

/// Removes RTSP URLs, `key=value` secrets, and `user:pass@` prefixes.
///
/// This mirrors `android/core/model/Redaction.kt`: a native log line and a
/// Kotlin UI message must agree on what is safe to show.
pub fn redact(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let bytes = input.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if is_rtsp_scheme(bytes, index) {
            out.push_str("[diredaksi]");
            index = skip_token(bytes, index);
        } else if is_secret_key(bytes, index) {
            out.push_str("[diredaksi]");
            index = skip_after_separator(bytes, index);
        } else if credential_prefix_end(bytes, index).is_some() {
            out.push_str("[diredaksi]");
            index = credential_prefix_end(bytes, index).expect("checked above");
        } else {
            let ch = input[index..]
                .chars()
                .next()
                .expect("byte boundary is valid");
            out.push(ch);
            index += ch.len_utf8();
        }
    }
    collapse_whitespace(&out)
}

fn is_rtsp_scheme(bytes: &[u8], at: usize) -> bool {
    bytes[at..].len() >= 7 && bytes[at..at + 7].eq_ignore_ascii_case(b"rtsp://")
}

fn is_secret_key(bytes: &[u8], at: usize) -> bool {
    const KEYS: [&[u8]; 5] = [b"password", b"passwd", b"pwd", b"secret", b"token"];
    let tail = &bytes[at..];
    for key in KEYS {
        if tail.len() < key.len() || !tail[..key.len()].eq_ignore_ascii_case(key) {
            continue;
        }
        let mut cursor = key.len();
        while cursor < tail.len() && tail[cursor].is_ascii_whitespace() {
            cursor += 1;
        }
        if cursor < tail.len() && (tail[cursor] == b'=' || tail[cursor] == b':') {
            return true;
        }
    }
    false
}

fn credential_prefix_end(bytes: &[u8], at: usize) -> Option<usize> {
    if at == 0 || bytes[at].is_ascii_whitespace() {
        return None;
    }
    let mut end = at;
    while end < bytes.len() && !bytes[end].is_ascii_whitespace() {
        if bytes[end] == b'@' {
            let prefix = &bytes[at..end];
            if prefix.contains(&b':') {
                return Some(end + 1);
            }
            return None;
        }
        end += 1;
    }
    None
}

fn skip_token(bytes: &[u8], at: usize) -> usize {
    let mut end = at;
    while end < bytes.len()
        && !bytes[end].is_ascii_whitespace()
        && bytes[end] != b'"'
        && bytes[end] != b'\''
    {
        end += 1;
    }
    end
}

fn skip_after_separator(bytes: &[u8], at: usize) -> usize {
    let mut end = at;
    while end < bytes.len() && bytes[end] != b'=' && bytes[end] != b':' {
        end += 1;
    }
    if end < bytes.len() {
        end += 1;
    }
    while end < bytes.len() && bytes[end].is_ascii_whitespace() {
        end += 1;
    }
    skip_token(bytes, end)
}

fn collapse_whitespace(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut pending_space = false;
    for ch in input.chars() {
        if ch.is_whitespace() {
            pending_space = true;
        } else {
            if pending_space && !out.is_empty() {
                out.push(' ');
            }
            pending_space = false;
            out.push(ch);
        }
    }
    out
}

/// Escapes a string for JSON without pulling in `serde_json` on the hot path.
pub fn json_escape(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 2);
    for ch in input.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn log_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("cctv-log-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn write_lines(writer: &RollingLogWriter, lines: &[String]) {
        let mut handle = RollingLogHandle { writer };
        for line in lines {
            handle
                .write_all(line.as_bytes())
                .expect("the temporary log directory is writable");
        }
        handle.flush().expect("flush the live log file");
    }

    fn files_with_prefix(dir: &Path, prefix: &str) -> Vec<PathBuf> {
        fs::read_dir(dir)
            .expect("log dir exists")
            .filter_map(|entry| entry.ok())
            .map(|entry| entry.path())
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with(prefix))
            })
            .collect()
    }

    #[test]
    fn an_unwritable_log_directory_is_reported_without_panicking() {
        let dir = std::env::temp_dir().join(format!("cctv-log-unwritable-{}", std::process::id()));
        let blocker = dir.join("not-a-directory");
        let _ = fs::create_dir_all(&dir);
        fs::write(&blocker, b"occupied").expect("create blocker");
        assert!(try_writer(&blocker, "cctv-test").is_none());
        let _ = fs::remove_file(&blocker);
        let _ = fs::remove_dir(&dir);
    }

    #[test]
    fn the_line_that_would_pass_the_budget_starts_a_new_file() {
        let dir = log_dir("budget");
        let writer =
            RollingLogWriter::with_limit(&dir, "cctv-test", 64).expect("a writable log dir");
        let first = format!("{}\n", "a".repeat(39));
        let second = format!("{}\n", "b".repeat(39));
        write_lines(&writer, &[first, second]);

        let live = fs::read_to_string(dir.join("cctv-test.log")).expect("live log file");
        let rotated = fs::read_to_string(dir.join("cctv-test.1.log")).expect("rotated log file");
        assert!(live.starts_with('b'), "the newest line owns the live file");
        assert!(
            rotated.starts_with('a'),
            "the previous line moved to the rotated file"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn rotation_keeps_three_files_and_drops_the_oldest() {
        let dir = log_dir("three");
        let writer =
            RollingLogWriter::with_limit(&dir, "cctv-test", 40).expect("a writable log dir");
        for index in 0..20 {
            write_lines(&writer, &[format!("line-{index:02}\n")]);
        }

        let files = files_with_prefix(&dir, "cctv-test");
        assert_eq!(
            files.len(),
            LOG_FILE_COUNT,
            "a size-rotated log keeps exactly {} files: {files:?}",
            LOG_FILE_COUNT
        );
        let contents: String = files
            .iter()
            .map(|path| fs::read_to_string(path).unwrap_or_default())
            .collect();
        assert!(
            !contents.contains("line-00"),
            "the oldest file must be dropped, got: {contents}"
        );
        assert!(
            contents.contains("line-19"),
            "the newest line must survive, got: {contents}"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_restart_keeps_counting_the_bytes_already_written() {
        let dir = log_dir("restart");
        let first =
            RollingLogWriter::with_limit(&dir, "cctv-test", 64).expect("a writable log dir");
        write_lines(&first, &[format!("{}\n", "a".repeat(39))]);
        drop(first);

        let second =
            RollingLogWriter::with_limit(&dir, "cctv-test", 64).expect("a writable log dir");
        write_lines(&second, &[format!("{}\n", "b".repeat(39))]);

        let rotated = fs::read_to_string(dir.join("cctv-test.1.log")).expect("rotated log file");
        assert!(
            rotated.starts_with('a'),
            "bytes from before the restart still count towards the budget"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_rtsp_url_never_reaches_the_log() {
        let line = redact("connect rtsp://admin:rahasia@10.0.0.5:554/live failed");
        assert!(!line.contains("rahasia"));
        assert!(!line.contains("admin"));
        assert!(!line.contains("rtsp://"));
        assert!(line.contains("[diredaksi]"));
    }

    #[test]
    fn secret_values_and_credential_prefixes_are_removed() {
        let line = redact("token=abc123 password: rahasia admin:s3cret@cam");
        assert!(!line.contains("abc123"));
        assert!(!line.contains("rahasia"));
        assert!(!line.contains("s3cret"));
        assert!(line.contains("[diredaksi]"));
    }

    #[test]
    fn ordinary_diagnostic_text_is_kept_and_whitespace_is_collapsed() {
        assert_eq!(
            redact("  tables  2  moved = false "),
            "tables 2 moved = false"
        );
    }

    #[test]
    fn json_escape_keeps_a_redacted_line_valid_json() {
        let escaped = json_escape("a\"b\\c\nd");
        assert_eq!(escaped, "a\\\"b\\\\c\\nd");
        let parsed: serde_json::Value =
            serde_json::from_str(&format!("\"{escaped}\"")).expect("valid JSON string");
        assert_eq!(parsed.as_str(), Some("a\"b\\c\nd"));
    }
}
