package com.jannetai.backend.service.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Audit GAP-020 (SRS 15.11: "department and ward boundary changes require
 * geometry validation (no overlapping/undefined zones) before saving").
 *
 * <p>{@link #validate} accepts a GeoJSON Polygon or MultiPolygon (bare, or
 * wrapped in a Feature) and rejects, with a specific message:
 * <ul>
 *   <li>anything that is not a polygon geometry, or has no coordinates
 *       ("undefined zone");</li>
 *   <li>rings with fewer than 4 positions, rings that are not closed,
 *       positions outside [-180,180] / [-90,90], non-finite numbers;</li>
 *   <li>zero-area rings and self-intersecting rings;</li>
 *   <li>holes that are not inside their outer ring, and MultiPolygon parts
 *       that overlap each other;</li>
 *   <li>more than {@link #MAX_VERTICES} positions in total.</li>
 * </ul>
 * {@link #overlaps} tells whether two wards' interiors intersect. Wards that
 * only share a border (the normal case for neighbouring wards) do not overlap.
 *
 * <p>Geometry is planar in (longitude, latitude) degrees, which is accurate
 * for city-scale shapes that do not cross the antimeridian. Coverage gaps
 * between wards cannot be checked: there is no configured municipal polygon
 * to compare against (only the app.geo.* bounding box).
 *
 * <p>No Spring types (Jackson only), so it can be unit-tested in isolation.
 */
public final class WardBoundaryValidator {

    public static final int MAX_VERTICES = 10_000;
    private static final double EPS = 1e-10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Polygon -> rings (outer first, then holes) -> points {@code [lng, lat]} (closing point kept). */
    public record Result(String normalizedGeojson, List<List<List<double[]>>> polygons) {
    }

    private WardBoundaryValidator() {
    }

    /** @throws IllegalArgumentException with a message fit to show an Admin */
    public static Result validate(String geojson) {
        if (geojson == null || geojson.isBlank()) {
            throw new IllegalArgumentException("Boundary is empty");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(geojson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Boundary is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Boundary must be a GeoJSON object");
        }
        if ("Feature".equals(root.path("type").asText())) {
            root = root.path("geometry");
        }
        String type = root.path("type").asText("");
        JsonNode coordinates = root.path("coordinates");
        if (!coordinates.isArray() || coordinates.isEmpty()) {
            throw new IllegalArgumentException("Boundary has no coordinates (undefined zone)");
        }
        List<List<List<double[]>>> polygons = new ArrayList<>();
        int[] vertexCount = {0};
        switch (type) {
            case "Polygon" -> polygons.add(parsePolygon(coordinates, vertexCount));
            case "MultiPolygon" -> {
                for (JsonNode polygon : coordinates) {
                    polygons.add(parsePolygon(polygon, vertexCount));
                }
            }
            default -> throw new IllegalArgumentException(
                    "Boundary must be a GeoJSON Polygon or MultiPolygon (got '" + type + "')");
        }
        for (int i = 0; i < polygons.size(); i++) {
            for (int j = i + 1; j < polygons.size(); j++) {
                if (overlaps(List.of(polygons.get(i)), List.of(polygons.get(j)))) {
                    throw new IllegalArgumentException("MultiPolygon parts " + (i + 1) + " and " + (j + 1) + " overlap");
                }
            }
        }
        ObjectNode normalized = MAPPER.createObjectNode();
        normalized.put("type", type);
        normalized.set("coordinates", coordinates.deepCopy());
        return new Result(normalized.toString(), polygons);
    }

    private static List<List<double[]>> parsePolygon(JsonNode polygon, int[] vertexCount) {
        if (!polygon.isArray() || polygon.isEmpty()) {
            throw new IllegalArgumentException("A polygon has no rings (undefined zone)");
        }
        List<List<double[]>> rings = new ArrayList<>();
        for (JsonNode ringNode : polygon) {
            if (!ringNode.isArray() || ringNode.size() < 4) {
                throw new IllegalArgumentException("Every ring needs at least 4 positions (first = last)");
            }
            List<double[]> ring = new ArrayList<>();
            for (JsonNode position : ringNode) {
                if (!position.isArray() || position.size() < 2 || !position.get(0).isNumber() || !position.get(1).isNumber()) {
                    throw new IllegalArgumentException("Every position must be [longitude, latitude]");
                }
                double lng = position.get(0).asDouble();
                double lat = position.get(1).asDouble();
                if (!Double.isFinite(lng) || !Double.isFinite(lat) || lng < -180 || lng > 180 || lat < -90 || lat > 90) {
                    throw new IllegalArgumentException("Position out of range: [" + lng + ", " + lat + "]");
                }
                double[] previous = ring.isEmpty() ? null : ring.get(ring.size() - 1);
                if (previous == null || previous[0] != lng || previous[1] != lat) {
                    ring.add(new double[]{lng, lat}); // consecutive duplicate positions are dropped
                }
            }
            if (ring.size() < 4) {
                throw new IllegalArgumentException("Every ring needs at least 3 distinct corners");
            }
            vertexCount[0] += ring.size();
            if (vertexCount[0] > MAX_VERTICES) {
                throw new IllegalArgumentException("Boundary has more than " + MAX_VERTICES + " positions - simplify it first");
            }
            double[] first = ring.get(0);
            double[] last = ring.get(ring.size() - 1);
            if (first[0] != last[0] || first[1] != last[1]) {
                throw new IllegalArgumentException("Every ring must be closed (last position equal to the first)");
            }
            if (selfIntersects(ring)) {
                throw new IllegalArgumentException("A ring crosses itself");
            }
            if (Math.abs(signedArea(ring)) < 1e-12) {
                throw new IllegalArgumentException("A ring has zero area");
            }
            rings.add(ring);
        }
        List<double[]> outer = rings.get(0);
        for (int h = 1; h < rings.size(); h++) {
            for (double[] p : rings.get(h)) {
                if (!inRing(outer, p[0], p[1]) && !onRing(outer, p[0], p[1])) {
                    throw new IllegalArgumentException("A hole lies outside its polygon's outer ring");
                }
            }
            if (ringsCross(outer, rings.get(h))) {
                throw new IllegalArgumentException("A hole crosses its polygon's outer ring");
            }
        }
        return rings;
    }

    // ---- overlap between two (multi)polygons ----

    /** True when the interiors of the two shapes intersect (a shared border alone is not an overlap). */
    public static boolean overlaps(List<List<List<double[]>>> a, List<List<List<double[]>>> b) {
        if (a.isEmpty() || b.isEmpty() || !boundsIntersect(bounds(a), bounds(b))) {
            return false;
        }
        return boundaryEntersInterior(a, b) || boundaryEntersInterior(b, a)
                || interiorPointInside(a, b) || interiorPointInside(b, a);
    }

    /** Some piece of a's boundary lies strictly inside b. Each edge of a is split where it meets b's boundary. */
    private static boolean boundaryEntersInterior(List<List<List<double[]>>> a, List<List<List<double[]>>> b) {
        for (List<List<double[]>> polygon : a) {
            for (List<double[]> ring : polygon) {
                for (int i = 0; i + 1 < ring.size(); i++) {
                    double[] p = ring.get(i);
                    double[] q = ring.get(i + 1);
                    List<Double> cuts = new ArrayList<>(List.of(0.0, 1.0));
                    for (List<List<double[]>> other : b) {
                        for (List<double[]> otherRing : other) {
                            for (int j = 0; j + 1 < otherRing.size(); j++) {
                                addCuts(p, q, otherRing.get(j), otherRing.get(j + 1), cuts);
                            }
                        }
                    }
                    cuts.sort(Double::compare);
                    for (int k = 0; k + 1 < cuts.size(); k++) {
                        double t = (cuts.get(k) + cuts.get(k + 1)) / 2;
                        if (cuts.get(k + 1) - cuts.get(k) < 1e-12) {
                            continue;
                        }
                        double x = p[0] + t * (q[0] - p[0]);
                        double y = p[1] + t * (q[1] - p[1]);
                        if (strictlyInside(b, x, y)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean interiorPointInside(List<List<List<double[]>>> a, List<List<List<double[]>>> b) {
        for (List<List<double[]>> polygon : a) {
            double[] point = interiorPoint(polygon);
            if (point != null && strictlyInside(b, point[0], point[1])) {
                return true;
            }
        }
        return false;
    }

    /** Parameters t in [0,1] along p->q where segment r->s touches or crosses it. */
    private static void addCuts(double[] p, double[] q, double[] r, double[] s, List<Double> cuts) {
        double dx = q[0] - p[0];
        double dy = q[1] - p[1];
        double ex = s[0] - r[0];
        double ey = s[1] - r[1];
        double denom = dx * ey - dy * ex;
        if (Math.abs(denom) > EPS * EPS) {
            double t = ((r[0] - p[0]) * ey - (r[1] - p[1]) * ex) / denom;
            double u = ((r[0] - p[0]) * dy - (r[1] - p[1]) * dx) / denom;
            if (t >= -EPS && t <= 1 + EPS && u >= -EPS && u <= 1 + EPS) {
                cuts.add(Math.max(0, Math.min(1, t)));
            }
            return;
        }
        // parallel: when collinear, r and s themselves split p->q
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) {
            return;
        }
        for (double[] v : new double[][]{r, s}) {
            if (onSegment(p, q, v[0], v[1])) {
                cuts.add(((v[0] - p[0]) * dx + (v[1] - p[1]) * dy) / len2);
            }
        }
    }

    private static boolean strictlyInside(List<List<List<double[]>>> shape, double x, double y) {
        for (List<List<double[]>> polygon : shape) {
            boolean onBoundary = false;
            for (List<double[]> ring : polygon) {
                if (onRing(ring, x, y)) {
                    onBoundary = true;
                    break;
                }
            }
            if (onBoundary || !inRing(polygon.get(0), x, y)) {
                continue;
            }
            boolean inHole = false;
            for (int h = 1; h < polygon.size(); h++) {
                if (inRing(polygon.get(h), x, y)) {
                    inHole = true;
                    break;
                }
            }
            if (!inHole) {
                return true;
            }
        }
        return false;
    }

    /** A point inside the polygon's area (holes excluded): midpoint of the first interior run of a horizontal scanline. */
    static double[] interiorPoint(List<List<double[]>> polygon) {
        List<Double> ys = new ArrayList<>();
        for (double[] p : polygon.get(0)) {
            ys.add(p[1]);
        }
        ys.sort(Double::compare);
        for (int i = 0; i + 1 < ys.size(); i++) {
            if (ys.get(i + 1) - ys.get(i) < 1e-12) {
                continue;
            }
            double y = (ys.get(i) + ys.get(i + 1)) / 2; // strictly between vertex latitudes: no vertex on the line
            List<Double> xs = new ArrayList<>();
            for (List<double[]> ring : polygon) {
                for (int k = 0; k + 1 < ring.size(); k++) {
                    double[] a = ring.get(k);
                    double[] b = ring.get(k + 1);
                    if ((a[1] > y) != (b[1] > y)) {
                        xs.add(a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1]));
                    }
                }
            }
            xs.sort(Double::compare);
            for (int k = 0; k + 1 < xs.size(); k += 2) {
                if (xs.get(k + 1) - xs.get(k) > 1e-12) {
                    return new double[]{(xs.get(k) + xs.get(k + 1)) / 2, y};
                }
            }
        }
        return null;
    }

    // ---- primitives (points are [lng, lat] = [x, y]) ----

    static boolean inRing(List<double[]> ring, double x, double y) {
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i)[0];
            double yi = ring.get(i)[1];
            double xj = ring.get(j)[0];
            double yj = ring.get(j)[1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean onRing(List<double[]> ring, double x, double y) {
        for (int i = 0; i + 1 < ring.size(); i++) {
            if (onSegment(ring.get(i), ring.get(i + 1), x, y)) {
                return true;
            }
        }
        return false;
    }

    private static boolean onSegment(double[] a, double[] b, double x, double y) {
        double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        if (Math.abs(cross) > EPS * Math.max(len, 1e-9)) {
            return false;
        }
        return x >= Math.min(a[0], b[0]) - EPS && x <= Math.max(a[0], b[0]) + EPS
                && y >= Math.min(a[1], b[1]) - EPS && y <= Math.max(a[1], b[1]) + EPS;
    }

    private static double orientation(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /** Segments share any point (crossing, touching or collinear overlap). */
    private static boolean segmentsTouch(double[] p1, double[] p2, double[] q1, double[] q2) {
        double o1 = orientation(p1, p2, q1);
        double o2 = orientation(p1, p2, q2);
        double o3 = orientation(q1, q2, p1);
        double o4 = orientation(q1, q2, p2);
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {
            return true;
        }
        return onSegment(p1, p2, q1[0], q1[1]) || onSegment(p1, p2, q2[0], q2[1])
                || onSegment(q1, q2, p1[0], p1[1]) || onSegment(q1, q2, p2[0], p2[1]);
    }

    /** Proper crossing only (touching/collinear is allowed: a hole may touch its outer ring at a point). */
    private static boolean segmentsCross(double[] p1, double[] p2, double[] q1, double[] q2) {
        double o1 = orientation(p1, p2, q1);
        double o2 = orientation(p1, p2, q2);
        double o3 = orientation(q1, q2, p1);
        double o4 = orientation(q1, q2, p2);
        return ((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0));
    }

    static boolean selfIntersects(List<double[]> ring) {
        int edges = ring.size() - 1;
        for (int i = 0; i < edges; i++) {
            for (int j = i + 1; j < edges; j++) {
                boolean adjacent = j == i + 1 || (i == 0 && j == edges - 1);
                if (adjacent) {
                    // adjacent edges share one endpoint; they are invalid only if they fold back over each other
                    double[] shared = j == i + 1 ? ring.get(j) : ring.get(i);
                    double[] a = j == i + 1 ? ring.get(i) : ring.get(i + 1);
                    double[] b = j == i + 1 ? ring.get(j + 1) : ring.get(j);
                    if (Math.abs(orientation(shared, a, b)) <= EPS * EPS
                            && (a[0] - shared[0]) * (b[0] - shared[0]) + (a[1] - shared[1]) * (b[1] - shared[1]) > 0) {
                        return true;
                    }
                    continue;
                }
                if (segmentsTouch(ring.get(i), ring.get(i + 1), ring.get(j), ring.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean ringsCross(List<double[]> a, List<double[]> b) {
        for (int i = 0; i + 1 < a.size(); i++) {
            for (int j = 0; j + 1 < b.size(); j++) {
                if (segmentsCross(a.get(i), a.get(i + 1), b.get(j), b.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double signedArea(List<double[]> ring) {
        double area = 0;
        for (int i = 0; i + 1 < ring.size(); i++) {
            area += ring.get(i)[0] * ring.get(i + 1)[1] - ring.get(i + 1)[0] * ring.get(i)[1];
        }
        return area / 2;
    }

    private static double[] bounds(List<List<List<double[]>>> shape) {
        double[] b = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        for (List<List<double[]>> polygon : shape) {
            for (double[] p : polygon.get(0)) {
                b[0] = Math.min(b[0], p[0]);
                b[1] = Math.max(b[1], p[0]);
                b[2] = Math.min(b[2], p[1]);
                b[3] = Math.max(b[3], p[1]);
            }
        }
        return b;
    }

    private static boolean boundsIntersect(double[] a, double[] b) {
        return a[0] <= b[1] + EPS && b[0] <= a[1] + EPS && a[2] <= b[3] + EPS && b[2] <= a[3] + EPS;
    }

    /** Convenience: parse an already-stored boundary without throwing (null/invalid -> empty). */
    public static List<List<List<double[]>>> parseStoredOrEmpty(String geojson) {
        try {
            return geojson == null || geojson.isBlank() ? List.of() : validate(geojson).polygons();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }
}
