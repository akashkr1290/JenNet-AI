package com.jannetai.backend.service.complaint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Remaining-gaps item 3: representative point of a ward from its stored
 * {@code wards.boundary_geojson} (GeoJSON Polygon or MultiPolygon, coordinate
 * order [longitude, latitude]). Area-weighted centroid of the outer ring; for
 * a MultiPolygon, of the largest polygon. Returns empty for null, blank,
 * malformed or unsupported geometry - the caller then uses its documented
 * fallback rather than failing a citizen's submission.
 *
 * Dependency-free on purpose (a ~60-line parser for the arrays-of-numbers
 * subset GeoJSON coordinates use) so it is unit-testable in isolation.
 */
public final class WardCentroid {

    private WardCentroid() {
    }

    /** @return {latitude, longitude} */
    public static Optional<double[]> of(String geojson) {
        if (geojson == null || geojson.isBlank()) {
            return Optional.empty();
        }
        try {
            String type = stringField(geojson, "type");
            int at = geojson.indexOf("\"coordinates\"");
            if (type == null || at < 0) {
                return Optional.empty();
            }
            int start = geojson.indexOf('[', at);
            if (start < 0) {
                return Optional.empty();
            }
            Object coords = new Parser(geojson, start).parseValue();
            List<Object> outerRing;
            if ("Polygon".equals(type)) {
                outerRing = asList(asList(coords).get(0));
            } else if ("MultiPolygon".equals(type)) {
                outerRing = null;
                double bestArea = -1;
                for (Object polygon : asList(coords)) {
                    List<Object> ring = asList(asList(polygon).get(0));
                    double area = Math.abs(signedArea(ring));
                    if (area > bestArea) {
                        bestArea = area;
                        outerRing = ring;
                    }
                }
                if (outerRing == null) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
            return centroid(outerRing);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Audit GAP-008: every polygon of a Polygon/MultiPolygon GeoJSON geometry
     * (bare geometry, or a Feature wrapping one), as polygon -> rings (outer ring
     * first, then holes) -> points {@code [lng, lat]}. Empty when the text is not
     * a parseable polygon geometry.
     */
    public static List<List<List<double[]>>> polygons(String geojson) {
        List<List<List<double[]>>> result = new ArrayList<>();
        if (geojson == null || geojson.isBlank()) {
            return result;
        }
        try {
            boolean multi = geojson.contains("\"MultiPolygon\"");
            if (!multi && !geojson.contains("\"Polygon\"")) {
                return result;
            }
            int at = geojson.indexOf("\"coordinates\"");
            int start = at < 0 ? -1 : geojson.indexOf('[', at);
            if (start < 0) {
                return result;
            }
            Object coords = new Parser(geojson, start).parseValue();
            List<Object> polygonList = multi ? asList(coords) : List.of(coords);
            for (Object polygon : polygonList) {
                List<List<double[]>> rings = new ArrayList<>();
                for (Object ring : asList(polygon)) {
                    List<double[]> points = new ArrayList<>();
                    for (Object p : asList(ring)) {
                        points.add(point(p));
                    }
                    if (points.size() >= 3) {
                        rings.add(points);
                    }
                }
                if (!rings.isEmpty()) {
                    result.add(rings);
                }
            }
            return result;
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static Optional<double[]> centroid(List<Object> ring) {
        if (ring.size() < 3) {
            return Optional.empty();
        }
        double a = 0;
        double cx = 0;
        double cy = 0;
        for (int i = 0; i < ring.size() - 1; i++) {
            double[] p = point(ring.get(i));
            double[] q = point(ring.get(i + 1));
            double cross = p[0] * q[1] - q[0] * p[1];
            a += cross;
            cx += (p[0] + q[0]) * cross;
            cy += (p[1] + q[1]) * cross;
        }
        if (Math.abs(a) < 1e-12) { // degenerate ring: fall back to the vertex mean
            double sx = 0;
            double sy = 0;
            for (Object o : ring) {
                double[] p = point(o);
                sx += p[0];
                sy += p[1];
            }
            return valid(sy / ring.size(), sx / ring.size());
        }
        a /= 2;
        return valid(cy / (6 * a), cx / (6 * a));
    }

    private static Optional<double[]> valid(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return Optional.empty();
        }
        return Optional.of(new double[]{lat, lng});
    }

    private static double signedArea(List<Object> ring) {
        double a = 0;
        for (int i = 0; i < ring.size() - 1; i++) {
            double[] p = point(ring.get(i));
            double[] q = point(ring.get(i + 1));
            a += p[0] * q[1] - q[0] * p[1];
        }
        return a / 2;
    }

    private static double[] point(Object o) {
        List<Object> xy = asList(o);
        return new double[]{(Double) xy.get(0), (Double) xy.get(1)}; // [lng, lat]
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        if (!(o instanceof List)) {
            throw new IllegalArgumentException("expected array");
        }
        return (List<Object>) o;
    }

    private static String stringField(String json, String name) {
        int at = json.indexOf("\"" + name + "\"");
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at);
        int open = json.indexOf('"', colon + 1);
        int close = json.indexOf('"', open + 1);
        return (colon < 0 || open < 0 || close < 0) ? null : json.substring(open + 1, close);
    }

    /** Parses nested JSON arrays of numbers - the only shape GeoJSON coordinates take. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s, int i) {
            this.s = s;
            this.i = i;
        }

        Object parseValue() {
            skipWs();
            if (s.charAt(i) == '[') {
                i++;
                List<Object> list = new ArrayList<>();
                skipWs();
                if (s.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    char c = s.charAt(i++);
                    if (c == ']') {
                        return list;
                    }
                    if (c != ',') {
                        throw new IllegalArgumentException("bad array");
                    }
                }
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Double.parseDouble(s.substring(start, i));
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
