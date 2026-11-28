//! Match detections against hand-labelled boxes and aggregate quality numbers.
//!
//! This is the pure half of `docs/detection-quality-todo.md` Q-03. A labelled
//! frame set turns "the boxes look better" into precision, recall, and a box
//! height histogram that can be compared between models and confidence floors.
//!
//! Nothing here touches ONNX or the file system, so the matcher runs on every
//! `cargo test`. `tests/detection_quality.rs` wires it to a real model and a
//! real frame set, and only runs when both are supplied through the
//! environment.

use std::cmp::Ordering;

use crate::nms::BoxRect;

/// IoU a prediction needs before it may claim a labelled person.
pub const DEFAULT_MATCH_IOU: f32 = 0.5;

/// A box in normalised frame space, `0..=1` on both axes.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NormalizedBox {
    /// Left edge as a fraction of the frame width.
    pub x: f32,
    /// Top edge as a fraction of the frame height.
    pub y: f32,
    /// Width as a fraction of the frame width.
    pub width: f32,
    /// Height as a fraction of the frame height.
    pub height: f32,
}

impl NormalizedBox {
    /// Construct a box from normalised coordinates.
    pub fn new(x: f32, y: f32, width: f32, height: f32) -> Self {
        Self {
            x,
            y,
            width,
            height,
        }
    }

    /// Normalise a pixel box from a frame of `frame_width x frame_height`.
    ///
    /// Returns `None` for a zero-sized frame or a non-finite result, so a
    /// broken frame is skipped instead of poisoning the counts.
    pub fn from_pixels(candidate: &BoxRect, frame_width: u32, frame_height: u32) -> Option<Self> {
        if frame_width == 0 || frame_height == 0 {
            return None;
        }
        let width = frame_width as f32;
        let height = frame_height as f32;
        let normalised = Self {
            x: candidate.x / width,
            y: candidate.y / height,
            width: candidate.width / width,
            height: candidate.height / height,
        };
        normalised.is_finite().then_some(normalised)
    }

    fn is_finite(self) -> bool {
        self.x.is_finite()
            && self.y.is_finite()
            && self.width.is_finite()
            && self.height.is_finite()
    }

    /// Area in normalised units, clamped at zero.
    pub fn area(self) -> f32 {
        self.width.max(0.0) * self.height.max(0.0)
    }
}

/// Intersection over union for two normalised boxes.
pub fn normalized_iou(a: NormalizedBox, b: NormalizedBox) -> f32 {
    let left = a.x.max(b.x);
    let top = a.y.max(b.y);
    let right = (a.x + a.width).min(b.x + b.width);
    let bottom = (a.y + a.height).min(b.y + b.height);
    let width = (right - left).max(0.0);
    let height = (bottom - top).max(0.0);
    let intersection = width * height;
    let union = a.area() + b.area() - intersection;
    if union <= 0.0 {
        0.0
    } else {
        intersection / union
    }
}

/// One prediction matched to one labelled person.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MatchedPair {
    /// The labelled box.
    pub ground_truth: NormalizedBox,
    /// The detection that claimed it.
    pub prediction: NormalizedBox,
    /// Overlap that justified the match.
    pub iou: f32,
    /// Detector confidence.
    pub confidence: f32,
}

/// True positives, false positives, and false negatives.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ConfusionCounts {
    /// Labelled people that a detection claimed.
    pub true_positives: usize,
    /// Detections that claimed nobody.
    pub false_positives: usize,
    /// Labelled people no detection claimed.
    pub false_negatives: usize,
}

impl ConfusionCounts {
    /// Accumulate another frame or threshold into these totals.
    pub fn add(&mut self, other: Self) {
        self.true_positives += other.true_positives;
        self.false_positives += other.false_positives;
        self.false_negatives += other.false_negatives;
    }

    /// `TP / (TP + FP)`, or `1.0` when nothing was predicted.
    ///
    /// An empty prediction set cannot be wrong about a person it never
    /// claimed, so precision is vacuously perfect; recall is what exposes the
    /// miss.
    pub fn precision(self) -> f32 {
        let claimed = self.true_positives + self.false_positives;
        if claimed == 0 {
            1.0
        } else {
            self.true_positives as f32 / claimed as f32
        }
    }

