//! A camera that pans away from the view its table map was drawn for must be
//! detected, and ordinary life in the room must not trip the detector.

use spatial_engine::{
    changed_fraction, ViewChangeConfig, ViewError, ViewGuard, ViewSignature, DEFAULT_VIEW_COLS,
    DEFAULT_VIEW_ROWS,
};

const WIDTH: u32 = 320;
const HEIGHT: u32 = 180;

/// Synthetic RGB888 frame: a grey background with brighter or darker blocks,
/// addressed in normalised `0..=1` coordinates like a table map.
struct Frame {
    width: u32,
    height: u32,
    rgb: Vec<u8>,
}

impl Frame {
    fn new(width: u32, height: u32, grey: u8) -> Self {
        Self {
            width,
            height,
            rgb: vec![grey; width as usize * height as usize * 3],
        }
    }

    fn block(&mut self, x0: f64, y0: f64, x1: f64, y1: f64, value: u8) -> &mut Self {
        let xs = (x0 * f64::from(self.width)) as usize;
        let xe = (x1 * f64::from(self.width)) as usize;
        let ys = (y0 * f64::from(self.height)) as usize;
        let ye = (y1 * f64::from(self.height)) as usize;
        for y in ys..ye.min(self.height as usize) {
            for x in xs..xe.min(self.width as usize) {
                let offset = (y * self.width as usize + x) * 3;
                self.rgb[offset..offset + 3].copy_from_slice(&[value, value, value]);
            }
        }
        self
    }

    fn signature(&self) -> ViewSignature {
        ViewSignature::from_rgb888(&self.rgb, self.width, self.height)
            .expect("synthetic frame matches its own dimensions")
    }
}

/// Feed [signature] until `moved` turns true, returning that frame's verdict.
///
/// Q-09 makes the guard need a run of agreeing frames before it flips, so a test
/// that wants "the camera is now moved" must keep observing. Panics if the run
/// never completes within [limit] frames.
fn observe_until_moved(
    guard: &mut ViewGuard,
    signature: &ViewSignature,
    limit: u32,
) -> spatial_engine::ViewVerdict {
    for frame in 0..limit {
        let verdict = guard.observe(signature);
        if verdict.moved {
            assert!(
                verdict.became_moved,
                "the flip frame must report became_moved (frame {frame})"
            );
            return verdict;
        }
    }
    panic!("view was never flagged moved within {limit} frames");
}

/// Feed [signature] until `moved` clears, returning that frame's verdict.
fn observe_until_recovered(
    guard: &mut ViewGuard,
    signature: &ViewSignature,
    limit: u32,
) -> spatial_engine::ViewVerdict {
    for frame in 0..limit {
        let verdict = guard.observe(signature);
        if !verdict.moved {
            assert!(
                verdict.recovered,
                "the release frame must report recovered (frame {frame})"
            );
            return verdict;
        }
    }
    panic!("view never recovered within {limit} frames");
}

/// A room seen from a fixed camera: bright wall on the left, dark floor right.
fn room_looking_left() -> Frame {
    let mut frame = Frame::new(WIDTH, HEIGHT, 60);
    frame.block(0.0, 0.0, 0.3, 1.0, 200);
    frame
}

/// The same camera after a pan: the bright wall is now on the right.
fn room_looking_right() -> Frame {
    let mut frame = Frame::new(WIDTH, HEIGHT, 60);
    frame.block(0.7, 0.0, 1.0, 1.0, 200);
    frame
}

#[test]
fn identical_frames_are_not_a_view_change() {
    let signature = room_looking_left().signature();
    assert_eq!(changed_fraction(&signature, &signature, 0.06), 0.0);
}

#[test]
fn the_default_grid_matches_the_documented_shape() {
    let signature = room_looking_left().signature();
    assert_eq!(signature.cols(), DEFAULT_VIEW_COLS);
    assert_eq!(signature.rows(), DEFAULT_VIEW_ROWS);
    assert_eq!(signature.len(), 576);
}

#[test]
fn a_pan_to_another_wall_flags_the_view_as_moved() {
    let mut guard = ViewGuard::default();
    let baseline = guard.observe(&room_looking_left().signature());
    assert!(
        !baseline.moved,
        "the first frame only captures the baseline"
    );

    let panned = room_looking_right().signature();
    assert!(
        !guard.observe(&panned).moved,
        "one frame is not yet enough: a single bright frame must not freeze the map"
    );
    let verdict = observe_until_moved(&mut guard, &panned, 4);
    assert!(verdict.changed_fraction > 0.5);
    assert!(guard.is_moved());
}

