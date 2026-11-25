//! The live path, exercised against a loopback RTSP camera.
//!
//! No real camera, no public ports: every case here fails on a network-less CI
//! machine exactly as it would on a laptop.

#![cfg(feature = "rtsp")]

mod support;

use std::net::TcpListener;
use std::time::{Duration, Instant};

use stream_engine::{RtspConfig, RtspSource, RtspTransport, StreamErrorKind};

use support::MockRtsp;

/// How long a test may wait for media before it is considered hung.
const READ_TIMEOUT: Duration = Duration::from_secs(5);

fn config(url: String) -> RtspConfig {
    let mut config = RtspConfig::new(url);
    config.connect_timeout = Duration::from_secs(5);
    config
}

#[test]
fn credentials_are_used_for_digest_auth_but_never_logged() {
    let camera = MockRtsp::protected();
    let source = RtspSource::connect(config(camera.url_with_credentials("admin", "hunter2")))
        .expect("mock camera accepts the credentials from the URL");

    let described = source.describe();
    assert!(!described.contains("hunter2"));
    assert!(!described.contains("admin"));
    assert!(described.contains("127.0.0.1"));

    let requests = camera.requests();
    assert_eq!(
        requests.first().map(|r| r.method.as_str()),
        Some("DESCRIBE")
    );
    assert!(
        !requests[0].authorized,
        "the first attempt must be anonymous so the camera can challenge"
    );
    assert!(
        requests.iter().any(|r| r.authorized),
        "the retry must carry credentials, otherwise auth never succeeds: {requests:?}"
    );
}

#[test]
fn an_access_unit_is_annex_b_and_flags_keyframes() {
    let camera = MockRtsp::streaming();
    let mut source = RtspSource::connect(config(camera.url())).expect("open camera");

    let first = source
        .next_unit(READ_TIMEOUT)
        .expect("first access unit arrives")
        .expect("stream is not over");

    assert_eq!(first.npt, Duration::ZERO);
    assert!(first.keyframe, "IDR slice must be flagged as a keyframe");
    assert_eq!(first.loss, 0);
    assert!(
        first.data.starts_with(&[0, 0, 0, 1]),
        "SIMPLE framing must produce Annex B start codes, got {:02x?}",
        &first.data[..first.data.len().min(8)]
    );
    assert!(
        annex_b_nal_types(&first.data).contains(&5),
        "the access unit must carry an IDR (type 5) NAL, got {:02x?}",
        first.data
    );

    let second = source
        .next_unit(READ_TIMEOUT)
        .expect("second access unit arrives")
        .expect("stream is not over");

    assert!(!second.keyframe, "a non-IDR slice is not a keyframe");
    assert!(
        second.npt > first.npt,
        "NPT must advance: {:?} then {:?}",
        first.npt,
        second.npt
    );
}

#[test]
fn the_camera_going_quiet_surfaces_as_a_timeout() {
    let camera = MockRtsp::silent();
    let mut source = RtspSource::connect(config(camera.url())).expect("session starts");

    let started = Instant::now();
    let error = source
        .next_unit(Duration::from_millis(300))
        .expect_err("no media arrives");
    let elapsed = started.elapsed();

    assert_eq!(error.kind(), StreamErrorKind::Timeout);
    assert!(
        elapsed >= Duration::from_millis(250),
        "returned early: {elapsed:?}"
    );
    assert!(
        elapsed < Duration::from_secs(3),
        "ignored the read timeout: {elapsed:?}"
    );
}

#[test]
fn a_stream_that_drops_mid_session_reports_closed() {
    let camera = MockRtsp::closing();
    let mut source = RtspSource::connect(config(camera.url())).expect("session starts");

    let first = source.next_unit(READ_TIMEOUT).expect("first unit");
    assert!(first.is_some());

    // The demuxer buffers the second unit before it notices the hang-up, so the
    // drop only becomes visible on the read after that.
    let second = source.next_unit(READ_TIMEOUT).expect("second unit");
    assert!(second.is_some());

    match source.next_unit(READ_TIMEOUT) {
        Ok(None) => {}
        Err(error) => assert_eq!(
            error.kind(),
            StreamErrorKind::Closed,
            "a dropped TCP connection is a closed stream, not {error:?}"
        ),
        Ok(Some(unit)) => panic!("unexpected access unit after the camera left: {unit:?}"),
    }
}

#[test]
fn rejected_credentials_fail_fast_without_a_retry_storm() {
    let camera = MockRtsp::rejecting_everything();
    let error = RtspSource::connect(config(camera.url_with_credentials("admin", "wrong")))
        .expect_err("401 must not produce a session");

    assert_eq!(error.kind(), StreamErrorKind::AuthFailed);

    let rendered = error.to_string();
    assert!(!rendered.contains("wrong"));
    assert!(!rendered.contains("127.0.0.1"));

    let requests = camera.requests();
    assert!(
        requests.len() <= 3,
        "401 must not be retried in a tight loop, saw {requests:?}"
    );
    assert!(
        requests.iter().all(|r| r.method == "DESCRIBE"),
        "the client must stop after the handshake fails, saw {requests:?}"
    );
}

#[test]
fn a_closed_port_is_unreachable_not_a_timeout() {
    // Bind, then drop, so the port is free and refusing connections.
    let port = TcpListener::bind("127.0.0.1:0")
        .expect("bind probe port")
        .local_addr()
        .expect("local addr")
        .port();

    let started = Instant::now();
    let error = RtspSource::connect(config(format!("rtsp://127.0.0.1:{port}/stream")))
        .expect_err("nothing is listening");
    let elapsed = started.elapsed();

    assert_eq!(error.kind(), StreamErrorKind::Unreachable);
    assert!(
        elapsed < Duration::from_secs(5),
        "refused connections must not wait for the deadline: {elapsed:?}"
    );
}

#[test]
fn udp_opt_in_reaches_setup_instead_of_silently_using_tcp() {
    let camera = MockRtsp::streaming();
    let mut rtsp = config(camera.url());
    rtsp.transport = RtspTransport::Udp;

    // The mock only speaks interleaved TCP, so SETUP cannot complete. What
    // matters is that the choice is honoured rather than ignored.
    let error = RtspSource::connect(rtsp).expect_err("mock camera offers TCP only");
    assert!(
        matches!(
            error.kind(),
            StreamErrorKind::Protocol | StreamErrorKind::Unreachable | StreamErrorKind::Timeout
        ),
        "unexpected kind: {error:?}"
    );
}

/// Walk Annex B start codes and report each NAL unit type (low 5 bits of the
/// header byte). Retina prefixes parameter sets, so an IDR is not always the
/// first NAL in the access unit.
fn annex_b_nal_types(data: &[u8]) -> Vec<u8> {
    let mut types = Vec::new();
    let mut index = 0;
    while index + 3 < data.len() {
        let start_code = if data[index..].starts_with(&[0, 0, 0, 1]) {
            Some(4)
        } else if data[index..].starts_with(&[0, 0, 1]) {
            Some(3)
        } else {
            None
        };

        match start_code {
            Some(length) => {
                if let Some(header) = data.get(index + length) {
                    types.push(header & 0x1f);
                }
                index += length;
            }
            None => index += 1,
        }
    }
    types
}
