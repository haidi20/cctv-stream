//! Compare the Rust detector against a Python YOLO reference on the same frames.
//!
//! [`crate::quality`] answers "is the detection right" against hand labels. This
//! module answers a different question: "does the Rust port (letterbox, output
//! decode, NMS, coordinate mapping) report the same boxes as the Python
//! reference implementation". Both run the same weights on the same frames, so a
//! large geometry or confidence delta is a port bug, not a model limitation.
//!
//! Nothing here touches ONNX or the file system, so the matcher runs in every
//! `cargo test`. `tests/parity_with_python.rs` wires it to real inference and a
//! `python.json` produced by `tools/model-export/python_reference.py`, and only
//! runs when both are supplied through the environment.
//!
//! Deltas are reported in normalised units: `1.0` is the full frame width, so
//! `0.01` is 1 % of the frame - about 6 px on a 640-wide source. Reporting the
//! frame-relative number keeps one report valid for every resolution.

use std::cmp::Ordering;

use crate::quality::{normalized_iou, NormalizedBox};

/// IoU at which a Rust box and a Python box count as the same person.
///
/// The same floor the hand-label harness uses. Lower would pair two people who
/// share a chair; higher would report a real small shift as a missing person.
pub const DEFAULT_PARITY_IOU: f32 = 0.5;

/// Longest edge shift between two boxes, in normalised units.
///
/// The maximum of the four edge differences, not the centre distance: a box
/// that is right about the centre but twice too wide is a port bug, and a centre
/// distance would hide it. `1.0` is a full frame width.
pub fn edge_delta(reference: NormalizedBox, detected: NormalizedBox) -> f32 {
    let left = (reference.x - detected.x).abs();
    let top = (reference.y - detected.y).abs();
    let right = (reference.x + reference.width - (detected.x + detected.width)).abs();
    let bottom = (reference.y + reference.height - (detected.y + detected.height)).abs();
    left.max(top).max(right).max(bottom)
}

/// One reference box and the Rust box that claimed it.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ParityPair {
    /// Box from the Python reference, in normalised coordinates.
    pub reference: NormalizedBox,
    /// Confidence the Python reference gave that box.
    pub reference_confidence: f32,
    /// The Rust box that matched it.
    pub detected: NormalizedBox,
    /// Confidence the Rust detector gave that box.
    pub detected_confidence: f32,
    /// Overlap that justified the match.
    pub iou: f32,
}

impl ParityPair {
    /// Longest edge shift of this pair, in normalised units.
    pub fn edge_delta(&self) -> f32 {
        edge_delta(self.reference, self.detected)
    }

    /// Difference between the two confidence scores.
    pub fn confidence_delta(&self) -> f32 {
        (self.reference_confidence - self.detected_confidence).abs()
    }
}

/// One frame compared: the pairs, plus what only one side reported.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct FrameParity {
    /// Frame file name, as written in `python.json`.
    pub frame: String,
    /// Boxes both sides found, paired.
    pub pairs: Vec<ParityPair>,
    /// People the Python reference found and Rust did not.
    pub missed: usize,
    /// People Rust found and the Python reference did not.
    pub extra: usize,
}

impl FrameParity {
    /// Compare one frame at [`DEFAULT_PARITY_IOU`].
    pub fn compare(
        frame: impl Into<String>,
        reference: &[(NormalizedBox, f32)],
        detected: &[(NormalizedBox, f32)],
    ) -> Self {
        Self::compare_with_threshold(frame, reference, detected, DEFAULT_PARITY_IOU)
    }

    /// Compare one frame at an explicit IoU floor.
    ///
    /// Both lists are consumed highest confidence first and each box may claim
    /// only one box on the other side, so two overlapping Rust boxes on one
    /// reference person are one pair plus one extra - the same rule the
    /// hand-label matcher uses, and the regression guard for duplicate boxes.
    pub fn compare_with_threshold(
        frame: impl Into<String>,
        reference: &[(NormalizedBox, f32)],
        detected: &[(NormalizedBox, f32)],
        min_iou: f32,
    ) -> Self {
        let detected_order = by_confidence(detected);
        let mut claimed = vec![false; detected.len()];
        let mut pairs = Vec::new();
        let mut missed = 0;

        for reference_index in by_confidence(reference) {
            let (reference_box, reference_confidence) = reference[reference_index];
            let mut best: Option<(usize, f32)> = None;
            for detected_index in &detected_order {
                if claimed[*detected_index] {
                    continue;
                }
                let iou = normalized_iou(reference_box, detected[*detected_index].0);
                if iou < min_iou {
                    continue;
                }
                if best.is_none_or(|(_, best_iou)| iou > best_iou) {
                    best = Some((*detected_index, iou));
                }
            }

            match best {
                Some((detected_index, iou)) => {
                    claimed[detected_index] = true;
                    let (detected_box, detected_confidence) = detected[detected_index];
                    pairs.push(ParityPair {
                        reference: reference_box,
                        reference_confidence,
                        detected: detected_box,
                        detected_confidence,
                        iou,
                    });
                }
                None => missed += 1,
            }
        }

        Self {
            frame: frame.into(),
            pairs,
            missed,
            extra: claimed.iter().filter(|claimed| !**claimed).count(),
        }
    }

