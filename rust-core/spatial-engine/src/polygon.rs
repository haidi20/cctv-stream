//! Point-in-polygon tests in normalised coordinates.

use serde::{Deserialize, Serialize};

/// A point in normalised frame space, `0..=1` on both axes.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Point {
    /// Horizontal fraction of the frame.
    pub x: f64,
    /// Vertical fraction of the frame.
    pub y: f64,
}

impl Point {
    /// Construct a point.
    pub fn new(x: f64, y: f64) -> Self {
        Self { x, y }
    }
}

/// Tolerance used when a point sits exactly on an edge or vertex.
pub const EDGE_TOLERANCE: f64 = 1e-6;
/// Minimum accepted polygon area, as a fraction of the frame.
pub const MIN_POLYGON_AREA: f64 = 0.0005;

/// A table footprint.
#[derive(Debug, Clone, PartialEq)]
pub struct Polygon {
    points: Vec<Point>,
    area: f64,
}

impl Polygon {
    /// Validate and build a polygon.
    ///
    /// Returns `None` when there are fewer than three points, a coordinate is
    /// not finite or outside `0..=1`, the outline self-intersects, or the area
    /// is below [`MIN_POLYGON_AREA`].
    pub fn new(points: Vec<Point>) -> Option<Self> {
        if points.len() < 3 {
            return None;
        }
        if points.iter().any(|p| {
            !p.x.is_finite()
                || !p.y.is_finite()
                || !(0.0..=1.0).contains(&p.x)
                || !(0.0..=1.0).contains(&p.y)
        }) {
            return None;
        }

        let area = signed_area(&points).abs();
        if area < MIN_POLYGON_AREA {
            return None;
        }
        if is_self_intersecting(&points) {
            return None;
        }

        Some(Self { points, area })
    }

    /// The polygon vertices.
    pub fn points(&self) -> &[Point] {
        &self.points
    }

    /// Absolute area in normalised units.
    pub fn area(&self) -> f64 {
        self.area
    }

    /// True when the point lies inside or on the boundary.
    ///
    /// Uses ray casting on the even-odd rule, then treats near-edge points as
    /// inside so a person standing on the drawn border still counts.
    pub fn contains(&self, point: Point) -> bool {
        if self.distance_to_boundary(point) <= EDGE_TOLERANCE {
            return true;
        }

        let mut inside = false;
        let count = self.points.len();
        for index in 0..count {
            let a = self.points[index];
            let b = self.points[(index + 1) % count];

            let straddles = (a.y > point.y) != (b.y > point.y);
            if !straddles {
                continue;
            }

            let t = (point.y - a.y) / (b.y - a.y);
            let x_at_y = a.x + t * (b.x - a.x);
            if point.x < x_at_y {
                inside = !inside;
            }
        }
        inside
    }

    /// Shortest distance from the point to any polygon edge.
    pub fn distance_to_boundary(&self, point: Point) -> f64 {
        let count = self.points.len();
        (0..count)
            .map(|index| {
                let a = self.points[index];
                let b = self.points[(index + 1) % count];
                distance_to_segment(point, a, b)
            })
            .fold(f64::INFINITY, f64::min)
    }

    /// Centroid of the polygon (area weighted).
    pub fn centroid(&self) -> Point {
        let count = self.points.len();
        let mut twice_area = 0.0;
        let mut cx = 0.0;
        let mut cy = 0.0;

        for index in 0..count {
            let a = self.points[index];
            let b = self.points[(index + 1) % count];
            let cross = a.x * b.y - b.x * a.y;
            twice_area += cross;
            cx += (a.x + b.x) * cross;
            cy += (a.y + b.y) * cross;
        }

        if twice_area.abs() < f64::EPSILON {
            let n = count as f64;
            let sum_x: f64 = self.points.iter().map(|p| p.x).sum();
            let sum_y: f64 = self.points.iter().map(|p| p.y).sum();
            return Point::new(sum_x / n, sum_y / n);
        }

        let factor = 1.0 / (3.0 * twice_area);
        Point::new(cx * factor, cy * factor)
    }
}

fn signed_area(points: &[Point]) -> f64 {
    let count = points.len();
    let mut sum = 0.0;
    for index in 0..count {
        let a = points[index];
        let b = points[(index + 1) % count];
        sum += a.x * b.y - b.x * a.y;
    }
    sum / 2.0
}

fn distance_to_segment(point: Point, a: Point, b: Point) -> f64 {
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    let length_squared = dx * dx + dy * dy;

    if length_squared <= f64::EPSILON {
        return ((point.x - a.x).powi(2) + (point.y - a.y).powi(2)).sqrt();
    }

    let t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / length_squared).clamp(0.0, 1.0);
    let px = a.x + t * dx;
    let py = a.y + t * dy;
    ((point.x - px).powi(2) + (point.y - py).powi(2)).sqrt()
}

