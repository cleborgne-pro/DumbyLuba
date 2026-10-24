#include "gw_geometry.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace gw {
namespace leg {

// ---------------------------------------------------------------------------------------------------------------
// WKB (OGC Simple Features; JTS WKBWriter() writes big endian 2D, polygons as rings of closed coordinates).

namespace {

struct WkbReader {
  const uint8_t *data;
  size_t size;
  size_t pos = 0;
  bool little = false;

  void need(size_t n) const {
    if (n > size - pos) throw LegInvalid("Surface WKB tronquée");
  }
  uint8_t byte() {
    need(1);
    return data[pos++];
  }
  uint32_t u32() {
    need(4);
    const uint8_t *p = data + pos;
    pos += 4;
    return little ? (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24
                  : (uint32_t)p[3] | (uint32_t)p[2] << 8 | (uint32_t)p[1] << 16 | (uint32_t)p[0] << 24;
  }
  double f64() {
    need(8);
    uint64_t v = 0;
    for (int k = 0; k < 8; k++) v |= (uint64_t)data[pos + k] << (little ? 8 * k : 56 - 8 * k);
    pos += 8;
    double d;
    std::memcpy(&d, &v, sizeof d);
    return d;
  }
  // Byte order, type, dimensions, optional SRID; returns the base type (1..7).
  int header(int &dimensions) {
    const uint8_t order = byte();
    if (order > 1) throw LegInvalid("Surface WKB : ordre des octets inconnu");
    little = order == 1;
    const uint32_t raw = u32();
    bool z = (raw & 0x80000000u) != 0, m = (raw & 0x40000000u) != 0;
    const bool srid = (raw & 0x20000000u) != 0;
    const uint32_t base = raw & 0xFFFFu;
    const uint32_t iso = base / 1000;
    if (iso == 1 || iso == 3) z = true;
    if (iso == 2 || iso == 3) m = true;
    if (iso > 3) throw LegInvalid("Surface WKB : géométrie non surfacique (type " + std::to_string(raw) + ")");
    dimensions = 2 + (z ? 1 : 0) + (m ? 1 : 0);
    if (srid) u32();
    return (int)(base % 1000);
  }
  Ring ring(int dimensions) {
    const uint32_t count = u32();
    need((size_t)count * 8u * (size_t)dimensions);
    Ring r;
    r.reserve(count);
    for (uint32_t k = 0; k < count; k++) {
      const double x = f64(), y = f64();
      for (int d = 2; d < dimensions; d++) f64();
      r.emplace_back(x, y);
    }
    return r;
  }
  void polygon(int dimensions, Surface &out) {
    const uint32_t rings = u32();
    need((size_t)rings * 4u);
    if (rings == 0) return;  // POLYGON EMPTY
    Polygon p;
    p.shell = ring(dimensions);
    for (uint32_t k = 1; k < rings; k++) p.holes.push_back(ring(dimensions));
    out.push_back(std::move(p));
  }
  void geometry(Surface &out, int depth) {
    if (depth > 4) throw LegInvalid("Surface WKB : imbrication trop profonde");
    int dimensions = 2;
    const int type = header(dimensions);
    if (type == 3) {
      polygon(dimensions, out);
    } else if (type == 6 || type == 7) {
      const uint32_t count = u32();
      need((size_t)count * 5u);
      for (uint32_t k = 0; k < count; k++) {
        const bool saved = little;
        geometry(out, depth + 1);
        little = saved;
      }
    } else {
      throw LegInvalid("Surface WKB : géométrie non surfacique (type " + std::to_string(type) + ")");
    }
  }
};

double ringDistance(const Point &p, const Ring &ring) {
  double best = std::numeric_limits<double>::infinity();
  for (size_t i = 0; i + 1 < ring.size(); i++) best = std::min(best, Geometry::distance(p, ring[i], ring[i + 1]));
  return best;
}

struct Box {
  double x0 = INFINITY, y0 = INFINITY, x1 = -INFINITY, y1 = -INFINITY;
  explicit Box(const Ring &r) {
    for (const Point &p : r) {
      x0 = std::min(x0, p.x); x1 = std::max(x1, p.x); y0 = std::min(y0, p.y); y1 = std::max(y1, p.y);
    }
  }
  bool within(const Box &o) const { return x0 >= o.x0 && x1 <= o.x1 && y0 >= o.y0 && y1 <= o.y1; }
};

std::string ringDefect(const Ring &r) {
  if (r.size() < 4) return "anneau de moins de 4 points";
  for (const Point &p : r)
    if (!std::isfinite(p.x) || !std::isfinite(p.y)) return "coordonnée non finie";
  if (r.front() != r.back()) return "anneau non fermé";
  if (!(ringArea2(r) != 0)) return "anneau d'aire nulle";
  return "";
}

}  // namespace

Surface decodeWkb(const uint8_t *data, size_t size) {
  if (data == nullptr || size == 0) throw LegInvalid("Surface WKB tronquée");
  WkbReader reader{data, size};
  Surface out;
  reader.geometry(out, 0);
  if (reader.pos != size) throw LegInvalid("Surface WKB : octets en trop");
  return out;
}

double ringArea2(const Ring &ring) {
  double a = 0;
  for (size_t i = 0; i + 1 < ring.size(); i++) a += ring[i].x * ring[i + 1].y - ring[i + 1].x * ring[i].y;
  return a;
}

std::string surfaceDefect(const Surface &surface) {
  if (surface.empty()) return "surface vide";
  for (const Polygon &p : surface) {
    std::string d = ringDefect(p.shell);
    if (!d.empty()) return d;
    for (const Ring &h : p.holes) {
      d = ringDefect(h);
      if (!d.empty()) return d;
    }
  }
  // A vertex of the inner ring off the outer one: inside it (holes) / not inside it (other polygons).
  auto offVertex = [](const Ring &inner, const Ring &outer, Point &found) {
    for (size_t k = 0; k + 1 < inner.size(); k++)
      if (ringDistance(inner[k], outer) > 0) { found = inner[k]; return true; }
    return false;
  };
  for (const Polygon &p : surface) {
    for (const Ring &h : p.holes) {
      Point v;
      if (!offVertex(h, p.shell, v) || !Geometry::inside(v, p.shell)) return "trou hors de son contour";
    }
  }
  for (size_t i = 0; i < surface.size(); i++) {
    const Box bi(surface[i].shell);
    for (size_t j = 0; j < surface.size(); j++) {
      if (i == j || !bi.within(Box(surface[j].shell))) continue;
      const Ring &inner = surface[i].shell;
      for (size_t k = 0; k + 1 < inner.size(); k++) {
        const Point &v = inner[k];
        bool onBoundary = !(ringDistance(v, surface[j].shell) > 0);
        for (const Ring &h : surface[j].holes) onBoundary = onBoundary || !(ringDistance(v, h) > 0);
        if (onBoundary) continue;
        bool inHole = false;
        for (const Ring &h : surface[j].holes) inHole = inHole || Geometry::inside(v, h);
        if (Geometry::inside(v, surface[j].shell) && !inHole) return "polygone contenu dans un autre";
        break;
      }
    }
  }
  return "";
}

// ---------------------------------------------------------------------------------------------------------------
// map/Geometry.kt

bool Geometry::inside(const Point &p, const std::vector<Point> &poly) {
  bool yes = false;
  const size_t n = poly.size();
  for (size_t i = 0; i < n; i++) {
    const Point &a = poly[i];
    const Point &b = poly[(i + 1) % n];
    if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) yes = !yes;
  }
  return yes;
}

double Geometry::distance(const Point &p, const Point &a, const Point &b) {
  const double dx = b.x - a.x, dy = b.y - a.y;
  const double t = (dx * dx + dy * dy == 0.0) ? 0.0
                                               : std::min(1.0, std::max(0.0, ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)));
  return p.distance(Point(a.x + t * dx, a.y + t * dy));
}

