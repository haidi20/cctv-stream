//! Detects a camera that no longer looks at the view its table map was drawn for.
//!
//! A map is a set of polygons in normalised `0..=1` frame space (ADR 0008).
//! That space only stays valid while the camera keeps pointing at the same
//! scene: after a pan, tilt, or zoom the polygons still cover *somewhere* in
//! the frame, so point-in-polygon keeps answering confidently about the wrong
//! tables. [`crate::geometry::needs_recalibration`] cannot see that, because a
//! pan never changes the resolution.
//!
//! This module compares a low-resolution luminance grid of the current frame
//! with the grid captured when the map was installed. A pan moves the scene
//! across the whole frame, so most cells change; a person, a chair, or a plate
//! changes far fewer; and a uniform brightness change not at all once each
//! signature is centred on its own median.
//!
//! The grid is computed with plain arithmetic on the RGB888 buffer the FFI
//! layer already owns, so no new dependency is added and `spatial-engine` stays
//! the small crate the rest of the workspace links.

use thiserror::Error;

/// Columns in the default view grid.
///
/// 32x18 keeps a pan obvious while the whole comparison stays far below the
/// cost of one inference on the target phone.
pub const DEFAULT_VIEW_COLS: u32 = 32;
/// Rows in the default view grid.
pub const DEFAULT_VIEW_ROWS: u32 = 18;

/// Samples taken per cell, and per axis.
///
/// Sixteen samples per cell read a 640x360 frame at 9 216 points instead of
/// 230 400, and the mean is still stable enough for a whole-frame comparison.
/// Inference costs ~200 ms on the target phone, so this must stay two orders of
/// magnitude cheaper than that.
const SAMPLES_PER_AXIS: u32 = 4;

/// Why a frame signature could not be built.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum ViewError {
    /// The frame has no pixels.
    #[error("frame width and height must be greater than zero")]
    EmptyFrame,
    /// The RGB buffer does not match the declared frame size.
    #[error("rgb buffer has {len} bytes, expected {expected} for {width}x{height}")]
    BufferLength {
        /// Declared width.
        width: u32,
        /// Declared height.
        height: u32,
        /// Bytes actually provided.
        len: usize,
        /// Bytes required for packed RGB888.
        expected: usize,
    },
    /// The requested grid has no cells.
    #[error("view grid must have at least one column and one row")]
    EmptyGrid,
}

/// Mean luminance of every cell of a downsampled frame.
///
/// Cells are normalised to `0.0..=1.0`. The grid is deliberately coarse: it
/// answers "is this the same scene?" and nothing else, so a slightly different
/// JPEG quality or a moving shadow cannot flip it.
#[derive(Debug, Clone, PartialEq)]
pub struct ViewSignature {
    cols: u32,
    rows: u32,
    cells: Vec<f32>,
    /// Median luminance of the grid, computed once per frame.
    ///
    /// The comparison centres each signature on this value instead of the mean:
    /// a person covering a quarter of the frame shifts a mean enough to make
    /// every cell look different, while a median does not move until half of
    /// the scene does.
    location: f32,
}

impl ViewSignature {
    /// Downsample a packed RGB888 frame into the default grid.
    pub fn from_rgb888(rgb: &[u8], width: u32, height: u32) -> Result<Self, ViewError> {
        Self::from_rgb888_with_grid(rgb, width, height, DEFAULT_VIEW_COLS, DEFAULT_VIEW_ROWS)
    }

