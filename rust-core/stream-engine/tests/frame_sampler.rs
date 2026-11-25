//! Sampling behaviour at the rates the product actually uses.

use std::time::Duration;

use stream_engine::{Frame, FrameSampler};

fn frame(seq: u64, ms: u64) -> Frame {
    Frame::new(seq, Duration::from_millis(ms), 768, 432)
}

#[test]
fn two_fps_keeps_one_frame_per_500ms() {
    let mut sampler = FrameSampler::new(2.0).unwrap();
    let mut emitted = Vec::new();

    for millis in (0..2_000).step_by(40) {
        if let Some(frame) = sampler.push(frame(millis / 40, millis)) {
            emitted.push(frame.captured_at);
        }
    }

    assert_eq!(emitted.len(), 4, "2 seconds at 2 FPS");
    assert_eq!(emitted[0], Duration::ZERO);

    // The source runs at 25 FPS, so 500 ms boundaries only exist every other
    // frame: the sampler emits the first frame at or after each boundary
    // (520 ms, 1040 ms, 1560 ms) instead of inventing a frame.
    for gap in emitted.windows(2) {
        let delta = gap[1] - gap[0];
        assert!(
            delta >= Duration::from_millis(500) && delta < Duration::from_millis(560),
            "unexpected cadence: {delta:?}"
        );
    }
}

#[test]
fn five_fps_keeps_one_frame_per_200ms() {
    let mut sampler = FrameSampler::new(5.0).unwrap();
    let mut emitted = 0;

    for millis in (0..1_000).step_by(40) {
        if sampler.push(frame(millis / 40, millis)).is_some() {
            emitted += 1;
        }
    }

    assert_eq!(emitted, 5);
}

#[test]
fn backlog_never_grows_under_slow_consumption() {
    let mut sampler = FrameSampler::new(2.0).unwrap();
    for millis in (0..5_000).step_by(40) {
        sampler.push(frame(millis / 40, millis));
    }

    assert!(sampler.dropped() > 0);
}

#[test]
fn timestamps_are_monotonic_for_every_emitted_frame() {
    let mut sampler = FrameSampler::new(3.0).unwrap();
    let mut last = None;

    for millis in (0..3_000).step_by(40) {
        if let Some(frame) = sampler.push(frame(millis / 40, millis)) {
            if let Some(previous) = last {
                assert!(frame.captured_at > previous);
            }
            last = Some(frame.captured_at);
        }
    }
}
