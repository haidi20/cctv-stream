//! Error classification for stream sources.
//!
//! Everything that leaves this crate is either an enum or a message without
//! endpoints. URLs and credentials never cross the boundary.

use std::fmt;
use std::io;

use serde::Serialize;

/// Coarse error category used by the UI and the heartbeat payload.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum StreamErrorKind {
    /// Host refused the connection, DNS failed, or no route exists.
    Unreachable,
    /// The connection attempt exceeded the configured timeout.
    Timeout,
    /// The camera rejected the credentials.
    AuthFailed,
    /// RTSP handshake succeeded but the protocol exchange broke.
    Protocol,
    /// Local filesystem or socket error that does not fit the categories above.
    Io,
    /// Container or codec data could not be decoded.
    Decode,
    /// The peer closed an established stream.
    Closed,
}

impl StreamErrorKind {
    /// Stable string form, used in JSON and logs.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Unreachable => "unreachable",
            Self::Timeout => "timeout",
            Self::AuthFailed => "auth_failed",
            Self::Protocol => "protocol",
            Self::Io => "io",
            Self::Decode => "decode",
            Self::Closed => "closed",
        }
    }
}

/// Error returned by stream sources.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamError {
    /// Host unreachable, DNS failure, or connection refused.
    Unreachable,
    /// Connection attempt timed out.
    Timeout,
    /// Credentials rejected by the camera.
    AuthFailed,
    /// Protocol-level failure with a short, non-sensitive detail string.
    Protocol {
        /// Short description without endpoints or credentials.
        detail: String,
    },
    /// Local I/O error.
    Io {
        /// Underlying error kind.
        kind: io::ErrorKind,
    },
    /// Container or codec failure.
    Decode {
        /// Short description without payload bytes.
        detail: String,
    },
    /// Peer closed an established stream.
    Closed,
}

impl fmt::Display for StreamError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Unreachable => f.write_str("stream unreachable"),
            Self::Timeout => f.write_str("connection timed out"),
            Self::AuthFailed => f.write_str("authentication failed"),
            Self::Protocol { detail } => write!(f, "protocol error: {}", redact_detail(detail)),
            Self::Io { kind } => write!(f, "i/o error ({kind:?})"),
            Self::Decode { detail } => write!(f, "decode error: {}", redact_detail(detail)),
            Self::Closed => f.write_str("source closed"),
        }
    }
}

impl std::error::Error for StreamError {}

impl StreamError {
    /// Category for UI mapping and metrics.
    pub fn kind(&self) -> StreamErrorKind {
        match self {
            Self::Unreachable => StreamErrorKind::Unreachable,
            Self::Timeout => StreamErrorKind::Timeout,
            Self::AuthFailed => StreamErrorKind::AuthFailed,
            Self::Protocol { .. } => StreamErrorKind::Protocol,
            Self::Io { .. } => StreamErrorKind::Io,
            Self::Decode { .. } => StreamErrorKind::Decode,
            Self::Closed => StreamErrorKind::Closed,
        }
    }

    /// Map an [`io::Error`] without copying its message.
    pub fn from_io(error: &io::Error) -> Self {
        match classify_io(error.kind()) {
            StreamErrorKind::Unreachable => Self::Unreachable,
            StreamErrorKind::Timeout => Self::Timeout,
            StreamErrorKind::AuthFailed => Self::AuthFailed,
            StreamErrorKind::Closed => Self::Closed,
            _ => Self::Io { kind: error.kind() },
        }
    }

    /// Protocol error with a short detail.
    pub fn protocol(detail: impl Into<String>) -> Self {
        let detail = redact_detail(&detail.into());
        Self::Protocol { detail }
    }

    /// Decode error with a short detail.
    pub fn decode(detail: impl Into<String>) -> Self {
        let detail = redact_detail(&detail.into());
        Self::Decode { detail }
    }

    /// Generic message that is safe to show to a user or send to the server.
    pub fn user_message(&self) -> &'static str {
        match self.kind() {
            StreamErrorKind::Unreachable => "kamera tidak terjangkau",
            StreamErrorKind::Timeout => "koneksi ke kamera timeout",
            StreamErrorKind::AuthFailed => "kredensial kamera ditolak",
            StreamErrorKind::Protocol => "protokol RTSP tidak cocok",
            StreamErrorKind::Io => "kamera tidak dapat dibaca",
            StreamErrorKind::Decode => "stream tidak dapat didekode",
            StreamErrorKind::Closed => "stream terputus",
        }
    }
}

impl From<io::Error> for StreamError {
    fn from(error: io::Error) -> Self {
        Self::from_io(&error)
    }
}

/// Classify an I/O error kind into a stream category.
pub fn classify_io(kind: io::ErrorKind) -> StreamErrorKind {
    use io::ErrorKind;

    match kind {
        ErrorKind::ConnectionRefused
        | ErrorKind::HostUnreachable
        | ErrorKind::NetworkUnreachable
        | ErrorKind::AddrNotAvailable
        | ErrorKind::NotFound
        | ErrorKind::AddrInUse => StreamErrorKind::Unreachable,
        ErrorKind::TimedOut | ErrorKind::WouldBlock => StreamErrorKind::Timeout,
        ErrorKind::PermissionDenied => StreamErrorKind::AuthFailed,
        ErrorKind::ConnectionReset
        | ErrorKind::ConnectionAborted
        | ErrorKind::BrokenPipe
        | ErrorKind::UnexpectedEof => StreamErrorKind::Closed,
        _ => StreamErrorKind::Io,
    }
}

