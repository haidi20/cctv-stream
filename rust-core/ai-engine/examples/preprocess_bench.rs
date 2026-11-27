//! Host-side preprocessing benchmark, independent of ONNX Runtime.
//!
//! Run with `cargo run --release -p ai-engine --example preprocess_bench`.
//! It reports per-frame cost for the two model inputs the project cares about.

use std::hint::black_box;
use std::time::Instant;

use ai_engine::preprocess::RgbFrame;

const FRAMES: usize = 2_000;

fn measure(width: u32, height: u32, size: u32) -> (f64, f64) {
    let pixels: Vec<u8> = (0..width as usize * height as usize * 3)
        .map(|index| (index % 251) as u8)
        .collect();
    let mut scratch = ai_engine::preprocess::PreprocessScratch::new();
    let mut samples = Vec::with_capacity(FRAMES);

    for _ in 0..FRAMES {
        let frame = RgbFrame::new(width, height, &pixels).expect("valid frame");
        let started = Instant::now();
        let letterbox = scratch.preprocess_into(frame, size).expect("valid size");
        let tensor = black_box(scratch.tensor());
        samples.push(started.elapsed().as_secs_f64() * 1_000.0);
        black_box((letterbox, tensor));
    }

    samples.sort_by(f64::total_cmp);
    let middle = samples.len() / 2;
    let p50 = samples[middle];
    let p95 = samples[samples
        .len()
        .saturating_sub(1)
        .saturating_sub(samples.len() / 20)];
    (p50, p95)
}

fn main() {
    for (width, height, size) in [(640, 360, 640), (640, 360, 320), (1920, 1080, 640)] {
        let (p50, p95) = measure(width, height, size);
        println!("{width}x{height} -> {size}: p50 {p50:.3} ms, p95 {p95:.3} ms ({FRAMES} frames)");
    }
}
