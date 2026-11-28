//! Does the Rust detector agree with Python YOLO on the same frames?
//!
//! Host-only and opt-in, so `cargo test --workspace` stays hermetic. Both
//! variables must be set:
//!
//! - `CCTV_PARITY_MODEL` - the same ONNX detector Python ran. Not committed.
//! - `CCTV_PARITY_DIR` - a directory holding
//!   `frames/frame-<n>-<w>x<h>.rgb` (raw RGB888, the same layout the FFI takes)
//!   and `python.json` written by `tools/model-export/python_reference.py`:
//!
//!   ```json
//!   [{ "frame": "frame-0001-640x480.rgb",
//!      "boxes": [{ "x": 0.1, "y": 0.2, "w": 0.1, "h": 0.3, "confidence": 0.87 }] }]
//!   ```
//!
//! Optional:
//!
//! - `CCTV_PARITY_THRESHOLD` - confidence floor handed to the Rust detector
//!   (`0.25` by default). Set it to the same `conf` Python used.
//! - `CCTV_PARITY_MIN_AGREEMENT` - fail unless at least this fraction of boxes
//!   agreed (`0..=1`). Unset prints the numbers without claiming a pass.
//!
//! Coordinates in `python.json` are normalised `0..1`, so one report is valid
//! for every resolution. Frames and model stay outside the repo.
//!
//! Without the variables the test prints a skip line and returns, which is the
//! same contract as `tests/detection_quality.rs` and `tests/onnx_inference.rs`.

use std::path::{Path, PathBuf};

use ai_engine::parity::{worst_frames, FrameParity, ParityReport};
use ai_engine::{Detector, DetectorConfig, NormalizedBox, RgbFrame};

/// Confidence floor handed to the Rust detector when the environment is silent.
///
/// Matches the lowest floor `tests/detection_quality.rs` reports at, so the two
/// harnesses can be run over the same frames.
const DEFAULT_THRESHOLD: f32 = 0.25;

/// `python.json` entry.
#[derive(Debug, serde::Deserialize)]
struct ReferenceFrame {
    frame: String,
    boxes: Vec<ReferenceBox>,
}

/// One Python box in normalised coordinates plus its score.
#[derive(Debug, serde::Deserialize)]
struct ReferenceBox {
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    confidence: f32,
}

#[test]
fn rust_boxes_agree_with_the_python_yolo_reference() {
    let (Ok(model), Ok(directory)) = (
        std::env::var("CCTV_PARITY_MODEL"),
        std::env::var("CCTV_PARITY_DIR"),
    ) else {
        eprintln!(
            "skipped: set CCTV_PARITY_MODEL and CCTV_PARITY_DIR to compare Rust with Python YOLO"
        );
        return;
    };

    let directory = PathBuf::from(directory);
    let reference = load_reference(&directory).expect("python.json must parse");
    assert!(!reference.is_empty(), "python.json listed no frames");

    let threshold = threshold_from_env();
    let mut detector = Detector::from_file(
        &model,
        DetectorConfig {
            confidence_threshold: threshold,
            ..DetectorConfig::default()
        },
    )
    .expect("load the detector");

    let mut report = ParityReport::default();
    let mut frames = Vec::with_capacity(reference.len());
    let mut skipped = 0_usize;

    for entry in &reference {
        let Some((path, width, height)) = frame_path(&directory, &entry.frame) else {
            skipped += 1;
            continue;
        };
        let bytes = std::fs::read(&path).unwrap_or_else(|error| {
            panic!("reading {} failed: {error}", path.display());
        });
        let Ok(frame) = RgbFrame::new(width, height, &bytes) else {
            skipped += 1;
            continue;
        };
        let detected = detector.detect(frame).expect("inference must succeed");

        // Both sides go through the same normalisation, so a delta is a
        // pipeline difference and never a units mismatch.
        let detected: Vec<(NormalizedBox, f32)> = detected
            .iter()
            .filter_map(|candidate| {
                NormalizedBox::from_pixels(candidate, width, height)
                    .map(|box_| (box_, candidate.confidence))
            })
            .collect();
        let truth: Vec<(NormalizedBox, f32)> = entry
            .boxes
            .iter()
            .map(|box_| {
                (
                    NormalizedBox::new(box_.x, box_.y, box_.w, box_.h),
                    box_.confidence,
                )
            })
            .collect();

        let compared = FrameParity::compare(entry.frame.clone(), &truth, &detected);
        report.record(&compared);
        frames.push(compared);
    }

    println!(
        "threshold {threshold:.2}, frames: {} in reference, {skipped} skipped (unreadable)",
        reference.len()
    );
    println!("{report}");
    println!("worst frames by one-sided boxes:");
    for frame in worst_frames(&frames, 5) {
        println!(
            "  {} matched={} missed={} extra={}",
            frame.frame,
            frame.pairs.len(),
            frame.missed,
            frame.extra
        );
    }

    assert!(
        report.frames > 0,
        "no frame could be compared; check frame names and sizes"
    );

    if let Some(floor) = agreement_floor_from_env() {
        assert!(
            report.agreement() >= floor,
            "Rust agreed on {:.3} of boxes, below the requested {floor:.3}: {report}",
            report.agreement()
        );
    }
}

/// Resolve `frames/<name>` and read `width`/`height` out of the file name.
fn frame_path(directory: &Path, name: &str) -> Option<(PathBuf, u32, u32)> {
    let stem = name.strip_suffix(".rgb")?;
    let (_, dimensions) = stem.rsplit_once('-')?;
    let (width, height) = dimensions.split_once('x')?;
    let width = width.parse().ok()?;
    let height = height.parse().ok()?;
    Some((directory.join("frames").join(name), width, height))
}

fn load_reference(directory: &Path) -> Result<Vec<ReferenceFrame>, String> {
    let path = directory.join("python.json");
    let raw = std::fs::read_to_string(&path)
        .map_err(|error| format!("reading {} failed: {error}", path.display()))?;
    serde_json::from_str(&raw)
        .map_err(|error| format!("parsing {} failed: {error}", path.display()))
}

/// Confidence floor from the environment, defaulting to [`DEFAULT_THRESHOLD`].
///
/// A value that does not parse is reported instead of silently replaced: a
/// typo would otherwise compare the two runtimes at different floors and make
/// the difference in counts look like a port bug.
fn threshold_from_env() -> f32 {
    let Ok(raw) = std::env::var("CCTV_PARITY_THRESHOLD") else {
        return DEFAULT_THRESHOLD;
    };
    raw.parse()
        .unwrap_or_else(|error| panic!("CCTV_PARITY_THRESHOLD={raw} is not a number: {error}"))
}

/// Agreement floor from the environment, or `None` when it is not set.
fn agreement_floor_from_env() -> Option<f32> {
    let raw = std::env::var("CCTV_PARITY_MIN_AGREEMENT").ok()?;
    let floor: f32 = raw
        .parse()
        .unwrap_or_else(|error| panic!("CCTV_PARITY_MIN_AGREEMENT={raw} is not a number: {error}"));
    assert!(
        (0.0..=1.0).contains(&floor),
        "CCTV_PARITY_MIN_AGREEMENT must be a fraction of 1, got {floor}"
    );
    Some(floor)
}