// ---------------------------------------------------------------------------------------------------------------
// map/AreaIndex.kt

namespace {
inline double clamp01(double t) { return t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t); }

double pointSegmentSquared(double px, double py, double x1, double y1, double x2, double y2) {
  const double dx = x2 - x1, dy = y2 - y1, d2 = dx * dx + dy * dy;
  const double t = d2 == 0.0 ? 0.0 : clamp01(((px - x1) * dx + (py - y1) * dy) / d2);
  const double qx = x1 + t * dx - px, qy = y1 + t * dy - py;
  return qx * qx + qy * qy;
}

// Minimum squared distance between two segments; 0 if they cross.
double segmentsSquared(double ax1, double ay1, double ax2, double ay2, double bx1, double by1, double bx2, double by2) {
  const double d1 = (ax2 - ax1) * (by1 - ay1) - (ay2 - ay1) * (bx1 - ax1);
  const double d2 = (ax2 - ax1) * (by2 - ay1) - (ay2 - ay1) * (bx2 - ax1);
  const double d3 = (bx2 - bx1) * (ay1 - by1) - (by2 - by1) * (ax1 - bx1);
  const double d4 = (bx2 - bx1) * (ay2 - by1) - (by2 - by1) * (ax2 - bx1);
  if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return 0.0;
  return std::min(std::min(pointSegmentSquared(ax1, ay1, bx1, by1, bx2, by2), pointSegmentSquared(ax2, ay2, bx1, by1, bx2, by2)),
                  std::min(pointSegmentSquared(bx1, by1, ax1, ay1, ax2, ay2), pointSegmentSquared(bx2, by2, ax1, ay1, ax2, ay2)));
}
}  // namespace

