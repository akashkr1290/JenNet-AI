package com.jannetai.backend.service.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit GAP-020 (SRS 15.11 geometry validation). The same cases (and more)
 * run in the Phase 06 pure-JDK harness. NOT EXECUTED here via Maven.
 */
class WardBoundaryValidatorTest {

    static String square(double x0, double y0, double x1, double y1) {
        return "{\"type\":\"Polygon\",\"coordinates\":[[[" + x0 + "," + y0 + "],[" + x1 + "," + y0 + "],[" + x1 + "," + y1
                + "],[" + x0 + "," + y1 + "],[" + x0 + "," + y0 + "]]]}";
    }

    static List<List<List<double[]>>> poly(String g) {
        return WardBoundaryValidator.validate(g).polygons();
    }

    @Test
    void acceptsPolygonAndFeatureAndNormalises() {
        assertThat(WardBoundaryValidator.validate(square(72.8, 19.0, 72.9, 19.1)).normalizedGeojson())
                .startsWith("{\"type\":\"Polygon\"");
        assertThat(WardBoundaryValidator.validate("{\"type\":\"Feature\",\"geometry\":" + square(0, 0, 1, 1) + "}")
                .normalizedGeojson()).startsWith("{\"type\":\"Polygon\"");
    }

    @Test
    void rejectsUndefinedOrInvalidGeometry() {
        assertThatThrownBy(() -> WardBoundaryValidator.validate("{\"type\":\"Point\",\"coordinates\":[1,2]}"))
                .hasMessageContaining("Polygon or MultiPolygon");
        assertThatThrownBy(() -> WardBoundaryValidator.validate("{\"type\":\"Polygon\",\"coordinates\":[]}"))
                .hasMessageContaining("undefined zone");
        assertThatThrownBy(() -> WardBoundaryValidator.validate("{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1]]]}"))
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> WardBoundaryValidator.validate("{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,1],[1,0],[0,1],[0,0]]]}"))
                .hasMessageContaining("crosses itself");
        assertThatThrownBy(() -> WardBoundaryValidator.validate("{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[190,0],[1,1],[0,0]]]}"))
                .hasMessageContaining("out of range");
    }

    @Test
    void overlapIsAreaNotSharedBorder() {
        var a = poly(square(0, 0, 2, 2));
        assertThat(WardBoundaryValidator.overlaps(a, poly(square(1, 1, 3, 3)))).isTrue();
        assertThat(WardBoundaryValidator.overlaps(a, poly(square(0, 0, 2, 2)))).isTrue();
        assertThat(WardBoundaryValidator.overlaps(a, poly(square(0.5, 0.5, 1, 1)))).isTrue();
        assertThat(WardBoundaryValidator.overlaps(poly(square(0, 1, 3, 2)), poly(square(1, 0, 2, 3)))).isTrue();
        assertThat(WardBoundaryValidator.overlaps(a, poly(square(2, 0, 4, 2)))).isFalse();
        assertThat(WardBoundaryValidator.overlaps(a, poly(square(2, 2, 3, 3)))).isFalse();
        assertThat(WardBoundaryValidator.overlaps(a, poly(square(5, 5, 6, 6)))).isFalse();
    }
}
