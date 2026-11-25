//! Public API surface that Kotlin depends on through FFI.

use std::time::Duration;

use stream_engine::{
    FileSource, FileSourceConfig, Frame, FrameSampler, ReconnectPolicy, RingBuffer, Source,
    StreamError, StreamErrorKind,
};

#[test]
fn source_enum_keeps_file_and_rtsp_variants() {
    let camera = Source::Rtsp {
        url: "rtsp://10.0.0.2:554/Streaming/Channels/101".to_string(),
    };
    let file = Source::File(FileSourceConfig {
        path: "testdata/videos/mock.mp4".to_string(),
        fps: 25,
        loop_playback: true,
    });

    assert!(!camera.is_replayable());
    assert!(file.is_replayable());
}

#[test]
fn source_describe_never_leaks_credentials() {
    let camera = Source::Rtsp {
        url: "rtsp://admin:hunter2@192.168.1.64:554/h264/ch1/main/av_stream?token=xyz".to_string(),
    };
    let described = camera.describe();

    assert!(!described.contains("hunter2"));
    assert!(!described.contains("token"));
    assert!(described.contains("192.168.1.64:554"));
}

#[test]
fn frame_is_cheap_to_clone() {
    let frame = Frame::new(1, Duration::from_millis(40), 768, 432);
    let clone = frame.clone();
    assert_eq!(clone.seq, frame.seq);
    assert_eq!(clone.payload_len(), 0);
}

#[test]
fn error_kind_maps_to_stable_strings() {
    assert_eq!(StreamError::Unreachable.kind().as_str(), "unreachable");
    assert_eq!(StreamError::Timeout.kind().as_str(), "timeout");
    assert_eq!(StreamError::AuthFailed.kind().as_str(), "auth_failed");
    assert_eq!(
        StreamError::protocol("bad SETUP").kind().as_str(),
        "protocol"
    );
}

#[test]
fn sampler_rejects_rates_outside_the_supported_band() {
    assert!(FrameSampler::new(3.0).unwrap().is_recommended());
    assert!(!FrameSampler::new(25.0).unwrap().is_recommended());
    assert!(FrameSampler::new(0.1).is_err());
}

#[test]
fn ring_buffer_and_reconnect_are_usable_without_a_source() {
    let mut buffer = RingBuffer::new(4);
    assert!(buffer.try_push(1u8).is_ok());
    assert_eq!(buffer.try_pop(), Some(1));

    let mut policy = ReconnectPolicy::default_policy();
    assert_eq!(policy.next_delay(), Duration::from_secs(1));
    policy.reset();
    assert_eq!(policy.attempts(), 0);
}

#[test]
fn file_source_needs_a_non_zero_fps() {
    #[derive(Debug)]
    struct NoopDecoder;

    impl stream_engine::FrameDecoder for NoopDecoder {
        fn next_frame(&mut self) -> Result<Option<stream_engine::DecodedFrame>, StreamError> {
            Ok(None)
        }

        fn rewind(&mut self) -> Result<(), StreamError> {
            Ok(())
        }
    }

    let error = FileSource::new(
        FileSourceConfig {
            path: "a.mp4".to_string(),
            fps: 0,
            loop_playback: false,
        },
        NoopDecoder,
    )
    .unwrap_err();

    assert_eq!(
        StreamError::from(error).kind(),
        StreamErrorKind::Protocol,
        "config errors must not be reported as connectivity failures"
    );
}
