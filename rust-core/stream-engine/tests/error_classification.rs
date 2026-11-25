//! Every connection failure must map to a category the UI can explain.

use std::io;

use stream_engine::{classify_io, StreamError, StreamErrorKind};

#[test]
fn connection_refused_is_unreachable() {
    assert_eq!(
        classify_io(io::ErrorKind::ConnectionRefused),
        StreamErrorKind::Unreachable
    );
    assert_eq!(
        StreamError::from_io(&io::Error::from(io::ErrorKind::ConnectionRefused)).kind(),
        StreamErrorKind::Unreachable
    );
}

#[test]
fn timeout_is_its_own_kind() {
    assert_eq!(
        classify_io(io::ErrorKind::TimedOut),
        StreamErrorKind::Timeout
    );
    assert_eq!(
        classify_io(io::ErrorKind::WouldBlock),
        StreamErrorKind::Timeout
    );
}

#[test]
fn permission_denied_is_auth_failed() {
    assert_eq!(
        classify_io(io::ErrorKind::PermissionDenied),
        StreamErrorKind::AuthFailed
    );
}

#[test]
fn reset_and_eof_are_closed_not_unreachable() {
    for kind in [
        io::ErrorKind::ConnectionReset,
        io::ErrorKind::ConnectionAborted,
        io::ErrorKind::BrokenPipe,
        io::ErrorKind::UnexpectedEof,
    ] {
        assert_eq!(classify_io(kind), StreamErrorKind::Closed);
    }
}

#[test]
fn user_messages_contain_no_endpoints() {
    let errors = [
        StreamError::Unreachable,
        StreamError::Timeout,
        StreamError::AuthFailed,
        StreamError::protocol("SETUP failed at rtsp://10.0.0.2:554/x"),
        StreamError::decode("bad SPS"),
        StreamError::Closed,
        StreamError::Io {
            kind: io::ErrorKind::Other,
        },
    ];

    for error in errors {
        let message = error.user_message();
        assert!(!message.contains("rtsp://"));
        assert!(!message.contains("10.0.0.2"));
        assert!(!message.contains("554"));
    }
}

#[test]
fn display_never_prints_the_endpoint() {
    let error = StreamError::protocol("rtsp://admin:secret@10.0.0.2:554/x");
    let rendered = format!("{error}");
    assert!(!rendered.contains("admin:secret"));
    assert!(!rendered.contains("10.0.0.2"));
}