#[test]
fn a_person_covering_a_quarter_of_the_frame_keeps_the_map_valid() {
    let mut guard = ViewGuard::default();
    guard.observe(&Frame::new(WIDTH, HEIGHT, 60).signature());

    // 30% of the width, 60% of the height: bigger than a person standing at a
    // table, which must stay below the enter threshold.
    let mut person = Frame::new(WIDTH, HEIGHT, 60);
    person.block(0.35, 0.2, 0.65, 0.8, 200);

    let verdict = guard.observe(&person.signature());
    assert!(!verdict.moved);
    assert!(verdict.changed_fraction > 0.1 && verdict.changed_fraction < 0.5);
}

#[test]
fn turning_the_lights_on_is_not_a_view_change() {
    let baseline = room_looking_left().signature();
    let brighter = {
        let mut frame = Frame::new(WIDTH, HEIGHT, 90);
        frame.block(0.0, 0.0, 0.3, 1.0, 230);
        frame.signature()
    };

    assert_eq!(changed_fraction(&baseline, &brighter, 0.06), 0.0);
}

#[test]
fn the_view_stays_flagged_until_it_mostly_matches_again() {
    let mut guard = ViewGuard::default();
    guard.observe(&room_looking_left().signature());

    observe_until_moved(&mut guard, &room_looking_right().signature(), 4);

    // A third of the frame differs from the baseline: less than the enter
    // threshold, more than the exit threshold, so the verdict must hold.
    let mut partial = Frame::new(WIDTH, HEIGHT, 60);
    partial.block(0.0, 0.0, 0.3, 1.0, 200);
    partial.block(0.7, 0.0, 1.0, 1.0, 200);
    let held = guard.observe(&partial.signature());
    assert!(held.moved);
    assert!(!held.recovered);

    let original = room_looking_left().signature();
    let first_back = guard.observe(&original);
    assert!(
        !first_back.recovered,
        "one matching frame must not un-freeze the map either"
    );
    observe_until_recovered(&mut guard, &original, 3);
    assert!(!guard.is_moved());
}

#[test]
fn reinstalling_a_map_rebases_the_view() {
    let mut guard = ViewGuard::default();
    guard.observe(&room_looking_left().signature());
    observe_until_moved(&mut guard, &room_looking_right().signature(), 4);

    guard.reset();
    assert!(!guard.is_moved());
    assert!(guard.baseline().is_none());

    let after_reset = guard.observe(&room_looking_right().signature());
    assert!(!after_reset.moved, "the new map is drawn on the new view");
}

#[test]
fn a_grid_of_a_different_shape_counts_as_a_view_change() {
    let frame = room_looking_left();
    let fine = ViewSignature::from_rgb888_with_grid(&frame.rgb, WIDTH, HEIGHT, 8, 8)
        .expect("8x8 grid is valid");
    let coarse = frame.signature();

    assert_eq!(changed_fraction(&fine, &coarse, 0.06), 1.0);
}

#[test]
fn a_frame_whose_buffer_does_not_match_its_size_is_rejected() {
    let frame = room_looking_left();
    let short = &frame.rgb[..frame.rgb.len() - 1];

    assert_eq!(
        ViewSignature::from_rgb888(short, WIDTH, HEIGHT),
        Err(ViewError::BufferLength {
            width: WIDTH,
            height: HEIGHT,
            len: short.len(),
            expected: frame.rgb.len(),
        })
    );
}

#[test]
fn an_empty_frame_or_grid_is_rejected() {
    let frame = room_looking_left();
    assert_eq!(
        ViewSignature::from_rgb888(&frame.rgb, 0, HEIGHT),
        Err(ViewError::EmptyFrame)
    );
    assert_eq!(
        ViewSignature::from_rgb888_with_grid(&frame.rgb, WIDTH, HEIGHT, 0, 8),
        Err(ViewError::EmptyGrid)
    );
}

#[test]
fn a_grid_finer_than_the_frame_still_produces_cells() {
    // Regression guard: an empty block would divide by zero in the sampler.
    let frame = Frame::new(8, 8, 60);
    let signature = ViewSignature::from_rgb888_with_grid(&frame.rgb, 8, 8, 32, 18)
        .expect("a grid finer than the frame is clamped, not rejected");

    assert_eq!(signature.len(), 32 * 18);
    assert!(signature
        .cells()
        .iter()
        .all(|cell| (*cell - 60.0 / 255.0).abs() < 0.01));
}

#[test]
fn a_pan_is_detected_on_a_four_three_frame_too() {
    // Detection is resolution and aspect agnostic; only the map is 16:9.
    let mut baseline = Frame::new(640, 480, 60);
    baseline.block(0.0, 0.0, 0.3, 1.0, 200);
    let mut panned = Frame::new(640, 480, 60);
    panned.block(0.7, 0.0, 1.0, 1.0, 200);

    assert!(changed_fraction(&baseline.signature(), &panned.signature(), 0.06) > 0.5);
}

