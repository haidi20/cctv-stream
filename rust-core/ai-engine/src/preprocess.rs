//! Letterbox resize and normalisation into the tensor layout YOLO expects.
//!
//! The model sees a square `size x size` RGB tensor in `NCHW` order,
//! normalised to `0..=1`. Letterboxing preserves the source aspect ratio and
//! records the padding so detections can be mapped back to frame pixels.
//!
//! A monitoring session preprocesses one frame every few hundred milliseconds
//! for hours. [`PreprocessScratch`] keeps the tensor and the per-axis sampling
//! tables between calls, so steady-state frames no longer allocate several
//! megabytes or recompute every source coordinate. [`preprocess`] stays as the
//! one-shot convenience used by tests and callers that analyse a single frame.

use crate::error::AiError;

/// Letterbox padding (114/255) as a normalised model input value.
const PAD_VALUE: f32 = 114.0 / 255.0;

/// Input frame in packed RGB8 (`width * height * 3` bytes).
#[derive(Debug, Clone, Copy)]
pub struct RgbFrame<'a> {
    /// Pixel width.
    pub width: u32,
    /// Pixel height.
    pub height: u32,
    /// Packed RGB bytes, row-major.
    pub data: &'a [u8],
}

impl<'a> RgbFrame<'a> {
    /// Validate that `data` matches the declared size.
    pub fn new(width: u32, height: u32, data: &'a [u8]) -> Result<Self, AiError> {
        if width == 0 || height == 0 {
            return Err(AiError::InvalidFrame {
                reason: "width and height must be non-zero".to_string(),
            });
        }
        let expected = u64::from(width)
            .checked_mul(u64::from(height))
            .and_then(|pixels| pixels.checked_mul(3))
            .and_then(|bytes| usize::try_from(bytes).ok())
            .ok_or_else(|| AiError::InvalidFrame {
                reason: "frame is too large".to_string(),
            })?;
        if data.len() != expected {
            return Err(AiError::InvalidFrame {
                reason: format!("expected {expected} RGB bytes, got {}", data.len()),
            });
        }
        Ok(Self {
            width,
            height,
            data,
        })
    }
}

/// How the source frame was placed inside the model input.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Letterbox {
    /// Uniform scale applied to both axes.
    pub scale: f32,
    /// Left padding in model pixels.
    pub pad_x: f32,
    /// Top padding in model pixels.
    pub pad_y: f32,
    /// Model input side length.
    pub size: u32,
}

impl Letterbox {
    /// Map a model-space point back to source-frame pixels.
    pub fn to_source(&self, x: f32, y: f32) -> (f32, f32) {
        ((x - self.pad_x) / self.scale, (y - self.pad_y) / self.scale)
    }
}

/// Geometry that decides whether the cached sampling tables stay valid.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct MapKey {
    size: u32,
    width: u32,
    height: u32,
}

/// One source coordinate pair plus the bilinear weight for `index1`.
#[derive(Debug, Clone, Copy)]
struct Sample {
    index0: u32,
    index1: u32,
    weight: f32,
}

/// Cached letterbox geometry and sampling tables for one frame shape.
#[derive(Debug)]
struct AxisMap {
    key: MapKey,
    letterbox: Letterbox,
    plane: usize,
    tensor_len: usize,
    pad_x_px: usize,
    pad_y_px: usize,
    x: Vec<Sample>,
    y: Vec<Sample>,
}

impl AxisMap {
    fn build(key: MapKey) -> Result<Self, AiError> {
        let source_w = key.width as f32;
        let source_h = key.height as f32;
        let target = key.size as f32;
        let scale = (target / source_w).min(target / source_h);
        let scaled_w = ((source_w * scale).round() as u32).clamp(1, key.size);
        let scaled_h = ((source_h * scale).round() as u32).clamp(1, key.size);
        let pad_x = (key.size - scaled_w) as f32 / 2.0;
        let pad_y = (key.size - scaled_h) as f32 / 2.0;

        let plane = usize::try_from(u64::from(key.size) * u64::from(key.size)).map_err(|_| {
            AiError::Config {
                reason: "model input size is too large".to_string(),
            }
        })?;
        let tensor_len = plane.checked_mul(3).ok_or_else(|| AiError::Config {
            reason: "model input size is too large".to_string(),
        })?;

        Ok(Self {
            key,
            letterbox: Letterbox {
                scale,
                pad_x,
                pad_y,
                size: key.size,
            },
            plane,
            tensor_len,
            pad_x_px: pad_x.round() as usize,
            pad_y_px: pad_y.round() as usize,
            x: axis_samples(scaled_w as usize, key.width, scale),
            y: axis_samples(scaled_h as usize, key.height, scale),
        })
    }
}