    /// Downsample into an explicit grid, used by tests and by callers that want
    /// a cheaper or finer comparison.
    pub fn from_rgb888_with_grid(
        rgb: &[u8],
        width: u32,
        height: u32,
        cols: u32,
        rows: u32,
    ) -> Result<Self, ViewError> {
        if width == 0 || height == 0 {
            return Err(ViewError::EmptyFrame);
        }
        if cols == 0 || rows == 0 {
            return Err(ViewError::EmptyGrid);
        }

        let expected = (width as usize)
            .saturating_mul(height as usize)
            .saturating_mul(3);
        if rgb.len() != expected {
            return Err(ViewError::BufferLength {
                width,
                height,
                len: rgb.len(),
                expected,
            });
        }

        let width_usize = width as usize;
        let height_usize = height as usize;
        let cols_usize = cols as usize;
        let rows_usize = rows as usize;
        let axis = SAMPLES_PER_AXIS as usize;
        let sample_count = (SAMPLES_PER_AXIS * SAMPLES_PER_AXIS) as f32;

        let mut cells = Vec::with_capacity(cols_usize.saturating_mul(rows_usize));
        for row in 0..rows {
            let y0 = (u64::from(row) * u64::from(height) / u64::from(rows)) as usize;
            let y1 = cell_end(y0, row + 1, height, rows, height_usize);
            for col in 0..cols {
                let x0 = (u64::from(col) * u64::from(width) / u64::from(cols)) as usize;
                let x1 = cell_end(x0, col + 1, width, cols, width_usize);

                let mut sum = 0.0_f32;
                for sample_y in 0..axis {
                    // Sample the centre of each sub-block, so a cell is not
                    // biased by its own one-pixel border.
                    let y = y0 + (y1 - y0) * (2 * sample_y + 1) / (2 * axis);
                    for sample_x in 0..axis {
                        let x = x0 + (x1 - x0) * (2 * sample_x + 1) / (2 * axis);
                        let offset = (y * width_usize + x) * 3;
                        sum += 0.299 * f32::from(rgb[offset])
                            + 0.587 * f32::from(rgb[offset + 1])
                            + 0.114 * f32::from(rgb[offset + 2]);
                    }
                }
                cells.push(sum / (sample_count * 255.0));
            }
        }

        let location = median(&cells);
        Ok(Self {
            cols,
            rows,
            cells,
            location,
        })
    }

    /// Columns in this grid.
    pub fn cols(&self) -> u32 {
        self.cols
    }

    /// Rows in this grid.
    pub fn rows(&self) -> u32 {
        self.rows
    }

    /// Cell luminance in row-major order, normalised to `0.0..=1.0`.
    pub fn cells(&self) -> &[f32] {
        &self.cells
    }

    /// Number of cells in the grid.
    pub fn len(&self) -> usize {
        self.cells.len()
    }

    /// Whether the grid holds no cells.
    pub fn is_empty(&self) -> bool {
        self.cells.is_empty()
    }

    /// Median luminance the comparison is centred on.
    pub fn location(&self) -> f32 {
        self.location
    }

    /// Move this signature a fraction `weight` towards `current`.
    ///
    /// Used to let the baseline follow slow, harmless changes: the sun moving
    /// across a wall, a lamp warming up, a door left open. A pan is not slow, so
    /// it still changes most cells in one frame and is caught before the blend
    /// can hide it. `weight` is clamped to `0.0..=1.0`; `0.0` leaves the
    /// baseline untouched.
    pub fn blend_towards(&mut self, current: &Self, weight: f32) {
        let weight = weight.clamp(0.0, 1.0);
        if weight == 0.0 {
            return;
        }
        if self.cols != current.cols
            || self.rows != current.rows
            || self.cells.len() != current.cells.len()
        {
            // A different grid is not a slow drift of the same scene; adopting
            // it is the only comparison that stays well defined.
            *self = current.clone();
            return;
        }
        for (baseline_cell, current_cell) in self.cells.iter_mut().zip(&current.cells) {
            *baseline_cell = (1.0 - weight) * *baseline_cell + weight * *current_cell;
        }
        // `location` is derived state, so it must be recomputed rather than
        // blended: averaging two medians is not the median of the average.
        self.location = median(&self.cells);
    }
}

/// End of one cell along an axis, guaranteed to advance by at least one pixel.
///
/// A grid finer than the frame (only reachable in tests) would otherwise
/// produce an empty block and a division by zero in the inner loop.
fn cell_end(start: usize, index: u32, size: u32, cells: u32, limit: usize) -> usize {
    let end = (u64::from(index) * u64::from(size) / u64::from(cells)) as usize;
    if end <= start {
        (start + 1).min(limit)
    } else {
        end
    }
}

/// Robust location of a cell grid.
///
/// The median ignores a large object in front of the lens: until somebody covers
/// half of the frame, the centre value stays where the background is, so the
/// comparison cannot mistake "someone walked in" for "the camera moved".
fn median(cells: &[f32]) -> f32 {
    if cells.is_empty() {
        return 0.0;
    }
    let mut sorted = cells.to_vec();
    sorted.sort_unstable_by(f32::total_cmp);
    sorted[sorted.len() / 2]
}

