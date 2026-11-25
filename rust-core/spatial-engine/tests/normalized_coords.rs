//! Resolution and aspect-ratio guards from ADR 0008.

use spatial_engine::{
    centroid_normalized, needs_recalibration, to_normalized, AspectRatio, FrameSize, PixelBox,
};

#[test]
fn canonical_person_pixel_maps_to_0_30() {
    let frame = FrameSize::new(1280, 720).unwrap();
    let point = to_normalized((384.0, 216.0), frame);
    assert!((point.x - 0.30).abs() < 1e-9);
    assert!((point.y - 0.30).abs() < 1e-9);
}

#[test]
fn fixture_person_outside_maps_to_expected_fraction() {
    let frame = FrameSize::new(1280, 720).unwrap();
    let point = centroid_normalized(PixelBox::new(1000.0, 500.0, 0.0, 0.0), frame);
    assert!((point.x - 0.78125).abs() < 1e-9);
    assert!((point.y - 0.694_444_444).abs() < 1e-9);
}

#[test]
fn the_same_normalised_point_survives_540p_and_720p() {
    let ratio = AspectRatio::parse("16:9").unwrap();
    for frame in [
        FrameSize::new(960, 540).unwrap(),
        FrameSize::new(1280, 720).unwrap(),
        FrameSize::new(768, 432).unwrap(),
        FrameSize::new(1920, 1080).unwrap(),
    ] {
        assert!(!needs_recalibration(ratio, frame), "{frame:?}");
        let point = to_normalized(
            (f64::from(frame.width) / 2.0, f64::from(frame.height) / 2.0),
            frame,
        );
        assert!((point.x - 0.5).abs() < 1e-9);
        assert!((point.y - 0.5).abs() < 1e-9);
    }
}

#[test]
fn four_by_three_requires_recalibration() {
    let ratio = AspectRatio::parse("16:9").unwrap();
    assert!(needs_recalibration(
        ratio,
        FrameSize::new(1024, 768).unwrap()
    ));
    assert!(needs_recalibration(
        ratio,
        FrameSize::new(640, 480).unwrap()
    ));
}

#[test]
fn fractional_aspect_ratios_are_supported() {
    let ratio = AspectRatio::parse("4:3").unwrap();
    assert!(ratio.matches_frame(FrameSize::new(1024, 768).unwrap()));
    assert!(!ratio.matches_frame(FrameSize::new(1280, 720).unwrap()));
}

#[test]
fn anamorphic_854x480_does_not_match_16_9() {
    let ratio = AspectRatio::parse("16:9").unwrap();
    let anamorphic = FrameSize::new(854, 480).unwrap();

    assert_eq!(anamorphic.aspect_ratio(), 854.0 / 480.0);
    assert!(
        needs_recalibration(ratio, anamorphic),
        "854x480 is not exactly 16:9; refusing to guess is the documented behaviour"
    );
}