/// Bilinear source coordinates for every destination pixel on one axis.
fn axis_samples(dst_len: usize, src_len: u32, scale: f32) -> Vec<Sample> {
    let mut table = Vec::with_capacity(dst_len);
    for dst in 0..dst_len {
        let source = ((dst as f32 + 0.5) / scale - 0.5).max(0.0);
        let index0 = (source.floor() as u32).min(src_len - 1);
        let index1 = (index0 + 1).min(src_len - 1);
        table.push(Sample {
            index0,
            index1,
            weight: source - index0 as f32,
        });
    }
    table
}

/// Reusable buffers for preprocessing a stream of same-shaped frames.
///
/// The tensor and sampling tables are kept between calls; only the pixel
/// blending runs again. Create one per inference worker.
#[derive(Debug, Default)]
pub struct PreprocessScratch {
    tensor: Vec<f32>,
    map: Option<AxisMap>,
}

impl PreprocessScratch {
    /// Empty scratch; buffers grow on the first call.
    pub fn new() -> Self {
        Self::default()
    }

    /// Resize, letterbox, and normalise `frame` into the cached tensor.
    ///
    /// Returns the [`Letterbox`] needed to map detections back to source
    /// pixels. The tensor is reset to the padding value on every call, so
    /// stale frame content never leaks into the padded border.
    pub fn preprocess_into(
        &mut self,
        frame: RgbFrame<'_>,
        size: u32,
    ) -> Result<Letterbox, AiError> {
        if size == 0 {
            return Err(AiError::Config {
                reason: "model input size must be non-zero".to_string(),
            });
        }

        let key = MapKey {
            size,
            width: frame.width,
            height: frame.height,
        };
        let rebuild = self.map.as_ref().is_none_or(|map| map.key != key);
        if rebuild {
            let map = AxisMap::build(key)?;
            if self.tensor.len() != map.tensor_len {
                self.tensor = vec![PAD_VALUE; map.tensor_len];
            } else {
                self.tensor.fill(PAD_VALUE);
            }
            self.map = Some(map);
        } else {
            self.tensor.fill(PAD_VALUE);
        }

        let map = self.map.as_ref().expect("axis map exists after a rebuild");
        let size_usize = size as usize;
        let stride = frame.width as usize;

        for (dst_y, sample_y) in map.y.iter().enumerate() {
            let row0 = sample_y.index0 as usize * stride;
            let row1 = sample_y.index1 as usize * stride;
            let out_y = (map.pad_y_px + dst_y).min(size_usize - 1);
            let row_out = out_y * size_usize;

            for (dst_x, sample_x) in map.x.iter().enumerate() {
                let left = sample_x.index0 as usize;
                let right = sample_x.index1 as usize;
                let i00 = (row0 + left) * 3;
                let i10 = (row0 + right) * 3;
                let i01 = (row1 + left) * 3;
                let i11 = (row1 + right) * 3;
                let out_x = (map.pad_x_px + dst_x).min(size_usize - 1);
                let index = row_out + out_x;

                for channel in 0..3 {
                    let p00 = f32::from(frame.data[i00 + channel]);
                    let p10 = f32::from(frame.data[i10 + channel]);
                    let p01 = f32::from(frame.data[i01 + channel]);
                    let p11 = f32::from(frame.data[i11 + channel]);
                    let top = p00 + (p10 - p00) * sample_x.weight;
                    let bottom = p01 + (p11 - p01) * sample_x.weight;
                    self.tensor[channel * map.plane + index] =
                        (top + (bottom - top) * sample_y.weight) / 255.0;
                }
            }
        }

        Ok(map.letterbox)
    }

    /// Latest normalised `NCHW` tensor, length `3 * size * size`.
    pub fn tensor(&self) -> &[f32] {
        &self.tensor
    }

    /// Consume the scratch and return the tensor buffer.
    pub fn into_tensor(self) -> Vec<f32> {
        self.tensor
    }
}

/// Result of preparing one frame for inference.
#[derive(Debug, Clone, PartialEq)]
pub struct Preprocessed {
    /// `NCHW` tensor data, length `3 * size * size`.
    pub tensor: Vec<f32>,
    /// Geometry needed to invert the transform.
    pub letterbox: Letterbox,
}