    /// Boxes only one side reported.
    pub fn mismatched(&self) -> usize {
        self.missed + self.extra
    }
}

/// Index list of a box/score list, highest confidence first.
fn by_confidence(boxes: &[(NormalizedBox, f32)]) -> Vec<usize> {
    let mut order: Vec<usize> = (0..boxes.len()).collect();
    order.sort_by(|left, right| {
        boxes[*right]
            .1
            .partial_cmp(&boxes[*left].1)
            .unwrap_or(Ordering::Equal)
    });
    order
}

/// Totals over every compared frame.
///
/// The sums are private so the public numbers can never disagree with each
/// other: [`ParityReport::record`] is the only way to add a frame, and it
/// updates the means and the extremes together.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ParityReport {
    /// Frames compared.
    pub frames: usize,
    /// Boxes both sides reported, at or above the IoU floor.
    pub matched: usize,
    /// People only the Python reference found.
    pub missed: usize,
    /// People only Rust found.
    pub extra: usize,
    /// Mean IoU of the matched pairs; `0.0` before the first match.
    pub mean_iou: f32,
    /// Lowest IoU among the matched pairs.
    pub min_iou: f32,
    /// Mean longest-edge shift of the matched pairs, in normalised units.
    pub mean_edge_delta: f32,
    /// Largest longest-edge shift of the matched pairs, in normalised units.
    pub max_edge_delta: f32,
    /// Mean absolute confidence difference of the matched pairs.
    pub mean_confidence_delta: f32,
    /// Largest absolute confidence difference of the matched pairs.
    pub max_confidence_delta: f32,
    iou_sum: f32,
    edge_sum: f32,
    confidence_sum: f32,
    extremes_seen: bool,
}

impl ParityReport {
    /// Fold one compared frame into the totals.
    pub fn record(&mut self, frame: &FrameParity) {
        self.frames += 1;
        self.missed += frame.missed;
        self.extra += frame.extra;

        for pair in &frame.pairs {
            self.matched += 1;
            let edge = pair.edge_delta();
            let confidence = pair.confidence_delta();
            self.iou_sum += pair.iou;
            self.edge_sum += edge;
            self.confidence_sum += confidence;
            if !self.extremes_seen {
                self.extremes_seen = true;
                self.min_iou = pair.iou;
                self.max_edge_delta = edge;
                self.max_confidence_delta = confidence;
            } else {
                self.min_iou = self.min_iou.min(pair.iou);
                self.max_edge_delta = self.max_edge_delta.max(edge);
                self.max_confidence_delta = self.max_confidence_delta.max(confidence);
            }
        }

        // Derived numbers are recomputed from the sums, never accumulated: a
        // running mean drifts after a few thousand frames.
        if self.matched > 0 {
            let count = self.matched as f32;
            self.mean_iou = self.iou_sum / count;
            self.mean_edge_delta = self.edge_sum / count;
            self.mean_confidence_delta = self.confidence_sum / count;
        }
    }

    /// How many boxes both sides agreed on, as a fraction of every box either
    /// side reported.
    ///
    /// `1.0` when neither side reported anything: two empty frames agree
    /// perfectly, and calling that a failure would punish a correct run.
    pub fn agreement(&self) -> f32 {
        let total = self.matched + self.missed + self.extra;
        if total == 0 {
            1.0
        } else {
            self.matched as f32 / total as f32
        }
    }
}

impl std::fmt::Display for ParityReport {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "frames={} matched={} missed={} extra={} agreement={:.3} mean_iou={:.3} \
             min_iou={:.3} mean_edge={:.4} max_edge={:.4} mean_conf={:.3} max_conf={:.3}",
            self.frames,
            self.matched,
            self.missed,
            self.extra,
            self.agreement(),
            self.mean_iou,
            self.min_iou,
            self.mean_edge_delta,
            self.max_edge_delta,
            self.mean_confidence_delta,
            self.max_confidence_delta
        )
    }
}