AreaIndex::AreaIndex(const Surface &area, double tolerance) : tolerance_(tolerance) {
  require(std::isfinite(tolerance) && tolerance >= 0.0, "Tolérance invalide");
  auto ring = [&](const Ring &r, bool counterClockwise) {
    if (r.size() < 2) return;
    const bool reverse = (ringArea2(r) > 0) != counterClockwise;
    for (size_t i = 0; i + 1 < r.size(); i++) {
      Point a = r[i], b = r[i + 1];
      if (reverse) std::swap(a, b);
      if (a.x == b.x && a.y == b.y) continue;
      ax_.push_back(a.x); ay_.push_back(a.y); bx_.push_back(b.x); by_.push_back(b.y);
    }
  };
  for (const Polygon &p : area) {
    if (p.shell.empty()) continue;
    ring(p.shell, true);
    for (const Ring &h : p.holes) ring(h, false);
  }
  edges_ = ax_.size();
  lengths_.resize(edges_); maxXs_.resize(edges_); minXs_.resize(edges_);
  for (size_t i = 0; i < edges_; i++) {
    lengths_[i] = std::hypot(bx_[i] - ax_[i], by_[i] - ay_[i]);
    maxXs_[i] = std::max(ax_[i], bx_[i]);
    minXs_[i] = std::min(ax_[i], bx_[i]);
  }
  seen_.assign(edges_, 0);
  const double pad = tolerance + 1e-9;
  if (edges_ == 0) {
    cellStart_.assign(2, 0);
    rowStart_.assign(2, 0);
    cellState_.assign(1, 2);
    return;
  }
  double x0 = INFINITY, y0 = INFINITY, x1 = -INFINITY, y1 = -INFINITY;
  for (size_t i = 0; i < edges_; i++) {
    x0 = std::min(x0, minXs_[i]); x1 = std::max(x1, maxXs_[i]);
    y0 = std::min(y0, std::min(ay_[i], by_[i])); y1 = std::max(y1, std::max(ay_[i], by_[i]));
  }
  require(std::isfinite(x0) && std::isfinite(y0) && std::isfinite(x1) && std::isfinite(y1), "Surface physique libre invalide ou vide");
  minX_ = x0 - pad; minY_ = y0 - pad;
  const double width = x1 - x0 + 2 * pad, height = y1 - y0 + 2 * pad;
  double size = std::sqrt(std::max(width * height, 1e-6) / (double)edges_);
  size = std::min(5.0, std::max(0.05, size));
  while ((width / size + 1) * (height / size + 1) > MAX_CELLS) size *= 1.5;
  cell_ = size;
  cols_ = std::max(1, (int)std::ceil(width / size));
  rows_ = std::max(1, (int)std::ceil(height / size));
  const int cells = cols_ * rows_;
  // Cells: count then fill (compressed rows).
  std::vector<uint32_t> counts(cells + 1, 0);
  for (size_t i = 0; i < edges_; i++)
    forCells(ax_[i], ay_[i], bx_[i], by_[i], pad, [&](int c) { counts[c]++; return true; });
  cellStart_.assign(cells + 1, 0);
  for (int c = 0; c < cells; c++) cellStart_[c + 1] = cellStart_[c] + counts[c];
  cellEdges_.resize(cellStart_[cells]);
  std::vector<uint32_t> fill(cellStart_.begin(), cellStart_.end() - 1);
  for (size_t i = 0; i < edges_; i++)
    forCells(ax_[i], ay_[i], bx_[i], by_[i], pad, [&](int c) { cellEdges_[fill[c]++] = (uint32_t)i; return true; });
  // Rows (ray casting), sorted by decreasing maximum / increasing minimum abscissa.
  std::vector<uint32_t> rowCounts(rows_, 0);
  for (size_t i = 0; i < edges_; i++)
    for (int r = row(std::min(ay_[i], by_[i])); r <= row(std::max(ay_[i], by_[i])); r++) rowCounts[r]++;
  rowStart_.assign(rows_ + 1, 0);
  for (int r = 0; r < rows_; r++) rowStart_[r + 1] = rowStart_[r] + rowCounts[r];
  byMaxX_.resize(rowStart_[rows_]);
  std::vector<uint32_t> rowFill(rowStart_.begin(), rowStart_.end() - 1);
  for (size_t i = 0; i < edges_; i++)
    for (int r = row(std::min(ay_[i], by_[i])); r <= row(std::max(ay_[i], by_[i])); r++) byMaxX_[rowFill[r]++] = (uint32_t)i;
  byMinX_ = byMaxX_;
  for (int r = 0; r < rows_; r++) {
    std::stable_sort(byMaxX_.begin() + rowStart_[r], byMaxX_.begin() + rowStart_[r + 1],
                     [&](uint32_t a, uint32_t b) { return maxXs_[a] > maxXs_[b]; });
    std::stable_sort(byMinX_.begin() + rowStart_[r], byMinX_.begin() + rowStart_[r + 1],
                     [&](uint32_t a, uint32_t b) { return minXs_[a] < minXs_[b]; });
  }
  // No edge closer than the tolerance in the cell: all its points have the status of its center.
  cellState_.assign(cells, 0);
  for (int c = 0; c < cells; c++) {
    if (cellStart_[c + 1] != cellStart_[c]) continue;
    const double x = minX_ + (c % cols_ + 0.5) * cell_, y = minY_ + (c / cols_ + 0.5) * cell_;
    cellState_[c] = rayInside(x, y) ? 1 : 2;
  }
}

