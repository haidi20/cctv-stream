//! Point-in-polygon edge cases that decide table status.

use spatial_engine::{Point, Polygon};

fn rectangle() -> Polygon {
    Polygon::new(vec![
        Point::new(0.2, 0.2),
        Point::new(0.4, 0.2),
        Point::new(0.4, 0.4),
        Point::new(0.2, 0.4),
    ])
    .unwrap()
}

#[test]
fn point_in_the_middle_is_inside() {
    assert!(rectangle().contains(Point::new(0.3, 0.3)));
}

#[test]
fn point_outside_is_outside() {
    assert!(!rectangle().contains(Point::new(0.5, 0.3)));
    assert!(!rectangle().contains(Point::new(0.3, 0.5)));
    assert!(!rectangle().contains(Point::new(0.1, 0.1)));
}

#[test]
fn point_on_each_edge_is_inside() {
    let polygon = rectangle();
    assert!(polygon.contains(Point::new(0.3, 0.2)), "top edge");
    assert!(polygon.contains(Point::new(0.3, 0.4)), "bottom edge");
    assert!(polygon.contains(Point::new(0.2, 0.3)), "left edge");
    assert!(polygon.contains(Point::new(0.4, 0.3)), "right edge");
}

#[test]
fn each_vertex_counts_as_inside() {
    let polygon = rectangle();
    for point in [
        Point::new(0.2, 0.2),
        Point::new(0.4, 0.2),
        Point::new(0.4, 0.4),
        Point::new(0.2, 0.4),
    ] {
        assert!(polygon.contains(point), "vertex {point:?} must be inside");
    }
}

#[test]
fn concave_notch_is_excluded() {
    let polygon = Polygon::new(vec![
        Point::new(0.1, 0.1),
        Point::new(0.5, 0.1),
        Point::new(0.5, 0.5),
        Point::new(0.4, 0.5),
        Point::new(0.4, 0.2),
        Point::new(0.1, 0.2),
    ])
    .unwrap();

    assert!(polygon.contains(Point::new(0.2, 0.15)), "inside the L");
    assert!(
        polygon.contains(Point::new(0.45, 0.45)),
        "inside the notch arm"
    );
    assert!(!polygon.contains(Point::new(0.2, 0.35)), "below the arm");
}

#[test]
fn triangle_at_the_frame_corner_is_valid() {
    let polygon = Polygon::new(vec![
        Point::new(0.0, 0.0),
        Point::new(0.2, 0.0),
        Point::new(0.0, 0.2),
    ])
    .unwrap();

    assert!(polygon.contains(Point::new(0.05, 0.05)));
    assert!(!polygon.contains(Point::new(0.15, 0.15)));
}

#[test]
fn area_matches_the_rectangle() {
    assert!((rectangle().area() - 0.04).abs() < 1e-9);
}

#[test]
fn polygon_is_rejected_when_it_is_only_a_line() {
    assert!(Polygon::new(vec![
        Point::new(0.1, 0.1),
        Point::new(0.5, 0.1),
        Point::new(0.9, 0.1),
    ])
    .is_none());
}
