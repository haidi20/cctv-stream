//! Precision, recall, and box-height histogram over a labelled frame set.
//!
//! Host-only and opt-in, so `cargo test --workspace` stays hermetic. Both
//! variables must be set:
//!
//! - `CCTV_QUALITY_MODEL` - an ONNX person detector. Not committed.
//! - `CCTV_QUALITY_DIR` - a directory holding
//!   `frames/frame-<n>-<w>x<h>.rgb` (raw RGB888, the same layout the FFI takes)
//!   and `labels.json`:
//!
//!   ```json
//!   [{ "frame": "frame-0001-640x480.rgb",
//!      "boxes": [{ "x": 0.1, "y": 0.2, "w": 0.1, "h": 0.3 }] }]
//!   ```
//!
//! Coordinates in `labels.json` are normalised `0..1`. Frames and labels stay
//! outside the repo; nothing binary is committed.
//!
//! Without the variables the test prints a skip line and returns, which is the
//! same contract as `onnx_inference.rs`.

use std::path::{Path, PathBuf};

use ai_engine::quality::{
    height_histogram, match_frame, summarize, worst_frames, ConfusionCounts, FrameScore,
    NormalizedBox,
};
use ai_engine::{Detector, DetectorConfig, RgbFrame};

/// Confidence floors reported for every run.
const THRESHOLDS: [f32; 3] = [0.25, 0.35, 0.50];

/// Lowest floor the model is asked to report at; the others are filters of it.
const DETECT_THRESHOLD: f32 = 0.25;

/// `labels.json` entry.
#[derive(Debug, serde::Deserialize)]
struct LabelFrame {
    frame: String,
    boxes: Vec<LabelBox>,
}

/// One labelled person in normalised coordinates.
#[derive(Debug, serde::Deserialize)]
struct LabelBox {
    x: f32,
    y: f32,
    w: f32,
    h: f32,
}

impl LabelBox {
    fn to_normalized(&self) -> NormalizedBox {
        NormalizedBox::new(self.x, self.y, self.w, self.h)
    }
}

#[test]
fn labelled_frames_report_precision_and_recall_per_threshold() {
    let (Ok(model), Ok(directory)) = (
        std::env::var("CCTV_QUALITY_MODEL"),
        std::env::var("CCTV_QUALITY_DIR"),
    ) else {
        eprintln!(
            "skipped: set CCTV_QUALITY_MODEL and CCTV_QUALITY_DIR to measure detection quality"
        );
        return;
    };

    let directory = PathBuf::from(directory);
    let labels = load_labels(&directory).expect("labels.json must parse");
    assert!(!labels.is_empty(), "labels.json listed no frames");

    let mut detector = Detector::from_file(
        &model,
        DetectorConfig {
            confidence_threshold: DETECT_THRESHOLD,
            ..DetectorConfig::default()
        },
    )
    .expect("load the detector");

    // One bucket of frame scores per confidence floor, indexed like
    // `THRESHOLDS` so the report cannot silently disagree with the loop.
    let mut per_threshold: Vec<Vec<FrameScore>> = vec![Vec::new(); THRESHOLDS.len()];
    let mut heights: Vec<f32> = Vec::new();
    let mut skipped = 0_usize;

    for label in &labels {
        let Some((path, width, height)) = frame_path(&directory, &label.frame) else {
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

        // One inference per frame at the lowest floor; the higher floors are
        // a filter of the same boxes. NMS is greedy by confidence, so dropping
        // low-confidence boxes afterwards cannot change which box wins.
        let predictions: Vec<(NormalizedBox, f32)> = detected
            .iter()
            .filter_map(|candidate| {
                NormalizedBox::from_pixels(candidate, width, height)
                    .map(|box_| (box_, candidate.confidence))
            })
            .collect();

        for (index, threshold) in THRESHOLDS.iter().enumerate() {
            let kept: Vec<(NormalizedBox, f32)> = predictions
                .iter()
                .copied()
                .filter(|(_, confidence)| *confidence >= *threshold)
                .collect();
            let truth: Vec<NormalizedBox> =
                label.boxes.iter().map(LabelBox::to_normalized).collect();
            let matched = match_frame(&truth, &kept, ai_engine::DEFAULT_MATCH_IOU);
            per_threshold[index].push(FrameScore {
                frame: label.frame.clone(),
                counts: matched.counts,
            });
        }

        for candidate in &detected {
            heights.push(candidate.height);
        }
    }

    println!("frames: {} labelled, {} skipped", labels.len(), skipped);
    println!(
        "{:<8} {:>10} {:>10} {:>8} {:>6} {:>6} {:>6}",
        "threshold", "precision", "recall", "f1", "tp", "fp", "fn"
    );
    for (index, threshold) in THRESHOLDS.iter().enumerate() {
        let totals: ConfusionCounts = summarize(&per_threshold[index]);
        println!(
            "{:<8.2} {:>10.3} {:>10.3} {:>8.3} {:>6} {:>6} {:>6}",
            threshold,
            totals.precision(),
            totals.recall(),
            totals.f1(),
            totals.true_positives,
            totals.false_positives,
            totals.false_negatives
        );
    }

    let histogram = height_histogram(&heights);
    println!("detection heights (px):");
    for bucket in histogram {
        match bucket.upper_px {
            Some(upper) => println!("  {:>4}-{:<4} {}", bucket.lower_px, upper, bucket.count),
            None => println!("  {:>4}+     {}", bucket.lower_px, bucket.count),
        }
    }

    println!("worst frames at {:.2}:", THRESHOLDS[1]);
    for frame in worst_frames(&per_threshold[1], 5) {
        println!(
            "  {} fp={} fn={}",
            frame.frame, frame.counts.false_positives, frame.counts.false_negatives
        );
    }

    let scored: usize = per_threshold[0].len();
    assert!(
        scored > 0,
        "no frame could be scored; check frame sizes and labels.json"
    );
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

fn load_labels(directory: &Path) -> Result<Vec<LabelFrame>, String> {
    let path = directory.join("labels.json");
    let raw = std::fs::read_to_string(&path)
        .map_err(|error| format!("reading {} failed: {error}", path.display()))?;
    serde_json::from_str(&raw)
        .map_err(|error| format!("parsing {} failed: {error}", path.display()))
}