    /// `TP / (TP + FN)`, or `1.0` when no person was labelled.
    pub fn recall(self) -> f32 {
        let labelled = self.true_positives + self.false_negatives;
        if labelled == 0 {
            1.0
        } else {
            self.true_positives as f32 / labelled as f32
        }
    }

    /// Harmonic mean of precision and recall, or `0.0` when both are zero.
    pub fn f1(self) -> f32 {
        let (precision, recall) = (self.precision(), self.recall());
        if precision + recall <= 0.0 {
            0.0
        } else {
            2.0 * precision * recall / (precision + recall)
        }
    }

    /// Number of labelled people in the frames behind these counts.
    pub fn labelled(self) -> usize {
        self.true_positives + self.false_negatives
    }

    /// Number of detections behind these counts.
    pub fn predicted(self) -> usize {
        self.true_positives + self.false_positives
    }
}

/// Result of matching one frame's detections against its labels.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct FrameMatch {
    /// Counts for this frame.
    pub counts: ConfusionCounts,
    /// Pairs that met the IoU floor.
    pub matches: Vec<MatchedPair>,
    /// Detections that claimed nobody, highest confidence first.
    pub false_positives: Vec<NormalizedBox>,
    /// Labelled people no detection claimed.
    pub missed: Vec<NormalizedBox>,
}

/// Match one frame's detections against its labels.
///
/// Detections are consumed highest confidence first, and each may claim only
/// one still-unclaimed label, so two overlapping detections on the same person
/// become one true positive and one false positive rather than two hits. That
/// is the regression guard for the duplicate boxes `BoxFilter` and NMS exist to
/// remove (`docs/detection-quality-todo.md` Q-06): if they reappear here, the
/// duplicate is counted as an error instead of inflating recall.
pub fn match_frame(
    ground_truth: &[NormalizedBox],
    predictions: &[(NormalizedBox, f32)],
    min_iou: f32,
) -> FrameMatch {
    let mut order: Vec<usize> = (0..predictions.len()).collect();
    order.sort_by(|left, right| {
        predictions[*right]
            .1
            .partial_cmp(&predictions[*left].1)
            .unwrap_or(Ordering::Equal)
    });

    let mut claimed = vec![false; ground_truth.len()];
    let mut result = FrameMatch::default();

    for index in order {
        let (prediction, confidence) = predictions[index];
        let mut best: Option<(usize, f32)> = None;
        for (label_index, label) in ground_truth.iter().enumerate() {
            if claimed[label_index] {
                continue;
            }
            let iou = normalized_iou(*label, prediction);
            if iou < min_iou {
                continue;
            }
            if best.is_none_or(|(_, best_iou)| iou > best_iou) {
                best = Some((label_index, iou));
            }
        }

        match best {
            Some((label_index, iou)) => {
                claimed[label_index] = true;
                result.matches.push(MatchedPair {
                    ground_truth: ground_truth[label_index],
                    prediction,
                    iou,
                    confidence,
                });
            }
            None => result.false_positives.push(prediction),
        }
    }

    result.counts = ConfusionCounts {
        true_positives: result.matches.len(),
        false_positives: result.false_positives.len(),
        false_negatives: claimed.iter().filter(|claimed| !**claimed).count(),
    };
    result.missed = ground_truth
        .iter()
        .zip(&claimed)
        .filter(|(_, claimed)| !**claimed)
        .map(|(label, _)| *label)
        .collect();
    result
}

/// Counts for one named frame, kept so the report can rank bad frames.
#[derive(Debug, Clone, PartialEq)]
pub struct FrameScore {
    /// Frame file name, as written in `labels.json`.
    pub frame: String,
    /// Counts for this frame at one confidence floor.
    pub counts: ConfusionCounts,
}

/// Sum the counts of every frame.
pub fn summarize(frames: &[FrameScore]) -> ConfusionCounts {
    let mut totals = ConfusionCounts::default();
    for frame in frames {
        totals.add(frame.counts);
    }
    totals
}

/// The `limit` frames with the most false positives, then the most misses.
pub fn worst_frames(frames: &[FrameScore], limit: usize) -> Vec<&FrameScore> {
    let mut ranked: Vec<&FrameScore> = frames.iter().collect();
    ranked.sort_by(|left, right| {
        right
            .counts
            .false_positives
            .cmp(&left.counts.false_positives)
            .then(
                right
                    .counts
                    .false_negatives
                    .cmp(&left.counts.false_negatives),
            )
            .then(left.frame.cmp(&right.frame))
    });
    ranked.truncate(limit);
    ranked
}