/// Fraction of cells whose structure differs between two signatures.
///
/// Each signature is centred on its median first, so the same room under
/// different exposure is not a view change (acceptance A10) and a person
/// covering a quarter of the frame cannot shift the whole comparison. A cell
/// counts as changed when its centred luminance moved more than
/// `cell_threshold`, which is in luminance units (`0.0..=1.0`).
///
/// A grid mismatch reports `1.0`: comparing two grids of different shapes is
/// not evidence that the view is unchanged, so the caller must treat it as
/// changed instead of trusting a map it could not verify.
pub fn changed_fraction(
    baseline: &ViewSignature,
    current: &ViewSignature,
    cell_threshold: f32,
) -> f32 {
    if baseline.cols != current.cols
        || baseline.rows != current.rows
        || baseline.cells.is_empty()
        || baseline.cells.len() != current.cells.len()
    {
        return 1.0;
    }

    let changed = baseline
        .cells
        .iter()
        .zip(&current.cells)
        .filter(|&(baseline_cell, current_cell)| {
            let base = baseline_cell - baseline.location;
            let current = current_cell - current.location;
            (current - base).abs() > cell_threshold
        })
        .count();

    changed as f32 / baseline.cells.len() as f32
}

/// Thresholds that decide when the camera counts as moved.
///
/// The two fractions implement hysteresis: the view is flagged at
/// [`Self::enter_fraction`] and only released at the lower
/// [`Self::exit_fraction`], so a scene near the boundary cannot flap between
/// `unknown` and a status reading on every analysed frame.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ViewChangeConfig {
    /// Per-cell luminance change, in `0.0..=1.0`, that counts as "this part of
    /// the scene moved". Below this, sensor noise and soft shadows are ignored.
    pub cell_threshold: f32,
    /// Changed-cell fraction that flags the view as moved.
    pub enter_fraction: f32,
    /// Changed-cell fraction at or below which the view is trusted again.
    pub exit_fraction: f32,
    /// Consecutive frames above [`Self::enter_fraction`] before the view is
    /// declared moved.
    ///
    /// A single bright frame - someone switching on a lamp, a door swinging
    /// open, a car headlight sweeping the room - must not freeze every table.
    /// Three frames at the analysed rate is about 1.5 s, short enough that a
    /// real pan is still reported before the user reads a status.
    pub enter_frames: u32,
    /// Consecutive frames at or below [`Self::exit_fraction`] before the view
    /// is trusted again.
    pub exit_frames: u32,
    /// Weight of the newest frame in the adaptive baseline, `0.0..=1.0`.
    ///
    /// Applied only while the view is trusted *and* the frame itself is below
    /// [`Self::enter_fraction`]. `0.0` disables adaptation and pins the baseline
    /// to the frame the map was drawn on.
    pub baseline_adapt: f32,
}

impl Default for ViewChangeConfig {
    /// Thresholds tuned for a fixed CCTV view of a warung.
    ///
    /// A pan or tilt moves the whole scene, so it changes far more than half of
    /// the grid. A standing person close to the camera is the largest legitimate
    /// change and covers roughly a quarter of the frame, which must stay below
    /// [`Self::enter_fraction`] or occupancy would freeze every time somebody
    /// stepped in front of the lens.
    fn default() -> Self {
        Self {
            cell_threshold: 0.06,
            enter_fraction: 0.50,
            exit_fraction: 0.25,
            enter_frames: 3,
            exit_frames: 2,
            baseline_adapt: 0.05,
        }
    }
}

/// One frame's verdict about the camera view.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ViewVerdict {
    /// Fraction of the grid that differs from the baseline.
    pub changed_fraction: f32,
    /// The camera no longer matches the view the map was drawn for.
    pub moved: bool,
    /// `moved` flipped to true on this frame.
    pub became_moved: bool,
    /// `moved` flipped back to false on this frame.
    pub recovered: bool,
}

