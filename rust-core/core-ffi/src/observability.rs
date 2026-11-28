//! Structured, line-per-event logging for the FFI layer.
//!
//! Events are JSON objects with fixed fields (`ts`, `level`, `target`,
//! `event`, `message`) so a phone can be diagnosed without shipping frames or
//! credentials. The formatter never writes raw messages: every string goes
//! through [`redact`] first.

use std::fmt::Write as _;
use std::fs;
use std::path::Path;
use std::sync::OnceLock;
use std::time::SystemTime;

use tracing::field::{Field, Visit};
use tracing::{Event, Level, Subscriber};
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::fmt::format::Writer;
use tracing_subscriber::fmt::{FmtContext, FormatEvent, FormatFields};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::registry::LookupSpan;

/// Number of retained local log files. Rotation is time-based so the app never
/// needs a background size watcher on a device that is already CPU-bound.
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

/// Installs a JSON line logger writing to a rolling file.
///
/// Safe to call repeatedly: only the first call installs the subscriber.
/// Returns `false` when another subscriber was already installed.
pub fn init(directory: &Path, file_prefix: &str) -> bool {
    if INITIALIZED.set(()).is_err() {
        return false;
    }
    let Some(appender) = try_appender(directory, file_prefix) else {
        return false;
    };
    let subscriber = tracing_subscriber::registry().with(
        tracing_subscriber::fmt::layer()
            .with_ansi(false)
            .with_writer(appender)
            .event_format(JsonFormat),
    );
    match tracing::subscriber::set_global_default(subscriber) {
        Ok(()) => true,
        Err(_) => false,
    }
}

/// Creates the directory and opens a rolling appender.
///
/// A phone where `filesDir` is unavailable must keep running; losing the native
/// log is preferable to panicking inside `Engine::new`.
fn try_appender(directory: &Path, file_prefix: &str) -> Option<RollingFileAppender> {
    fs::create_dir_all(directory).ok()?;
    RollingFileAppender::builder()
        .rotation(Rotation::MINUTELY)
        .max_log_files(LOG_FILE_COUNT)
        .filename_prefix(file_prefix)
        .build(directory)
        .ok()
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

    #[test]
    fn an_unwritable_log_directory_is_reported_without_panicking() {
        let dir = std::env::temp_dir().join(format!("cctv-log-unwritable-{}", std::process::id()));
        let blocker = dir.join("not-a-directory");
        let _ = fs::create_dir_all(&dir);
        fs::write(&blocker, b"occupied").expect("create blocker");
        assert!(try_appender(&blocker, "cctv-test").is_none());
        let _ = fs::remove_file(&blocker);
        let _ = fs::remove_dir(&dir);
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
