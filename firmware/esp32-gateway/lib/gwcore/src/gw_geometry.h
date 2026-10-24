// Plane geometry of the on-board route tracking: free physical surface received from the pilot (WKB), point-segment
// distance and point-in-polygon of map/Geometry.kt, and the fast surface index of map/AreaIndex.kt.
// Meters in the map plane, doubles everywhere (exact predicates of the Kotlin code; the ESP32 emulates them).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "gw_types.h"

namespace gw {
namespace leg {

// Ring as carried by WKB: closed (last point == first point), any orientation.
using Ring = std::vector<Point>;
struct Polygon {
  Ring shell;
  std::vector<Ring> holes;
};
// Polygonal surface: Polygon (one element) or MultiPolygon. Empty polygons are dropped at decoding.
using Surface = std::vector<Polygon>;

// Decodes the WKB of LegBlobs.encodeSurface (JTS WKBWriter(): big endian, 2D; both byte orders, ISO/EWKB Z and M
// (dropped) and SRID are accepted too). Polygon, MultiPolygon, or GeometryCollection of polygonal members only.
// Throws LegInvalid (French message) on malformed or non-polygonal bytes. Validity is NOT checked here: see
// surfaceDefect() and TrajectoryEnvelope.
Surface decodeWkb(const uint8_t *data, size_t size);

// Subset of JTS isValid checked without the edge index (the edge crossings are checked by AreaIndex::crossingEdges()):
// non-empty surface, rings closed with at least 4 points, finite coordinates, non-zero ring area, each hole inside its
// shell, no shell inside another polygon (outside its holes). Returns the first defect (French), empty if none.
// Not checked (unlike JTS isValid): interior connected (holes touching each other or the shell so as to split the
// interior), self-touching rings at a single point, holes nested in other holes of the same polygon.
std::string surfaceDefect(const Surface &surface);

// Twice the signed area of a closed ring (> 0: counter-clockwise).
double ringArea2(const Ring &ring);

// fr.luba.poc.map.Geometry
struct Geometry {
  // Ray casting (open or closed list of vertices).
  static bool inside(const Point &p, const std::vector<Point> &poly);
  // Distance from p to the segment [a, b].
  static double distance(const Point &p, const Point &a, const Point &b);
};

// Port of map/AreaIndex.kt: fast inclusion tests in a fixed polygonal surface (polygons and holes), edges indexed in a
// uniform grid (compact arrays). A point is covered if inside or closer than [tolerance] to the edge.
// Not re-entrant (one mark buffer per instance, like the Kotlin per-thread buffer): calls must be serialized.
// Edges are stored oriented with the interior on their left (shells counter-clockwise, holes clockwise).
class AreaIndex {
 public:
  explicit AreaIndex(const Surface &area, double tolerance = 1e-5);
  AreaIndex(const AreaIndex &) = delete;
  AreaIndex &operator=(const AreaIndex &) = delete;

  bool isEmpty() const { return edges_ == 0; }
  bool coversPoint(double x, double y) const;
  bool covers(const Point &p) const { return coversPoint(p.x, p.y); }
  // Segment covered: no clear crossing of the edge, every portion between two contacts covered.
  bool covers(const Point &a, const Point &b) const { return coversSegment(a.x, a.y, b.x, b.y); }
  bool coversSegment(double x1, double y1, double x2, double y2) const;
  // Convex polygon (n >= 3 vertices, any orientation) strictly inside the surface and at a distance strictly greater
  // than [clearance] from any edge (containsProperly of the polygon grown by a disc of radius clearance).
  bool containsConvex(const double *xs, const double *ys, int n, double clearance) const;
  // Closed disc strictly inside (distance to the edge > radius).
  bool containsDisc(double x, double y, double radius) const;
  // Area of the surface inside the convex polygon (n >= 3, any orientation, at most 32 vertices): exact (Green's
  // theorem over the edges near the polygon and the polygon sides inside the surface), not in the Kotlin index.
  // Raw result: may leave [0, polygon area] by rounding (or on a degenerate surface): the caller clamps it prudently.
  double convexArea(const double *xs, const double *ys, int n) const;
  // Two edges crossing properly or overlapping along a positive length (JTS: self-intersection, invalid surface).
  bool crossingEdges() const;

  size_t edgeCount() const { return edges_; }
  size_t cellCount() const { return (size_t)cols_ * rows_; }
  size_t memoryBytes() const;

  // Grid cell count cap (Kotlin: 1 000 000; ESP32 memory: 65 536, ~0.5 MB at most for the grid).
  static constexpr int MAX_CELLS = 65536;

 private:
  double tolerance_;
  size_t edges_ = 0;
  std::vector<double> ax_, ay_, bx_, by_, lengths_, maxXs_, minXs_;
  double minX_ = 0, minY_ = 0, cell_ = 1;
  int cols_ = 1, rows_ = 1;
  std::vector<uint32_t> cellStart_, cellEdges_;  // edges closer than the tolerance to each cell (CSR)
  std::vector<uint32_t> rowStart_, byMaxX_, byMinX_;  // edges of each row by decreasing max x / increasing min x
  std::vector<uint8_t> cellState_;  // cells without a nearby edge: 1 inside, 2 outside, 0 to test
  mutable std::vector<uint32_t> seen_;
  mutable uint32_t generation_ = 0;

  uint32_t nextGeneration() const;
  int row(double y) const;
  int col(double x) const;
  bool inGrid(double x, double y) const;
  bool rayInside(double x, double y) const;
  bool insideStrict(double x, double y) const;  // ray casting, tolerance ignored
  double edgeDistanceSquared(double px, double py, uint32_t i) const;
  template <class F>
  void forCells(double x1, double y1, double x2, double y2, double pad, F action) const;
  const uint32_t *cellBegin(int c) const { return cellEdges_.data() + cellStart_[c]; }
  const uint32_t *cellEnd(int c) const { return cellEdges_.data() + cellStart_[c + 1]; }
};

}  // namespace leg
}  // namespace gw
