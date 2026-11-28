//! Frame-to-frame association for the boxes the overlay draws.
//!
//! Detection itself is stateless: every analysed frame returns exactly what the
//! model found, so one frame in which a person bends over, turns away, or has
//! their body covered deletes their box, and the next frame pops it back a few
//! pixels away. At 2 FPS that reads as a box blinking for roughly a second
//! (Q-07 in `docs/detection-quality-todo.md`).
//!
//! A track is a short memory, not a prediction. It survives [`MAX_MISSES`]
//! frames that missed it, keeps the frame stamp of the frame where the person
//! really was seen, and is smoothed while it keeps being re-detected. A track
//! that is not re-detected never moves.
//!
//! Scope is display only. Table statuses keep consuming the raw detections in
//! `StateInner::observe_frame`, so `spatial-engine`'s debounce stays the only
//! thing that decides a table and a smoothed box can never drag a person into
//! the neighbouring polygon.

use crate::state::AnalysedBox;

/// Minimum overlap for a fresh box to continue an existing track.
///
/// `0.3` is the usual person-tracking floor: loose enough for a sitting person
/// whose box changes shape between frames, strict enough that two people at one
/// table do not steal each other's boxes.
pub(crate) const MATCH_IOU: f32 = 0.3;

/// Missed frames a track stays on screen for.
///
/// Two misses is about a second at 2 FPS: long enough to ride out one bad frame,
/// short enough that someone who actually left cannot hide behind a ghost box.
pub(crate) const MAX_MISSES: u8 = 2;

/// Weight of the newest geometry when a track is re-detected.
///
/// `0.5` halves the few-pixel jitter the model adds between frames without
/// lagging visibly behind someone walking.
pub(crate) const SMOOTHING_ALPHA: f32 = 0.5;

/// Associate this frame's boxes with the ones already on screen.
///
/// `previous` are the boxes currently published, `fresh` the boxes decoded from
/// the newest analysed frame. The result is what the overlay should draw now:
/// matched tracks blended with the new geometry, brand-new boxes, plus the
/// unmatched tracks still inside their miss budget.
pub(crate) fn advance(previous: Vec<AnalysedBox>, fresh: Vec<AnalysedBox>) -> Vec<AnalysedBox> {
    let mut claimed = vec![false; previous.len()];
    let mut published = Vec::with_capacity(fresh.len() + previous.len());

    for detection in fresh {
        // Best overlap wins, not first match: two people walking past each
        // other keep their own boxes instead of swapping them.
        let mut best: Option<(usize, f32)> = None;
        for (index, track) in previous.iter().enumerate() {
            if claimed[index] {
                continue;
            }
            let overlap = overlap(track, &detection);
            if overlap < MATCH_IOU {
                continue;
            }
            if best.is_none_or(|(_, best_overlap)| overlap > best_overlap) {
                best = Some((index, overlap));
            }
        }

        match best {
            Some((index, _)) => {
                claimed[index] = true;
                published.push(smoothed(&previous[index], &detection));
            }
            None => published.push(detection),
        }
    }

    for (index, track) in previous.into_iter().enumerate() {
        if claimed[index] {
            continue;
        }
        let misses = track.misses.saturating_add(1);
        if misses <= MAX_MISSES {
            published.push(AnalysedBox { misses, ..track });
        }
    }

    published
}

/// Continue a matched track: blended geometry, fresh frame stamp.
///
/// The stamp restarting is the point: a re-detected box really was seen in this
/// frame, so `age_ms` must fall back to zero. A track that is *not* re-detected
/// keeps its old stamp instead, which is what makes a stale box keep growing
/// older on screen rather than pretending to be new (Q-12).
fn smoothed(track: &AnalysedBox, fresh: &AnalysedBox) -> AnalysedBox {
    AnalysedBox {
        x: blend(track.x, fresh.x),
        y: blend(track.y, fresh.y),
        width: blend(track.width, fresh.width),
        height: blend(track.height, fresh.height),
        confidence: fresh.confidence,
        frame_seq: fresh.frame_seq,
        captured_at: fresh.captured_at,
        misses: 0,
    }
}

fn blend(previous: f32, fresh: f32) -> f32 {
    previous + (fresh - previous) * SMOOTHING_ALPHA
}