/// Remove credentials and query parameters from an RTSP URL.
///
/// `rtsp://admin:secret@10.0.0.2:554/stream?token=abc` becomes
/// `rtsp://10.0.0.2:554/stream`.
pub fn redact_endpoint(input: &str) -> String {
    let (scheme, rest) = match input.find("://") {
        Some(index) => (&input[..index + 3], &input[index + 3..]),
        None => ("", input),
    };

    let (authority, path) = match rest.find('/') {
        Some(index) => (&rest[..index], &rest[index..]),
        None => (rest, ""),
    };

    let host = match authority.rfind('@') {
        Some(index) => &authority[index + 1..],
        None => authority,
    };

    let path = match path.find('?') {
        Some(index) => &path[..index],
        None => path,
    };

    format!("{scheme}{host}{path}")
}

/// Remove every endpoint from a free-form detail string.
///
/// Unlike [`redact_endpoint`], this keeps no host at all: detail strings end up
/// in `Display`, logs, and the heartbeat, where the camera address is needless
/// metadata. URLs become `scheme://[redacted]`, bare IPv4 addresses become
/// `[ip]`, and `user:pass@` prefixes are dropped.
pub fn redact_detail(detail: &str) -> String {
    let mut output = String::with_capacity(detail.len());
    let mut rest = detail;

    while let Some(offset) = rest.find("://") {
        let scheme_start = rest[..offset]
            .rfind(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.')))
            .map_or(0, |index| index + 1);

        if scheme_start == offset {
            output.push_str(&rest[..offset + 3]);
            rest = &rest[offset + 3..];
            continue;
        }

        output.push_str(&rest[..scheme_start]);
        output.push_str(&rest[scheme_start..offset]);
        output.push_str("://[redacted]");

        let after = &rest[offset + 3..];
        let end = after
            .find(|c: char| c.is_whitespace() || matches!(c, '"' | '\'' | ')' | ',' | ';' | '>'))
            .unwrap_or(after.len());
        rest = &after[end..];
    }

    output.push_str(rest);

    let without_userinfo = output
        .split_whitespace()
        .map(|token| match token.rfind('@') {
            Some(index) => &token[index + 1..],
            None => token,
        })
        .collect::<Vec<_>>()
        .join(" ");

    mask_ipv4(&without_userinfo)
}

fn mask_ipv4(input: &str) -> String {
    let mut output = String::with_capacity(input.len());
    let mut index = 0;

    while index < input.len() {
        if input.as_bytes()[index].is_ascii_digit() {
            if let Some(len) = ipv4_len_at(input, index) {
                output.push_str("[ip]");
                index += len;
                continue;
            }
        }

        let ch = input[index..]
            .chars()
            .next()
            .expect("index is a char boundary");
        output.push(ch);
        index += ch.len_utf8();
    }

    output
}

fn ipv4_len_at(input: &str, start: usize) -> Option<usize> {
    let mut offset = start;

    for octet in 0..4 {
        let end = input[offset..]
            .find(|c: char| !c.is_ascii_digit())
            .map_or(input.len(), |index| offset + index);

        let digits = end - offset;
        if digits == 0 || digits > 3 {
            return None;
        }
        if input[offset..end].parse::<u16>().ok()? > 255 {
            return None;
        }

        offset = end;
        if octet < 3 {
            if !input[offset..].starts_with('.') {
                return None;
            }
            offset += 1;
        }
    }

    Some(offset - start)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redacts_userinfo_and_query() {
        let url = "rtsp://admin:secret123@10.0.0.2:554/stream1?token=abc";
        let redacted = redact_endpoint(url);
        assert_eq!(redacted, "rtsp://10.0.0.2:554/stream1");
        assert!(!redacted.contains("secret123"));
        assert!(!redacted.contains("admin"));
        assert!(!redacted.contains("token"));
    }

    #[test]
    fn redacts_url_without_scheme() {
        assert_eq!(redact_endpoint("admin:pass@host/path"), "host/path");
    }

    #[test]
    fn redacts_urls_embedded_in_prose() {
        let detail = "SETUP failed for rtsp://admin:secret@10.0.0.2:554/x?token=abc (retrying)";
        let redacted = redact_detail(detail);
        assert!(!redacted.contains("secret"));
        assert!(!redacted.contains("admin"));
        assert!(!redacted.contains("token"));
        assert!(!redacted.contains("10.0.0.2"));
        assert!(redacted.contains("rtsp://[redacted]"));
        assert!(redacted.contains("retrying"));
    }

    #[test]
    fn masks_bare_ipv4_addresses() {
        assert_eq!(
            redact_detail("connect to 192.168.1.64 refused"),
            "connect to [ip] refused"
        );
        assert_eq!(redact_detail("not an ip: 1.2.3"), "not an ip: 1.2.3");
        assert_eq!(redact_detail("version 1.96.0"), "version 1.96.0");
    }
}