size_t AreaIndex::memoryBytes() const {
  return sizeof(*this) + 7 * edges_ * sizeof(double) +
         (cellStart_.size() + cellEdges_.size() + rowStart_.size() + byMaxX_.size() + byMinX_.size() + seen_.size()) * sizeof(uint32_t) +
         cellState_.size();
}

uint32_t AreaIndex::nextGeneration() const {
  if (++generation_ == 0) {
    std::fill(seen_.begin(), seen_.end(), 0);
    generation_ = 1;
  }
  return generation_;
}

int AreaIndex::row(double y) const {
  const double v = std::floor((y - minY_) / cell_);
  if (!(v >= 0)) return 0;
  if (v >= rows_ - 1) return rows_ - 1;
  return (int)v;
}

int AreaIndex::col(double x) const {
  const double v = std::floor((x - minX_) / cell_);
  if (!(v >= 0)) return 0;
  if (v >= cols_ - 1) return cols_ - 1;
  return (int)v;
}

bool AreaIndex::inGrid(double x, double y) const {
  return x >= minX_ && y >= minY_ && x <= minX_ + cols_ * cell_ && y <= minY_ + rows_ * cell_;
}

template <class F>
void AreaIndex::forCells(double x1, double y1, double x2, double y2, double pad, F action) const {
  const int r0 = row(std::min(y1, y2) - pad), r1 = row(std::max(y1, y2) + pad);
  for (int r = r0; r <= r1; r++) {
    const double bandLow = minY_ + r * cell_ - pad, bandHigh = minY_ + (r + 1) * cell_ + pad;
    double lo, hi;
    if (std::fabs(y2 - y1) < 1e-15) {
      lo = std::min(x1, x2); hi = std::max(x1, x2);
    } else {
      const double ta = clamp01((bandLow - y1) / (y2 - y1)), tb = clamp01((bandHigh - y1) / (y2 - y1));
      const double xa = x1 + (x2 - x1) * ta, xb = x1 + (x2 - x1) * tb;
      lo = std::min(xa, xb); hi = std::max(xa, xb);
    }
    const int c0 = col(lo - pad), c1 = col(hi + pad);
    for (int c = c0; c <= c1; c++)
      if (!action(r * cols_ + c)) return;
  }
}

