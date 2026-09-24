package com.jannetai.backend.service.complaint;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remaining-gaps item 3: ward-fallback location point from a stored ward boundary. */
class WardCentroidTest {

    @Test
    void squarePolygonCentroidIsItsCentre() {
        String square = "{\"type\":\"Polygon\",\"coordinates\":[[[77.0,12.0],[77.2,12.0],[77.2,12.2],[77.0,12.2],[77.0,12.0]]]}";
        double[] p = WardCentroid.of(square).orElseThrow();
        assertEquals(12.1, p[0], 1e-9);
        assertEquals(77.1, p[1], 1e-9);
    }

    @Test
    void lShapedPolygonUsesAreaWeightedCentroidNotVertexMean() {
        // L-shape: 2x1 bottom bar + 1x1 top-left block (unit-scaled, offset so values stay valid).
        String l = "{\"type\": \"Polygon\", \"coordinates\": [[[10,10],[12,10],[12,11],[11,11],[11,12],[10,12],[10,10]]]}";
        double[] p = WardCentroid.of(l).orElseThrow();
        assertEquals(10.8333333333, p[0], 1e-6);
        assertEquals(10.8333333333, p[1], 1e-6);
    }

    @Test
    void multiPolygonUsesTheLargestPart() {
        String mp = "{\"type\":\"MultiPolygon\",\"coordinates\":["
                + "[[[0,0],[0.1,0],[0.1,0.1],[0,0.1],[0,0]]],"
                + "[[[70,20],[72,20],[72,22],[70,22],[70,20]]]]}";
        double[] p = WardCentroid.of(mp).orElseThrow();
        assertEquals(21.0, p[0], 1e-9);
        assertEquals(71.0, p[1], 1e-9);
    }

    @Test
    void missingMalformedOrUnsupportedGeometryIsEmpty() {
        assertTrue(WardCentroid.of(null).isEmpty());
        assertTrue(WardCentroid.of("   ").isEmpty());
        assertTrue(WardCentroid.of("{\"type\":\"Polygon\",\"coordinates\":[[[77,12],[77.1").isEmpty());
        assertTrue(WardCentroid.of("{\"type\":\"Point\",\"coordinates\":[77.0,12.0]}").isEmpty());
        assertTrue(WardCentroid.of("not json").isEmpty());
        Optional<double[]> outOfRange = WardCentroid.of(
                "{\"type\":\"Polygon\",\"coordinates\":[[[500,500],[501,500],[501,501],[500,500]]]}");
        assertTrue(outOfRange.isEmpty());
    }
}
