package com.jannetai.backend.service.complaint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Audit GAP-008 (SRS 15.5: "GPS coordinates are reverse-geocoded to a ward/zone;
 * ... fall back to the nearest ward"). Pure JDK so it can be tested without
 * Spring or a spatial database.
 *
 * <ol>
 *   <li>Point-in-polygon (ray casting, holes respected) against every ward
 *       boundary (wards.boundary_geojson, Polygon or MultiPolygon);</li>
 *   <li>otherwise the ward whose boundary is nearest, if it is within
 *       {@code maxNearestMeters} (a point just outside every drawn boundary,
 *       e.g. on a boundary road);</li>
 *   <li>otherwise no ward (the complaint keeps location.ward_id NULL, as before).</li>
 * </ol>
 * Distances use a local equirectangular projection around the point, accurate
 * to well under 1 % at city scale.
 */
public final class WardLocator {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** One ward's parsed boundary: polygon -> rings -> {@code [lng, lat]}. */
    public record WardShape(long wardId, List<List<List<double[]>>> polygons) {
        public static Optional<WardShape> parse(long wardId, String boundaryGeojson) {
            List<List<List<double[]>>> polygons = WardCentroid.polygons(boundaryGeojson);
            return polygons.isEmpty() ? Optional.empty() : Optional.of(new WardShape(wardId, polygons));
        }
    }

    private WardLocator() {
    }

    public static Optional<Long> locate(List<WardShape> wards, double lat, double lng, double maxNearestMeters) {
        for (WardShape ward : wards) {
            if (contains(ward, lat, lng)) {
                return Optional.of(ward.wardId());
            }
        }
        Long nearest = null;
        double best = Double.MAX_VALUE;
        for (WardShape ward : wards) {
            double d = distanceMeters(ward, lat, lng);
            if (d < best) {
                best = d;
                nearest = ward.wardId();
            }
        }
        return nearest != null && best <= maxNearestMeters ? Optional.of(nearest) : Optional.empty();
    }

    static boolean contains(WardShape ward, double lat, double lng) {
        for (List<List<double[]>> polygon : ward.polygons()) {
            if (inRing(polygon.get(0), lat, lng)) {
                boolean inHole = false;
                for (int h = 1; h < polygon.size(); h++) {
                    if (inRing(polygon.get(h), lat, lng)) {
                        inHole = true;
                        break;
                    }
                }
                if (!inHole) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ray casting; ring points are {@code [lng, lat]}; closing point optional. */
    static boolean inRing(List<double[]> ring, double lat, double lng) {
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i)[0];
            double yi = ring.get(i)[1];
            double xj = ring.get(j)[0];
            double yj = ring.get(j)[1];
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Shortest distance (metres) from the point to any boundary edge of the ward. */
    static double distanceMeters(WardShape ward, double lat, double lng) {
        double best = Double.MAX_VALUE;
        double cosLat = Math.cos(Math.toRadians(lat));
        for (List<List<double[]>> polygon : ward.polygons()) {
            for (List<double[]> ring : polygon) {
                for (int i = 0; i < ring.size(); i++) {
                    double[] a = ring.get(i);
                    double[] b = ring.get((i + 1) % ring.size());
                    best = Math.min(best, segmentDistance(lat, lng, a, b, cosLat));
                }
            }
        }
        return best;
    }

    private static double segmentDistance(double lat, double lng, double[] a, double[] b, double cosLat) {
        // project to metres around the query point
        double ax = Math.toRadians(a[0] - lng) * cosLat * EARTH_RADIUS_M;
        double ay = Math.toRadians(a[1] - lat) * EARTH_RADIUS_M;
        double bx = Math.toRadians(b[0] - lng) * cosLat * EARTH_RADIUS_M;
        double by = Math.toRadians(b[1] - lat) * EARTH_RADIUS_M;
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double px = ax + t * dx;
        double py = ay + t * dy;
        return Math.sqrt(px * px + py * py);
    }

    /** Great-circle distance in metres. */
    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /**
     * Bounding box {minLat, maxLat, minLng, maxLng} that contains every point
     * within {@code radiusMeters} of the given point (for an indexed pre-filter).
     */
    public static double[] boundingBox(double lat, double lng, double radiusMeters) {
        double dLat = Math.toDegrees(radiusMeters / EARTH_RADIUS_M);
        double cos = Math.max(Math.cos(Math.toRadians(lat)), 1e-6);
        double dLng = Math.toDegrees(radiusMeters / (EARTH_RADIUS_M * cos));
        return new double[]{lat - dLat, lat + dLat, lng - dLng, lng + dLng};
    }

    /** Convenience for callers holding several (id, geojson) pairs. */
    public static List<WardShape> shapesOf(List<Long> ids, List<String> geojsons) {
        List<WardShape> shapes = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            WardShape.parse(ids.get(i), geojsons.get(i)).ifPresent(shapes::add);
        }
        return shapes;
    }
}
