//! Letterbox resize and normalisation into the tensor layout YOLO expects.
//!
//! The model sees a square `size x size` RGB tensor in `NCHW` order,
//! normalised to `0..=1`. Letterboxing preserves the source aspect ratio and
//! records the padding so detections can be mapped back to frame pixels.

use crate::error::AiError;

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
    if size == 0 {
        return Err(AiError::Config {
            reason: "model input size must be non-zero".to_string(),
        });
    }

    let source_w = frame.width as f32;
    let source_h = frame.height as f32;
    let target = size as f32;
    let scale = (target / source_w).min(target / source_h);
    let scaled_w = ((source_w * scale).round() as u32).clamp(1, size);
    let scaled_h = ((source_h * scale).round() as u32).clamp(1, size);
    let pad_x = (size - scaled_w) as f32 / 2.0;
    let pad_y = (size - scaled_h) as f32 / 2.0;

    let plane =
        usize::try_from(u64::from(size) * u64::from(size)).map_err(|_| AiError::Config {
            reason: "model input size is too large".to_string(),
        })?;
    let tensor_len = plane.checked_mul(3).ok_or_else(|| AiError::Config {
        reason: "model input size is too large".to_string(),
    })?;
    let mut tensor = vec![114.0f32 / 255.0; tensor_len];
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

    Ok(Preprocessed {
        tensor,
        letterbox: Letterbox {
            scale,
            pad_x,
            pad_y,
            size,
        },
    })
}

fn pixel(frame: RgbFrame<'_>, x: u32, y: u32, channel: usize) -> f32 {
    let index = ((y as usize * frame.width as usize) + x as usize) * 3 + channel;
    f32::from(frame.data[index])
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
}