bool AreaIndex::rayInside(double x, double y) const {
  const int r = row(y);
  const uint32_t *right = byMaxX_.data() + rowStart_[r];
  const uint32_t *left = byMinX_.data() + rowStart_[r];
  const size_t n = rowStart_[r + 1] - rowStart_[r];
  // Number of edges whose maximum abscissa is >= x / minimum abscissa <= x (binary searches on the sorted rows).
  size_t lo = 0, hi = n;
  while (lo < hi) { const size_t mid = (lo + hi) / 2; if (maxXs_[right[mid]] >= x) lo = mid + 1; else hi = mid; }
  const size_t rightCount = lo;
  lo = 0; hi = n;
  while (lo < hi) { const size_t mid = (lo + hi) / 2; if (minXs_[left[mid]] <= x) lo = mid + 1; else hi = mid; }
  const size_t leftCount = lo;
  bool inside = false;
  if (rightCount <= leftCount) {
    for (size_t k = 0; k < rightCount; k++) {
      const uint32_t i = right[k];
      if ((ay_[i] > y) != (by_[i] > y) && x < (bx_[i] - ax_[i]) * (y - ay_[i]) / (by_[i] - ay_[i]) + ax_[i]) inside = !inside;
    }
  } else {
    for (size_t k = 0; k < leftCount; k++) {
      const uint32_t i = left[k];
      if ((ay_[i] > y) != (by_[i] > y) && x > (bx_[i] - ax_[i]) * (y - ay_[i]) / (by_[i] - ay_[i]) + ax_[i]) inside = !inside;
    }
  }
  return inside;
}

double AreaIndex::edgeDistanceSquared(double px, double py, uint32_t i) const {
  const double dx = bx_[i] - ax_[i], dy = by_[i] - ay_[i];
  const double t = clamp01(((px - ax_[i]) * dx + (py - ay_[i]) * dy) / (dx * dx + dy * dy));
  const double qx = ax_[i] + t * dx - px, qy = ay_[i] + t * dy - py;
  return qx * qx + qy * qy;
}

bool AreaIndex::coversPoint(double x, double y) const {
  if (isEmpty() || !inGrid(x, y)) return false;
  const int c = row(y) * cols_ + col(x);
  if (cellState_[c] == 1) return true;
  if (cellState_[c] == 2) return false;
  const double limit = tolerance_ * tolerance_;
  for (const uint32_t *e = cellBegin(c); e != cellEnd(c); e++)
    if (edgeDistanceSquared(x, y, *e) <= limit) return true;
  return rayInside(x, y);
}

bool AreaIndex::coversSegment(double x1, double y1, double x2, double y2) const {
  const double dx = x2 - x1, dy = y2 - y1;
  const double length = std::hypot(dx, dy);
  if (length < 1e-12) return coversPoint(x1, y1);
  if (!coversPoint(x1, y1) || !coversPoint(x2, y2)) return false;
  std::vector<double> contacts;
  auto contact = [&](double t) { if (t > 0.0 && t < 1.0) contacts.push_back(t); };
  const double tol = tolerance_;
  bool crossed = false;
  const uint32_t generation = nextGeneration();
  forCells(x1, y1, x2, y2, tol, [&](int c) {
    for (const uint32_t *e = cellBegin(c); e != cellEnd(c); e++) {
      const uint32_t i = *e;
      if (seen_[i] == generation) continue;
      seen_[i] = generation;
      const double ex = bx_[i] - ax_[i], ey = by_[i] - ay_[i];
      const double edge = lengths_[i];
      // Signed distances of the edge ends to the segment line, and vice versa.
      const double dc = (dx * (ay_[i] - y1) - dy * (ax_[i] - x1)) / length;
      const double dd = (dx * (by_[i] - y1) - dy * (bx_[i] - x1)) / length;
      const double da = (ex * (y1 - ay_[i]) - ey * (x1 - ax_[i])) / edge;
      const double db = (ex * (y2 - ay_[i]) - ey * (x2 - ax_[i])) / edge;
      if (((dc > tol && dd < -tol) || (dc < -tol && dd > tol)) && ((da > tol && db < -tol) || (da < -tol && db > tol))) {
        crossed = true;
        return false;
      }
      if (std::fabs(dc) <= tol) contact(((ax_[i] - x1) * dx + (ay_[i] - y1) * dy) / (length * length));
      if (std::fabs(dd) <= tol) contact(((bx_[i] - x1) * dx + (by_[i] - y1) * dy) / (length * length));
      if ((dc > 0) != (dd > 0) && dc != dd) {
        const double s = dc / (dc - dd);
        if (s >= 0.0 && s <= 1.0) {
          const double px = ax_[i] + s * ex, py = ay_[i] + s * ey;
          contact(((px - x1) * dx + (py - y1) * dy) / (length * length));
        }
      }
    }
    return true;
  });
  if (crossed) return false;
  if (contacts.empty()) return coversPoint(x1 + dx / 2, y1 + dy / 2);
  std::sort(contacts.begin(), contacts.end());
  double previous = 0.0;
  for (size_t k = 0; k <= contacts.size(); k++) {
    const double t = k == contacts.size() ? 1.0 : contacts[k];
    if (t - previous > 1e-12) {
      const double m = (previous + t) / 2;
      if (!coversPoint(x1 + dx * m, y1 + dy * m)) return false;
    }
    previous = t;
  }
  return true;
}