/// The `limit` frames with the most one-sided boxes, worst first.
///
/// A large edge shift on a matched pair is already visible in the report; this
/// ranking is for frames where a side simply had a box the other did not, which
/// is what points at a confidence or NMS difference instead of a geometry one.
pub fn worst_frames(frames: &[FrameParity], limit: usize) -> Vec<&FrameParity> {
    let mut ranked: Vec<&FrameParity> = frames.iter().collect();
    ranked.sort_by(|left, right| {
        right
            .mismatched()
            .cmp(&left.mismatched())
            .then(left.frame.cmp(&right.frame))
    });
    ranked.truncate(limit);
    ranked
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reference_box() -> NormalizedBox {
        NormalizedBox::new(0.20, 0.20, 0.10, 0.30)
    }

    #[test]
    fn an_identical_box_is_a_pair_with_no_geometry_error() {
        let frame = FrameParity::compare(
            "frame-0001.rgb",
            &[(reference_box(), 0.9)],
            &[(reference_box(), 0.88)],
        );

        assert_eq!(frame.pairs.len(), 1);
        assert_eq!(frame.mismatched(), 0);
        assert!(
            (frame.pairs[0].iou - 1.0).abs() < 1e-6,
            "identical boxes overlap completely"
        );
        assert_eq!(frame.pairs[0].edge_delta(), 0.0, "same box, no shift");
        assert!(
            (frame.pairs[0].confidence_delta() - 0.02).abs() < 1e-6,
            "only the score differs"
        );
    }

    #[test]
    fn a_shifted_box_reports_the_longest_edge_difference() {
        let detected = NormalizedBox::new(0.22, 0.20, 0.10, 0.30);
        let frame = FrameParity::compare(
            "frame-0002.rgb",
            &[(reference_box(), 0.9)],
            &[(detected, 0.9)],
        );

        assert_eq!(frame.pairs.len(), 1);
        assert!(
            (frame.pairs[0].edge_delta() - 0.02).abs() < 1e-6,
            "both edges moved by 2% of the frame, and the delta must say so"
        );
    }

    #[test]
    fn a_box_only_one_side_found_is_left_out_of_the_pairs() {
        let other = NormalizedBox::new(0.60, 0.60, 0.10, 0.20);
        let frame = FrameParity::compare(
            "frame-0003.rgb",
            &[(reference_box(), 0.9), (other, 0.8)],
            &[(reference_box(), 0.9)],
        );

        assert_eq!(frame.pairs.len(), 1);
        assert_eq!(frame.missed, 1, "the reference saw someone Rust did not");
        assert_eq!(frame.extra, 0);
        assert_eq!(frame.mismatched(), 1);
    }

    #[test]
    fn two_overlapping_rust_boxes_on_one_person_are_one_pair_and_one_extra() {
        let duplicate = NormalizedBox::new(0.205, 0.20, 0.10, 0.30);
        let frame = FrameParity::compare(
            "frame-0004.rgb",
            &[(reference_box(), 0.9)],
            &[(reference_box(), 0.9), (duplicate, 0.7)],
        );

        assert_eq!(frame.pairs.len(), 1, "one reference person, one pair");
        assert_eq!(frame.extra, 1, "the duplicate must be counted as an error");
    }

    #[test]
    fn a_box_below_the_iou_floor_is_not_paired() {
        let far = NormalizedBox::new(0.70, 0.20, 0.10, 0.30);
        let frame =
            FrameParity::compare("frame-0005.rgb", &[(reference_box(), 0.9)], &[(far, 0.9)]);

        assert!(frame.pairs.is_empty());
        assert_eq!((frame.missed, frame.extra), (1, 1));
    }

    #[test]
    fn the_report_averages_only_matched_pairs_and_keeps_the_worst_shift() {
        let mut report = ParityReport::default();
        report.record(&FrameParity::compare(
            "frame-0001.rgb",
            &[(reference_box(), 0.9)],
            &[(NormalizedBox::new(0.21, 0.20, 0.10, 0.30), 0.8)],
        ));
        report.record(&FrameParity::compare(
            "frame-0002.rgb",
            &[(reference_box(), 0.9)],
            &[(NormalizedBox::new(0.205, 0.20, 0.10, 0.30), 0.9)],
        ));

        assert_eq!(report.frames, 2);
        assert_eq!(report.matched, 2);
        assert_eq!(report.missed + report.extra, 0);
        assert!((report.mean_edge_delta - 0.0075).abs() < 1e-6);
        assert!((report.max_edge_delta - 0.01).abs() < 1e-6);
        assert!((report.mean_confidence_delta - 0.05).abs() < 1e-6);
        assert!(
            report.min_iou < report.mean_iou,
            "the minimum must stay the worst pair, not the average"
        );
    }

    #[test]
    fn two_empty_frames_agree_completely() {
        let mut report = ParityReport::default();
        report.record(&FrameParity::compare("frame-0001.rgb", &[], &[]));

        assert_eq!(report.agreement(), 1.0);
        assert_eq!(report.matched, 0);
        assert_eq!(report.mean_iou, 0.0, "no pair means no invented overlap");
    }

    #[test]
    fn the_report_ranks_the_frames_with_the_most_one_sided_boxes() {
        let clean = FrameParity::compare(
            "frame-0001.rgb",
            &[(reference_box(), 0.9)],
            &[(reference_box(), 0.9)],
        );
        let messy = FrameParity::compare("frame-0002.rgb", &[], &[(reference_box(), 0.9)]);
        let frames = vec![clean, messy];

        let worst = worst_frames(&frames, 1);
        assert_eq!(worst.len(), 1);
        assert_eq!(worst[0].frame, "frame-0002.rgb");
    }
}