/// Upper edges, in source-frame pixels, of the height histogram.
///
/// The split matches the distances that matter at 640 wide: under 16 px is a
/// head at the back wall, 16-64 px is a seated person across the room, and
/// above 128 px is somebody near the camera.
pub const HEIGHT_BUCKET_EDGES_PX: [f32; 5] = [16.0, 32.0, 64.0, 128.0, 256.0];

/// One bar of the height histogram.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HeightBucket {
    /// Inclusive lower edge, in pixels.
    pub lower_px: u32,
    /// Exclusive upper edge, in pixels; `None` for the open last bucket.
    pub upper_px: Option<u32>,
    /// Boxes that fell in this bucket.
    pub count: usize,
}

/// Histogram of detection heights, in source-frame pixels.
///
/// Non-finite and non-positive heights are skipped: a box with no height is a
/// decoder bug, and counting it as "small" would hide that.
pub fn height_histogram(heights_px: &[f32]) -> Vec<HeightBucket> {
    let mut counts = [0_usize; HEIGHT_BUCKET_EDGES_PX.len() + 1];
    for height in heights_px {
        if !height.is_finite() || *height <= 0.0 {
            continue;
        }
        let index = HEIGHT_BUCKET_EDGES_PX
            .iter()
            .position(|edge| *height < *edge)
            .unwrap_or(HEIGHT_BUCKET_EDGES_PX.len());
        counts[index] += 1;
    }

    let mut lower = 0.0_f32;
    counts
        .iter()
        .enumerate()
        .map(|(index, count)| {
            let upper = HEIGHT_BUCKET_EDGES_PX.get(index).copied();
            let bucket = HeightBucket {
                lower_px: lower as u32,
                upper_px: upper.map(|edge| edge as u32),
                count: *count,
            };
            if let Some(upper) = upper {
                lower = upper;
            }
            bucket
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn label() -> NormalizedBox {
        NormalizedBox::new(0.2, 0.2, 0.4, 0.4)
    }

    #[test]
    fn iou_just_below_the_floor_is_not_a_true_positive() {
        // Shifted 0.14 to the right: IoU is about 0.48, under the 0.5 floor.
        let shifted = NormalizedBox::new(0.34, 0.2, 0.4, 0.4);
        let iou = normalized_iou(label(), shifted);
        assert!((0.45..0.5).contains(&iou), "iou was {iou}");

        let matched = match_frame(&[label()], &[(shifted, 0.9)], DEFAULT_MATCH_IOU);
        assert_eq!(matched.counts.true_positives, 0);
        assert_eq!(matched.counts.false_positives, 1);
        assert_eq!(matched.counts.false_negatives, 1);
    }

    #[test]
    fn iou_above_the_floor_is_a_true_positive() {
        let shifted = NormalizedBox::new(0.25, 0.2, 0.4, 0.4);
        assert!(normalized_iou(label(), shifted) > 0.5);
        let matched = match_frame(&[label()], &[(shifted, 0.9)], DEFAULT_MATCH_IOU);
        assert_eq!(matched.counts.true_positives, 1);
        assert_eq!(matched.counts.false_positives, 0);
        assert_eq!(matched.counts.false_negatives, 0);
    }

    #[test]
    fn one_ground_truth_absorbs_only_one_detection() {
        // Two detections on the same person: the second is a false positive,
        // which is how a duplicate that survived NMS shows up as an error.
        let first = NormalizedBox::new(0.2, 0.2, 0.4, 0.4);
        let second = NormalizedBox::new(0.21, 0.2, 0.4, 0.4);
        let matched = match_frame(
            &[label()],
            &[(first, 0.95), (second, 0.80)],
            DEFAULT_MATCH_IOU,
        );
        assert_eq!(matched.counts.true_positives, 1);
        assert_eq!(matched.counts.false_positives, 1);
        assert_eq!(matched.counts.false_negatives, 0);
        assert_eq!(matched.matches.len(), 1);
        assert!((matched.matches[0].confidence - 0.95).abs() < 1e-6);
    }

    #[test]
    fn a_missed_person_is_a_false_negative() {
        let elsewhere = NormalizedBox::new(0.7, 0.6, 0.1, 0.2);
        let matched = match_frame(&[label()], &[(elsewhere, 0.9)], DEFAULT_MATCH_IOU);
        assert_eq!(matched.counts.true_positives, 0);
        assert_eq!(matched.counts.false_positives, 1);
        assert_eq!(matched.counts.false_negatives, 1);
        assert_eq!(matched.missed.len(), 1);
    }

    #[test]
    fn nothing_predicted_and_nothing_labelled_is_perfect() {
        let counts = match_frame(&[], &[], DEFAULT_MATCH_IOU).counts;
        assert_eq!(counts.precision(), 1.0);
        assert_eq!(counts.recall(), 1.0);
        assert_eq!(counts.f1(), 1.0);
    }

    #[test]
    fn an_empty_prediction_set_cannot_have_precision_but_has_no_recall() {
        let counts = match_frame(&[label()], &[], DEFAULT_MATCH_IOU).counts;
        assert_eq!(counts.precision(), 1.0);
        assert_eq!(counts.recall(), 0.0);
        assert_eq!(counts.f1(), 0.0);
    }

    #[test]
    fn totals_add_up_across_frames() {
        let frames = vec![
            FrameScore {
                frame: "a.rgb".to_string(),
                counts: ConfusionCounts {
                    true_positives: 2,
                    false_positives: 1,
                    false_negatives: 0,
                },
            },
            FrameScore {
                frame: "b.rgb".to_string(),
                counts: ConfusionCounts {
                    true_positives: 1,
                    false_positives: 0,
                    false_negatives: 2,
                },
            },
        ];
        let totals = summarize(&frames);
        assert_eq!(totals.true_positives, 3);
        assert_eq!(totals.false_positives, 1);
        assert_eq!(totals.false_negatives, 2);
        assert_eq!(totals.labelled(), 5);
        assert_eq!(totals.predicted(), 4);
    }

    #[test]
    fn worst_frames_are_ranked_by_false_positives() {
        let frame = |name: &str, false_positives: usize, false_negatives: usize| FrameScore {
            frame: name.to_string(),
            counts: ConfusionCounts {
                true_positives: 0,
                false_positives,
                false_negatives,
            },
        };
        let frames = vec![
            frame("a", 1, 0),
            frame("b", 3, 0),
            frame("c", 0, 4),
            frame("d", 2, 1),
        ];
        let worst = worst_frames(&frames, 2);
        assert_eq!(worst.len(), 2);
        assert_eq!(worst[0].frame, "b");
        assert_eq!(worst[1].frame, "d");
    }

    #[test]
    fn height_histogram_places_each_box_in_one_bucket() {
        let buckets = height_histogram(&[8.0, 20.0, 40.0, 100.0, 200.0, 400.0]);
        assert_eq!(buckets.len(), HEIGHT_BUCKET_EDGES_PX.len() + 1);
        assert_eq!(buckets[0].count, 1);
        assert_eq!(buckets[0].upper_px, Some(16));
        assert_eq!(buckets[2].count, 1);
        assert_eq!(buckets[5].count, 1);
        assert_eq!(buckets[5].upper_px, None);
        assert_eq!(buckets.iter().map(|bucket| bucket.count).sum::<usize>(), 6);
    }

    #[test]
    fn height_histogram_ignores_degenerate_heights() {
        let buckets = height_histogram(&[0.0, -4.0, f32::NAN, f32::INFINITY, 40.0]);
        assert_eq!(buckets.iter().map(|bucket| bucket.count).sum::<usize>(), 1);
    }

    #[test]
    fn a_pixel_box_normalises_against_the_frame() {
        let pixels = BoxRect {
            x: 64.0,
            y: 48.0,
            width: 128.0,
            height: 96.0,
            confidence: 0.8,
        };
        let normalised = NormalizedBox::from_pixels(&pixels, 640, 480).unwrap();
        assert!((normalised.x - 0.1).abs() < 1e-6);
        assert!((normalised.y - 0.1).abs() < 1e-6);
        assert!((normalised.width - 0.2).abs() < 1e-6);
        assert!((normalised.height - 0.2).abs() < 1e-6);
        assert!(NormalizedBox::from_pixels(&pixels, 0, 480).is_none());
    }
}