/// Resize with letterbox padding and normalise into `NCHW` `f32`.
pub fn preprocess(frame: RgbFrame<'_>, size: u32) -> Result<Preprocessed, AiError> {
    let mut scratch = PreprocessScratch::new();
    let letterbox = scratch.preprocess_into(frame, size)?;
    Ok(Preprocessed {
        tensor: scratch.into_tensor(),
        letterbox,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(width: u32, height: u32, rgb: [u8; 3]) -> Vec<u8> {
        let mut data = Vec::with_capacity((width * height * 3) as usize);
        for _ in 0..width * height {
            data.extend_from_slice(&rgb);
        }
        data
    }

    fn gradient(width: u32, height: u32) -> Vec<u8> {
        (0..width * height)
            .flat_map(|pixel| {
                let x = pixel % width;
                let y = pixel / width;
                [
                    (x * 7 % 256) as u8,
                    (y * 13 % 256) as u8,
                    ((x + y) * 3 % 256) as u8,
                ]
            })
            .collect()
    }

    /// Straight port of the original per-pixel loop, kept as an oracle.
    fn reference(frame: RgbFrame<'_>, size: u32) -> Preprocessed {
        let source_w = frame.width as f32;
        let source_h = frame.height as f32;
        let target = size as f32;
        let scale = (target / source_w).min(target / source_h);
        let scaled_w = ((source_w * scale).round() as u32).clamp(1, size);
        let scaled_h = ((source_h * scale).round() as u32).clamp(1, size);
        let pad_x = (size - scaled_w) as f32 / 2.0;
        let pad_y = (size - scaled_h) as f32 / 2.0;
        let plane = size as usize * size as usize;
        let mut tensor = vec![114.0f32 / 255.0; plane * 3];
        let pad_x_px = pad_x.round() as usize;
        let pad_y_px = pad_y.round() as usize;

        for dst_y in 0..scaled_h as usize {
            let src_y = ((dst_y as f32 + 0.5) / scale - 0.5).max(0.0);
            let y0 = (src_y.floor() as u32).min(frame.height - 1);
            let y1 = (y0 + 1).min(frame.height - 1);
            let wy = src_y - y0 as f32;
            for dst_x in 0..scaled_w as usize {
                let src_x = ((dst_x as f32 + 0.5) / scale - 0.5).max(0.0);
                let x0 = (src_x.floor() as u32).min(frame.width - 1);
                let x1 = (x0 + 1).min(frame.width - 1);
                let wx = src_x - x0 as f32;
                let mut rgb = [0.0f32; 3];
                for (channel, value) in rgb.iter_mut().enumerate() {
                    let p00 = pixel(frame, x0, y0, channel);
                    let p10 = pixel(frame, x1, y0, channel);
                    let p01 = pixel(frame, x0, y1, channel);
                    let p11 = pixel(frame, x1, y1, channel);
                    let top = p00 + (p10 - p00) * wx;
                    let bottom = p01 + (p11 - p01) * wx;
                    *value = (top + (bottom - top) * wy) / 255.0;
                }
                let out_x = (pad_x_px + dst_x).min(size as usize - 1);
                let out_y = (pad_y_px + dst_y).min(size as usize - 1);
                let index = out_y * size as usize + out_x;
                tensor[index] = rgb[0];
                tensor[plane + index] = rgb[1];
                tensor[plane * 2 + index] = rgb[2];
            }
        }

        Preprocessed {
            tensor,
            letterbox: Letterbox {
                scale,
                pad_x,
                pad_y,
                size,
            },
        }
    }

    fn pixel(frame: RgbFrame<'_>, x: u32, y: u32, channel: usize) -> f32 {
        let index = ((y as usize * frame.width as usize) + x as usize) * 3 + channel;
        f32::from(frame.data[index])
    }

    #[test]
    fn rejects_payload_that_does_not_match_dimensions() {
        let error = RgbFrame::new(2, 2, &[0; 11]).unwrap_err();
        assert!(matches!(error, AiError::InvalidFrame { .. }));
    }

    #[test]
    fn rejects_dimensions_whose_byte_count_overflows() {
        let error = RgbFrame::new(u32::MAX, u32::MAX, &[]).unwrap_err();
        assert!(matches!(error, AiError::InvalidFrame { .. }));
    }

    #[test]
    fn rejects_model_size_whose_tensor_length_overflows() {
        let data = solid(1, 1, [0, 0, 0]);
        let frame = RgbFrame::new(1, 1, &data).unwrap();
        let error = preprocess(frame, u32::MAX).unwrap_err();
        assert!(matches!(error, AiError::Config { .. }));
    }

    #[test]
    fn letterbox_pads_a_wide_frame_vertically() {
        let data = solid(8, 4, [255, 0, 0]);
        let frame = RgbFrame::new(8, 4, &data).unwrap();
        let result = preprocess(frame, 8).unwrap();

        assert_eq!(result.tensor.len(), 3 * 64);
        assert!((result.letterbox.scale - 1.0).abs() < 1e-6);
        assert!((result.letterbox.pad_y - 2.0).abs() < 1e-6);
        assert!(result.letterbox.pad_x.abs() < 1e-6);
        assert!((result.tensor[0] - 114.0 / 255.0).abs() < 1e-6);
        assert!((result.tensor[3 * 8] - 1.0).abs() < 1e-6);
    }

    #[test]
    fn values_are_normalised_to_unit_range() {
        let data = solid(4, 4, [255, 255, 255]);
        let frame = RgbFrame::new(4, 4, &data).unwrap();
        let result = preprocess(frame, 4).unwrap();
        assert!(result.tensor.iter().all(|v| (0.0..=1.0).contains(v)));
        assert!(result.tensor.iter().all(|v| (*v - 1.0).abs() < 1e-6));
    }

    #[test]
    fn inverse_mapping_removes_padding_and_scale() {
        let letterbox = Letterbox {
            scale: 2.0,
            pad_x: 10.0,
            pad_y: 4.0,
            size: 64,
        };
        let (x, y) = letterbox.to_source(30.0, 24.0);
        assert!((x - 10.0).abs() < 1e-6);
        assert!((y - 10.0).abs() < 1e-6);
    }

    #[test]
    fn scratch_matches_reference_for_odd_aspect_frames() {
        let cases = [(97u32, 61u32, 64u32), (640, 360, 320), (168, 168, 96)];
        for (width, height, size) in cases {
            let data = gradient(width, height);
            let frame = RgbFrame::new(width, height, &data).unwrap();
            let mut scratch = PreprocessScratch::new();
            let letterbox = scratch.preprocess_into(frame, size).unwrap();
            let expected = reference(frame, size);
            assert_eq!(letterbox, expected.letterbox, "{width}x{height}@{size}");
            assert_eq!(
                scratch.tensor(),
                expected.tensor.as_slice(),
                "{width}x{height}@{size}"
            );
        }
    }

    #[test]
    fn scratch_reuses_the_tensor_allocation_across_frames() {
        let data = gradient(64, 48);
        let frame = RgbFrame::new(64, 48, &data).unwrap();
        let mut scratch = PreprocessScratch::new();
        scratch.preprocess_into(frame, 64).unwrap();
        let first = scratch.tensor().as_ptr();
        let first_len = scratch.tensor().len();
        scratch.preprocess_into(frame, 64).unwrap();
        assert_eq!(scratch.tensor().as_ptr(), first);
        assert_eq!(scratch.tensor().len(), first_len);
    }

    #[test]
    fn scratch_drops_stale_padding_when_the_aspect_ratio_changes() {
        let wide = solid(8, 4, [255, 0, 0]);
        let tall = solid(4, 8, [0, 0, 255]);
        let mut scratch = PreprocessScratch::new();
        scratch
            .preprocess_into(RgbFrame::new(8, 4, &wide).unwrap(), 8)
            .unwrap();
        let size = 8usize;
        let stale_cell = 3 * size;
        assert!((scratch.tensor()[stale_cell] - 1.0).abs() < 1e-6);

        scratch
            .preprocess_into(RgbFrame::new(4, 8, &tall).unwrap(), 8)
            .unwrap();
        assert!((scratch.tensor()[stale_cell] - PAD_VALUE).abs() < 1e-6);
    }

    #[test]
    fn scratch_rejects_zero_and_overflowing_sizes() {
        let data = solid(1, 1, [0, 0, 0]);
        let frame = RgbFrame::new(1, 1, &data).unwrap();
        let mut scratch = PreprocessScratch::new();
        let error = scratch.preprocess_into(frame, 0).unwrap_err();
        assert!(matches!(error, AiError::Config { .. }));
        let error = scratch.preprocess_into(frame, u32::MAX).unwrap_err();
        assert!(matches!(error, AiError::Config { .. }));
    }
}