bool AreaIndex::containsConvex(const double *xs, const double *ys, int n, double clearance) const {
  if (isEmpty() || n < 3) return false;
  double x0 = INFINITY, y0 = INFINITY, x1 = -INFINITY, y1 = -INFINITY, area2 = 0;
  for (int k = 0; k < n; k++) {
    x0 = std::min(x0, xs[k]); x1 = std::max(x1, xs[k]); y0 = std::min(y0, ys[k]); y1 = std::max(y1, ys[k]);
    const int j = (k + 1) % n;
    area2 += xs[k] * ys[j] - xs[j] * ys[k];
  }
  if (!inGrid(x0 - clearance, y0 - clearance) || !inGrid(x1 + clearance, y1 + clearance)) return false;
  const double sign = area2 >= 0 ? 1.0 : -1.0;
  const double limit = clearance * clearance;
  auto insideConvex = [&](double px, double py) {
    for (int k = 0; k < n; k++) {
      const int j = (k + 1) % n;
      if (sign * ((xs[j] - xs[k]) * (py - ys[k]) - (ys[j] - ys[k]) * (px - xs[k])) < 0) return false;
    }
    return true;
  };
  const uint32_t generation = nextGeneration();
  const int r0 = row(y0 - clearance), r1 = row(y1 + clearance);
  for (int r = r0; r <= r1; r++) {
    const double low = minY_ + r * cell_ - clearance, high = minY_ + (r + 1) * cell_ + clearance;
    double lo = INFINITY, hi = -INFINITY;
    for (int k = 0; k < n; k++) {
      const int j = (k + 1) % n;
      const double sx = xs[k], sy = ys[k], ex = xs[j], ey = ys[j];
      double t0, t1;
      if (std::fabs(ey - sy) < 1e-15) {
        if (sy < low || sy > high) continue;
        t0 = 0.0; t1 = 1.0;
      } else {
        const double ta = (low - sy) / (ey - sy), tb = (high - sy) / (ey - sy);
        t0 = std::max(0.0, std::min(ta, tb)); t1 = std::min(1.0, std::max(ta, tb));
        if (t0 > t1) continue;
      }
      const double xa = sx + (ex - sx) * t0, xb = sx + (ex - sx) * t1;
      lo = std::min(lo, std::min(xa, xb)); hi = std::max(hi, std::max(xa, xb));
    }
    if (lo > hi) continue;
    for (int cc = col(lo - clearance); cc <= col(hi + clearance); cc++) {
      const int c = r * cols_ + cc;
      for (const uint32_t *e = cellBegin(c); e != cellEnd(c); e++) {
        const uint32_t i = *e;
        if (seen_[i] == generation) continue;
        seen_[i] = generation;
        if (insideConvex(ax_[i], ay_[i]) || insideConvex(bx_[i], by_[i])) return false;
        for (int k = 0; k < n; k++) {
          const int j = (k + 1) % n;
          if (segmentsSquared(ax_[i], ay_[i], bx_[i], by_[i], xs[k], ys[k], xs[j], ys[j]) <= limit) return false;
        }
      }
    }
  }
  const int c = row(ys[0]) * cols_ + col(xs[0]);
  return cellState_[c] == 1 ? true : cellState_[c] == 2 ? false : rayInside(xs[0], ys[0]);
}

bool AreaIndex::containsDisc(double x, double y, double radius) const {
  if (isEmpty() || !inGrid(x - radius, y - radius) || !inGrid(x + radius, y + radius)) return false;
  const double limit = radius * radius;
  const uint32_t generation = nextGeneration();
  for (int r = row(y - radius); r <= row(y + radius); r++)
    for (int cc = col(x - radius); cc <= col(x + radius); cc++) {
      const int c = r * cols_ + cc;
      for (const uint32_t *e = cellBegin(c); e != cellEnd(c); e++) {
        const uint32_t i = *e;
        if (seen_[i] == generation) continue;
        seen_[i] = generation;
        if (edgeDistanceSquared(x, y, i) <= limit) return false;
      }
    }
  const int c = row(y) * cols_ + col(x);
  return cellState_[c] == 1 ? true : cellState_[c] == 2 ? false : rayInside(x, y);
}