#[test]
fn instant_pan_beyond_half_the_grid_flags_moved() {
    let mut guard = ViewGuard::default();
    guard.observe(&room_looking_left().signature());

    let panned = room_looking_right().signature();
    assert!(changed_fraction(guard.baseline().expect("baseline captured"), &panned, 0.06) > 0.5);

    // Three consecutive frames above the enter fraction: the pan is real. A
    // 30%-wide wall leaving one edge and arriving at the other changes ~60% of
    // the columns, comfortably past the 50% enter fraction.
    let verdict = observe_until_moved(&mut guard, &panned, 3);
    assert!(verdict.changed_fraction > 0.5);
}

#[test]
fn short_spike_below_three_frames_does_not_flag_moved() {
    let mut guard = ViewGuard::default();
    guard.observe(&room_looking_left().signature());

    let panned = room_looking_right().signature();
    assert!(!guard.observe(&panned).moved, "spike frame 1");
    assert!(!guard.observe(&panned).moved, "spike frame 2");

    // The camera is back to the calibrated view before the streak completes.
    let original = room_looking_left().signature();
    assert!(!guard.observe(&original).moved);
    assert!(
        !guard.is_moved(),
        "two bright frames must not freeze the map"
    );
}

#[test]
fn gradual_brightness_ramp_does_not_flag_moved() {
    // The sun creeps across the wall: the bright patch slides 0.0 -> 0.3 of the
    // frame width over 400 analysed frames (about 3.5 minutes at 2 FPS). No
    // single frame moves more than a fraction of a cell, but against the
    // original calibration frame the patch has travelled its own width - the
    // slow "camera moved" false alarm Q-09 is about.
    let mut guard = ViewGuard::default();
    let start = room_looking_left();
    guard.observe(&start.signature());

    let steps = 400u16;
    for step in 1..=steps {
        let left = 0.3 * f64::from(step) / f64::from(steps);
        let mut next = Frame::new(WIDTH, HEIGHT, 60);
        next.block(left, 0.0, left + 0.3, 1.0, 200);
        let verdict = guard.observe(&next.signature());
        assert!(
            !verdict.moved,
            "a slow drift must not be reported as a pan (step {step}, fraction {})",
            verdict.changed_fraction
        );
    }

    // Prove the drift was big enough to trip a *frozen* baseline, so this test
    // fails if the adaptive baseline is removed.
    let end = {
        let mut frame = Frame::new(WIDTH, HEIGHT, 60);
        frame.block(0.3, 0.0, 0.6, 1.0, 200);
        frame
    };
    let frozen = changed_fraction(
        &start.signature(),
        &end.signature(),
        ViewChangeConfig::default().cell_threshold,
    );
    assert!(
        frozen > ViewChangeConfig::default().enter_fraction,
        "the drift must exceed the enter fraction against a frozen baseline, got {frozen}"
    );
    assert!(!guard.is_moved());
}

#[test]
fn baseline_adapts_only_while_not_moved() {
    let mut guard = ViewGuard::default();
    let baseline_frame = room_looking_left();
    guard.observe(&baseline_frame.signature());

    let before = guard
        .baseline()
        .expect("baseline captured")
        .cells()
        .to_vec();

    // A trusted, slightly different frame nudges the baseline.
    let mut brighter = Frame::new(WIDTH, HEIGHT, 70);
    brighter.block(0.0, 0.0, 0.3, 1.0, 205);
    guard.observe(&brighter.signature());
    let adapted = guard
        .baseline()
        .expect("baseline captured")
        .cells()
        .to_vec();
    assert_ne!(before, adapted, "a trusted frame must move the baseline");

    // Now flag the view as moved and prove the baseline stops following.
    let panned = room_looking_right().signature();
    observe_until_moved(&mut guard, &panned, 4);
    assert!(guard.is_moved());
    let frozen = guard
        .baseline()
        .expect("baseline captured")
        .cells()
        .to_vec();
    guard.observe(&panned);
    guard.observe(&panned);
    assert_eq!(
        frozen,
        guard
            .baseline()
            .expect("baseline captured")
            .cells()
            .to_vec(),
        "the baseline must be frozen while the view is flagged"
    );
}

#[test]
fn adaptation_can_be_disabled() {
    let config = ViewChangeConfig {
        baseline_adapt: 0.0,
        ..ViewChangeConfig::default()
    };
    let mut guard = ViewGuard::new(config);
    guard.observe(&room_looking_left().signature());
    let before = guard
        .baseline()
        .expect("baseline captured")
        .cells()
        .to_vec();

    let mut brighter = Frame::new(WIDTH, HEIGHT, 70);
    brighter.block(0.0, 0.0, 0.3, 1.0, 205);
    guard.observe(&brighter.signature());

    assert_eq!(
        before,
        guard
            .baseline()
            .expect("baseline captured")
            .cells()
            .to_vec(),
        "baseline_adapt = 0 must pin the baseline to the calibration frame"
    );
}