/// Intersection over union of a track and a fresh box, in `0.0..=1.0`.
///
/// Boxes with no area score `0.0`: the decoder rejects degenerate boxes before
/// they ever reach a session (`BoxFilter` in `ai-engine`), and inventing a
/// second matching rule for them would make tracking behave differently on a
/// real frame than a test fixture suggests.
fn overlap(track: &AnalysedBox, fresh: &AnalysedBox) -> f32 {
    let left = track.x.max(fresh.x);
    let top = track.y.max(fresh.y);
    let right = (track.x + track.width).min(fresh.x + fresh.width);
    let bottom = (track.y + track.height).min(fresh.y + fresh.height);
    let intersection = (right - left).max(0.0) * (bottom - top).max(0.0);
    let union = track.width * track.height + fresh.width * fresh.height - intersection;
    if union <= 0.0 {
        return 0.0;
    }
    (intersection / union).clamp(0.0, 1.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn boxed(x: f32, y: f32, width: f32, height: f32, frame_seq: u64) -> AnalysedBox {
        AnalysedBox {
            x,
            y,
            width,
            height,
            confidence: 0.9,
            frame_seq,
            captured_at: Duration::from_millis(frame_seq * 500),
            misses: 0,
        }
    }

    #[test]
    fn a_re_detected_person_keeps_one_box_and_resets_its_misses() {
        let first = advance(Vec::new(), vec![boxed(0.20, 0.20, 0.10, 0.30, 1)]);
        let missed = advance(first, Vec::new());
        assert_eq!(missed.len(), 1, "one missed frame keeps the box");
        assert_eq!(missed[0].misses, 1);

        let again = advance(missed, vec![boxed(0.21, 0.20, 0.10, 0.30, 3)]);
        assert_eq!(again.len(), 1, "the same person must not become two boxes");
        assert_eq!(again[0].misses, 0);
        assert_eq!(again[0].frame_seq, 3, "a re-detected box is fresh again");
    }

    #[test]
    fn an_unmatched_track_never_moves_on_its_own() {
        let first = advance(Vec::new(), vec![boxed(0.20, 0.20, 0.10, 0.30, 1)]);
        let missed = advance(first, Vec::new());
        assert_eq!(missed[0].x, 0.20, "no detection, no invented position");
        assert_eq!(missed[0].frame_seq, 1, "and no invented frame either");
        assert_eq!(missed[0].captured_at, Duration::from_millis(500));
    }

    #[test]
    fn two_people_keep_their_own_boxes_when_they_pass_each_other() {
        let previous = vec![
            boxed(0.10, 0.10, 0.10, 0.20, 1),
            boxed(0.40, 0.10, 0.10, 0.20, 1),
        ];
        // The left person steps right, the right person steps left: the overlap
        // is strongest with the box each of them came from.
        let fresh = vec![
            boxed(0.13, 0.10, 0.10, 0.20, 2),
            boxed(0.37, 0.10, 0.10, 0.20, 2),
        ];
        let published = advance(previous, fresh);

        assert_eq!(published.len(), 2);
        assert!(
            published[0].x < 0.3 && published[1].x > 0.3,
            "each person must keep the side they were on"
        );
        assert!(published.iter().all(|track| track.misses == 0));
    }

    #[test]
    fn smoothing_keeps_the_centre_within_15_percent_of_the_true_one() {
        let mut published = advance(Vec::new(), vec![boxed(0.20, 0.20, 0.08, 0.20, 1)]);
        for step in 1..=5_u64 {
            let truth = 0.20 + 0.04 * step as f32;
            published = advance(published, vec![boxed(truth, 0.20, 0.08, 0.20, step + 1)]);

            let centre = published[0].x + published[0].width / 2.0;
            let true_centre = truth + 0.04;
            assert!(
                (centre - true_centre).abs() <= 0.15,
                "smoothed centre {centre} drifted more than 15% of the frame from {true_centre}"
            );
            assert!(
                (centre - true_centre).abs() <= 0.05,
                "a person walking 4% per frame must not be trailed by more than a frame"
            );
        }
    }

    #[test]
    fn a_box_without_area_never_matches_anything() {
        let previous = vec![boxed(0.5, 0.5, 0.0, 0.0, 1)];
        let published = advance(previous, vec![boxed(0.5, 0.5, 0.0, 0.0, 2)]);

        assert_eq!(
            published.len(),
            2,
            "a degenerate box cannot be associated, so it is not silently reused"
        );
    }

    #[test]
    fn a_track_is_dropped_after_three_missed_frames() {
        let mut published = advance(Vec::new(), vec![boxed(0.20, 0.20, 0.10, 0.30, 1)]);
        for _ in 0..MAX_MISSES {
            published = advance(published, Vec::new());
            assert_eq!(published.len(), 1, "still inside the miss budget");
        }
        published = advance(published, Vec::new());
        assert!(published.is_empty(), "the third miss must remove the box");
    }
}