double AreaIndex::convexArea(const double *xsIn, const double *ysIn, int n) const {
  require(n >= 3 && n <= 32, "Polygone convexe invalide");
  double area2 = 0;
  for (int k = 0; k < n; k++) area2 += xsIn[k] * ysIn[(k + 1) % n] - xsIn[(k + 1) % n] * ysIn[k];
  if (isEmpty() || !(area2 != 0)) return 0.0;
  // Counter-clockwise copy in local coordinates (origin: first vertex), the window interior on the left of its sides.
  const double ox = xsIn[0], oy = ysIn[0];
  double xs[33], ys[33];
  for (int k = 0; k < n; k++) {
    const int s = area2 >= 0 ? k : (n - k) % n;
    xs[k] = xsIn[s] - ox; ys[k] = ysIn[s] - oy;
  }
  xs[n] = xs[0]; ys[n] = ys[0];
  double x0 = INFINITY, y0 = INFINITY, x1 = -INFINITY, y1 = -INFINITY;
  for (int k = 0; k < n; k++) {
    x0 = std::min(x0, xs[k]); x1 = std::max(x1, xs[k]); y0 = std::min(y0, ys[k]); y1 = std::max(y1, ys[k]);
  }
  // Edges of the cells overlapping the window bounding box (an edge is listed in every cell it crosses).
  std::vector<uint32_t> near;
  const uint32_t generation = nextGeneration();
  for (int r = row(y0 + oy); r <= row(y1 + oy); r++)
    for (int cc = col(x0 + ox); cc <= col(x1 + ox); cc++) {
      const int c = r * cols_ + cc;
      for (const uint32_t *e = cellBegin(c); e != cellEnd(c); e++) {
        if (seen_[*e] == generation) continue;
        seen_[*e] = generation;
        near.push_back(*e);
      }
    }
  // An edge portion closer than ALONG to a side lies on it: the side decides (by the edge direction), never both.
  // Status of the other side portions: point INWARD inside the window (INWARD < ALONG: no counted edge in between).
  const double ALONG = 1e-8, INWARD = 1e-9;
  double sides[32];
  for (int k = 0; k < n; k++) sides[k] = std::hypot(xs[k + 1] - xs[k], ys[k + 1] - ys[k]);
  auto side = [&](int k, double px, double py) {  // signed distance to the side line (> 0: inside)
    return ((xs[k + 1] - xs[k]) * (py - ys[k]) - (ys[k + 1] - ys[k]) * (px - xs[k])) / sides[k];
  };
  auto along = [&](int k, double px, double py) {  // position along the side, 0..1
    return ((px - xs[k]) * (xs[k + 1] - xs[k]) + (py - ys[k]) * (ys[k + 1] - ys[k])) / (sides[k] * sides[k]);
  };
  struct Overlap { int k; double t0, t1; bool same; };
  std::vector<Overlap> overlaps;
  // Green's theorem: boundary of (surface ∩ window) = surface edges inside the window (interior on their left) +
  // window sides inside the surface; twice the area = sum of x1*y2 - x2*y1 over these oriented pieces.
  double twice = 0;
  for (const uint32_t i : near) {
    const double axl = ax_[i] - ox, ayl = ay_[i] - oy, ex = bx_[i] - ax_[i], ey = by_[i] - ay_[i];
    double t0 = 0, t1 = 1;
    for (int k = 0; k < n && t0 < t1; k++) {
      if (sides[k] == 0) continue;
      const double f0 = side(k, axl, ayl), f1 = side(k, axl + ex, ayl + ey);
      if (f0 < 0 && f1 < 0) { t1 = -1; break; }
      if (f0 < 0) t0 = std::max(t0, f0 / (f0 - f1));
      else if (f1 < 0) t1 = std::min(t1, f0 / (f0 - f1));
    }
    if (!(t1 > t0)) continue;
    const double px = axl + ex * t0, py = ayl + ey * t0, qx = axl + ex * t1, qy = ayl + ey * t1;
    int on = -1;
    for (int k = 0; k < n && on < 0; k++)
      if (sides[k] != 0 && std::fabs(side(k, px, py)) <= ALONG && std::fabs(side(k, qx, qy)) <= ALONG) on = k;
    if (on >= 0) {
      const double ta = clamp01(along(on, px, py)), tb = clamp01(along(on, qx, qy));
      overlaps.push_back({on, std::min(ta, tb), std::max(ta, tb), tb > ta});
      continue;
    }
    twice += px * qy - qx * py;
  }
  std::vector<double> cuts;
  for (int k = 0; k < n; k++) {
    if (sides[k] == 0) continue;
    const double sx = xs[k], sy = ys[k], dx = xs[k + 1] - sx, dy = ys[k + 1] - sy;
    cuts.assign({0.0, 1.0});
    for (const Overlap &o : overlaps)
      if (o.k == k) { cuts.push_back(o.t0); cuts.push_back(o.t1); }
    for (const uint32_t i : near) {
      const double axl = ax_[i] - ox, ayl = ay_[i] - oy, bxl = bx_[i] - ox, byl = by_[i] - oy;
      // Edge ends on or near the side: split there (an edge ending on the side may miss the crossing test).
      const double fa = side(k, axl, ayl), fb = side(k, bxl, byl);
      if (std::fabs(fa) <= ALONG) cuts.push_back(clamp01(along(k, axl, ayl)));
      if (std::fabs(fb) <= ALONG) cuts.push_back(clamp01(along(k, bxl, byl)));
      if ((fa > 0 && fb < 0) || (fa < 0 && fb > 0)) {
        const double s = fa / (fa - fb);
        const double t = along(k, axl + (bxl - axl) * s, ayl + (byl - ayl) * s);
        if (t > 0 && t < 1) cuts.push_back(t);
      }
    }
    std::sort(cuts.begin(), cuts.end());
    const double nx = -dy / sides[k], ny = dx / sides[k];  // inward normal
    for (size_t m = 0; m + 1 < cuts.size(); m++) {
      const double ta = cuts[m], tb = cuts[m + 1];
      if (tb - ta <= 1e-12) continue;
      const double mt = (ta + tb) / 2;
      int covered = -1;
      for (const Overlap &o : overlaps)
        if (o.k == k && o.t0 <= mt && mt <= o.t1) { covered = o.same ? 1 : 0; break; }
      if (covered < 0) {
        const double qx = sx + dx * mt + nx * INWARD + ox, qy = sy + dy * mt + ny * INWARD + oy;
        covered = insideStrict(qx, qy) ? 1 : 0;
      }
      if (!covered) continue;
      const double px = sx + dx * ta, py = sy + dy * ta, qx = sx + dx * tb, qy = sy + dy * tb;
      twice += px * qy - qx * py;
    }
  }
  return twice / 2;
}