fn segments_intersect(p1: Point, p2: Point, p3: Point, p4: Point) -> bool {
    let d1 = orientation(p3, p4, p1);
    let d2 = orientation(p3, p4, p2);
    let d3 = orientation(p1, p2, p3);
    let d4 = orientation(p1, p2, p4);

    if ((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0))
        && ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0))
    {
        return true;
    }

    (d1.abs() <= EDGE_TOLERANCE && on_segment(p3, p4, p1))
        || (d2.abs() <= EDGE_TOLERANCE && on_segment(p3, p4, p2))
        || (d3.abs() <= EDGE_TOLERANCE && on_segment(p1, p2, p3))
        || (d4.abs() <= EDGE_TOLERANCE && on_segment(p1, p2, p4))
}

fn orientation(a: Point, b: Point, c: Point) -> f64 {
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}

fn on_segment(a: Point, b: Point, p: Point) -> bool {
    p.x >= a.x.min(b.x) - EDGE_TOLERANCE
        && p.x <= a.x.max(b.x) + EDGE_TOLERANCE
        && p.y >= a.y.min(b.y) - EDGE_TOLERANCE
        && p.y <= a.y.max(b.y) + EDGE_TOLERANCE
}

fn is_self_intersecting(points: &[Point]) -> bool {
    let count = points.len();
    if count < 4 {
        return false;
    }

    for i in 0..count {
        let a1 = points[i];
        let a2 = points[(i + 1) % count];

        for j in (i + 1)..count {
            if j == i || (j + 1) % count == i || (i + 1) % count == j {
                continue;
            }

            let b1 = points[j];
            let b2 = points[(j + 1) % count];
            if segments_intersect(a1, a2, b1, b2) {
                return true;
            }
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    fn square() -> Polygon {
        Polygon::new(vec![
            Point::new(0.2, 0.2),
            Point::new(0.4, 0.2),
            Point::new(0.4, 0.4),
            Point::new(0.2, 0.4),
        ])
        .unwrap()
    }

    #[test]
    fn rejects_polygons_with_too_few_points() {
        assert!(Polygon::new(vec![Point::new(0.1, 0.1), Point::new(0.2, 0.2)]).is_none());
    }

    #[test]
    fn rejects_coordinates_outside_the_frame() {
        let points = vec![
            Point::new(-0.1, 0.2),
            Point::new(0.4, 0.2),
            Point::new(0.4, 0.4),
        ];
        assert!(Polygon::new(points).is_none());
    }

    #[test]
    fn rejects_degenerate_area() {
        let points = vec![
            Point::new(0.2, 0.2),
            Point::new(0.2001, 0.2),
            Point::new(0.2, 0.4),
        ];
        assert!(Polygon::new(points).is_none());
    }

    #[test]
    fn rejects_self_intersecting_outline() {
        let points = vec![
            Point::new(0.2, 0.2),
            Point::new(0.6, 0.6),
            Point::new(0.6, 0.2),
            Point::new(0.2, 0.6),
        ];
        assert!(Polygon::new(points).is_none());
    }

    #[test]
    fn inside_and_outside_points_are_distinguished() {
        let polygon = square();
        assert!(polygon.contains(Point::new(0.3, 0.3)));
        assert!(!polygon.contains(Point::new(0.5, 0.5)));
    }

    #[test]
    fn boundary_and_vertex_points_count_as_inside() {
        let polygon = square();
        assert!(polygon.contains(Point::new(0.2, 0.3)));
        assert!(polygon.contains(Point::new(0.2, 0.2)));
        assert!(polygon.contains(Point::new(0.3, 0.2)));
    }

    #[test]
    fn concave_notch_is_outside() {
        let polygon = Polygon::new(vec![
            Point::new(0.1, 0.1),
            Point::new(0.5, 0.1),
            Point::new(0.5, 0.5),
            Point::new(0.4, 0.5),
            Point::new(0.4, 0.2),
            Point::new(0.1, 0.2),
        ])
        .unwrap();

        assert!(polygon.contains(Point::new(0.15, 0.15)));
        assert!(polygon.contains(Point::new(0.45, 0.45)));
        assert!(!polygon.contains(Point::new(0.2, 0.4)));
    }

    #[test]
    fn centroid_of_a_square_is_its_center() {
        let centroid = square().centroid();
        assert!((centroid.x - 0.3).abs() < 1e-9);
        assert!((centroid.y - 0.3).abs() < 1e-9);
    }
}