impl Default for ViewVerdict {
    /// The verdict before any frame was compared: nothing is known yet, and
    /// treating "unknown" as "moved" would freeze a map that was just installed.
    fn default() -> Self {
        Self {
            changed_fraction: 0.0,
            moved: false,
            became_moved: false,
            recovered: false,
        }
    }
}

/// Compares analysed frames against the view a table map was drawn for.
///
/// The baseline is the first signature observed after a map is installed, which
/// is the frame those polygons were drawn against. While the view is trusted it
/// is blended towards each new frame by [`ViewChangeConfig::baseline_adapt`], so
/// slow, harmless change (a sunlit wall drifting into shade) does not accumulate
/// into a false "camera moved". It is frozen the moment the view is flagged, and
/// [`ViewGuard::reset`] still rebases it when a new map is installed, so a camera
/// that leaves and returns is judged against the original view.
#[derive(Debug, Clone)]
pub struct ViewGuard {
    config: ViewChangeConfig,
    baseline: Option<ViewSignature>,
    moved: bool,
    enter_streak: u32,
    exit_streak: u32,
}

impl Default for ViewGuard {
    fn default() -> Self {
        Self::new(ViewChangeConfig::default())
    }
}

impl ViewGuard {
    /// Build a guard with explicit thresholds.
    pub fn new(config: ViewChangeConfig) -> Self {
        Self {
            config,
            baseline: None,
            moved: false,
            enter_streak: 0,
            exit_streak: 0,
        }
    }

    /// Thresholds in use.
    pub fn config(&self) -> ViewChangeConfig {
        self.config
    }

    /// Baseline signature, if a frame has been observed.
    pub fn baseline(&self) -> Option<&ViewSignature> {
        self.baseline.as_ref()
    }

    /// Whether the last observed frame still matches the baseline.
    pub fn is_moved(&self) -> bool {
        self.moved
    }

    /// Forget the baseline and the verdict.
    ///
    /// Called when a new table map is installed: the next frame then defines the
    /// view that map belongs to.
    pub fn reset(&mut self) {
        self.baseline = None;
        self.moved = false;
        self.enter_streak = 0;
        self.exit_streak = 0;
    }

    /// Feed one frame signature and report the resulting verdict.
    ///
    /// The first call captures the baseline and never reports a change: there is
    /// nothing to compare against yet. Later calls need a run of agreeing frames
    /// to flip the verdict in either direction, so one bright frame cannot
    /// freeze the map and one matching frame cannot un-freeze it.
    pub fn observe(&mut self, signature: &ViewSignature) -> ViewVerdict {
        let Some(baseline) = self.baseline.as_ref() else {
            self.baseline = Some(signature.clone());
            return ViewVerdict::default();
        };

        let fraction = changed_fraction(baseline, signature, self.config.cell_threshold);
        let was_moved = self.moved;
        if std::env::var("CCTV_VIEW_TRACE").is_ok() {
            eprintln!(
                "observe fraction={fraction:.4} moved_before={was_moved} enter={} exit={}",
                self.enter_streak, self.exit_streak
            );
        }

        if self.moved {
            self.exit_streak = if fraction <= self.config.exit_fraction {
                self.exit_streak.saturating_add(1)
            } else {
                0
            };
            if self.exit_streak >= self.config.exit_frames.max(1) {
                self.moved = false;
                self.exit_streak = 0;
            }
        } else {
            self.enter_streak = if fraction >= self.config.enter_fraction {
                self.enter_streak.saturating_add(1)
            } else {
                0
            };
            if self.enter_streak >= self.config.enter_frames.max(1) {
                self.moved = true;
                self.enter_streak = 0;
            }
        }

        // Absorb slow drift, but never from a frame that already looks like a
        // pan. Blending during an enter streak would fold part of the new scene
        // into the baseline before the streak completes, which both hides the
        // pan and can leave the view stuck above the exit fraction forever.
        if !self.moved && fraction < self.config.enter_fraction {
            if let Some(baseline) = self.baseline.as_mut() {
                baseline.blend_towards(signature, self.config.baseline_adapt);
            }
        }

        ViewVerdict {
            changed_fraction: fraction,
            moved: self.moved,
            became_moved: self.moved && !was_moved,
            recovered: !self.moved && was_moved,
        }
    }
}
