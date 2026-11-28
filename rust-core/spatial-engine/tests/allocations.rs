//! Per-frame allocation guard for the assignment hot path.
//!
//! `SpatialEngine::update_normalized_with_totals` runs once per analysed frame
//! for hours. Cloning every footprint into a fresh `Vec` on each call was a
//! silent allocation per table per frame; this test counts real allocations
//! with a global allocator, so the clone cannot come back unnoticed.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use spatial_engine::{DebounceConfig, NormalizedDetection, Point, SpatialEngine, TableConfigSet};

/// Counts every allocation the test binary makes.
struct CountingAllocator;

static ALLOCATIONS: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) }
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

const SIX_TABLES: &str = r#"{
    "version": 2,
    "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
    "tables": [
        { "id": "t1", "polygon": [
            { "x": 0.02, "y": 0.55 }, { "x": 0.16, "y": 0.55 },
            { "x": 0.16, "y": 0.90 }, { "x": 0.02, "y": 0.90 } ] },
        { "id": "t2", "polygon": [
            { "x": 0.18, "y": 0.55 }, { "x": 0.32, "y": 0.55 },
            { "x": 0.32, "y": 0.90 }, { "x": 0.18, "y": 0.90 } ] },
        { "id": "t3", "polygon": [
            { "x": 0.34, "y": 0.55 }, { "x": 0.48, "y": 0.55 },
            { "x": 0.48, "y": 0.90 }, { "x": 0.34, "y": 0.90 } ] },
        { "id": "t4", "polygon": [
            { "x": 0.52, "y": 0.55 }, { "x": 0.66, "y": 0.55 },
            { "x": 0.66, "y": 0.90 }, { "x": 0.52, "y": 0.90 } ] },
        { "id": "t5", "polygon": [
            { "x": 0.68, "y": 0.55 }, { "x": 0.82, "y": 0.55 },
            { "x": 0.82, "y": 0.90 }, { "x": 0.68, "y": 0.90 } ] },
        { "id": "t6", "polygon": [
            { "x": 0.84, "y": 0.55 }, { "x": 0.98, "y": 0.55 },
            { "x": 0.98, "y": 0.90 }, { "x": 0.84, "y": 0.90 } ] }
    ]
}"#;

#[test]
fn a_steady_state_frame_allocates_only_the_totals_vector() {
    let config = TableConfigSet::from_json(SIX_TABLES).unwrap();
    let mut engine = SpatialEngine::new(
        config,
        DebounceConfig::new(std::time::Duration::ZERO),
        std::time::Duration::ZERO,
    );

    // Six people, one per table. Build the input once so the measurement
    // covers the engine call and not the test's own setup.
    let detections: Vec<NormalizedDetection> = (0..6)
        .map(|index| NormalizedDetection {
            centroid: Point::new(0.09 + index as f64 * 0.16, 0.72),
            confidence: 0.9,
        })
        .collect();

    // Warm up: the first call may touch lazily initialised internals.
    engine.update_normalized_with_totals(&detections, std::time::Duration::ZERO);

    let before = ALLOCATIONS.load(Ordering::Relaxed);
    let (_, totals) = engine.update_normalized_with_totals(&detections, std::time::Duration::ZERO);
    let after = ALLOCATIONS.load(Ordering::Relaxed);

    assert_eq!(totals.iter().map(|table| table.persons).sum::<usize>(), 6);
    assert_eq!(
        after - before,
        1,
        "expected exactly one allocation (the totals vector); cloning footprints per frame \
         would allocate one more Vec per table"
    );
}