bool AreaIndex::insideStrict(double x, double y) const {
  if (isEmpty() || !inGrid(x, y)) return false;
  const int c = row(y) * cols_ + col(x);
  return cellState_[c] == 1 ? true : cellState_[c] == 2 ? false : rayInside(x, y);
}

bool AreaIndex::crossingEdges() const {
  const int cells = cols_ * rows_;
  for (int c = 0; c < cells; c++) {
    const uint32_t *b = cellBegin(c), *e = cellEnd(c);
    for (const uint32_t *p = b; p != e; p++)
      for (const uint32_t *q = p + 1; q != e; q++) {
        const uint32_t i = *p, j = *q;
        const double d1 = (bx_[i] - ax_[i]) * (ay_[j] - ay_[i]) - (by_[i] - ay_[i]) * (ax_[j] - ax_[i]);
        const double d2 = (bx_[i] - ax_[i]) * (by_[j] - ay_[i]) - (by_[i] - ay_[i]) * (bx_[j] - ax_[i]);
        const double d3 = (bx_[j] - ax_[j]) * (ay_[i] - ay_[j]) - (by_[j] - ay_[j]) * (ax_[i] - ax_[j]);
        const double d4 = (bx_[j] - ax_[j]) * (by_[i] - ay_[j]) - (by_[j] - ay_[j]) * (bx_[i] - ax_[j]);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
        if (d1 == 0 && d2 == 0) {
          // Collinear: overlap of positive length along the edge i.
          const double ex = bx_[i] - ax_[i], ey = by_[i] - ay_[i], l2 = ex * ex + ey * ey;
          const double s0 = ((ax_[j] - ax_[i]) * ex + (ay_[j] - ay_[i]) * ey) / l2;
          const double s1 = ((bx_[j] - ax_[i]) * ex + (by_[j] - ay_[i]) * ey) / l2;
          if (std::min(1.0, std::max(s0, s1)) - std::max(0.0, std::min(s0, s1)) > 1e-12) return true;
        }
      }
  }
  return false;
}

}  // namespace leg
}  // namespace gw
