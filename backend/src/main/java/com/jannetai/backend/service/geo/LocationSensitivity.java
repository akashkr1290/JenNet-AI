package com.jannetai.backend.service.geo;

import java.util.List;

/**
 * Audit GAP-033 (SRS 15.8 location-sensitivity weighting): which sensitive-place
 * categories a point lies within. A zone is a circle (centre + radius);
 * distance is great-circle (haversine). Pure JDK.
 */
public final class LocationSensitivity {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** One zone; {@code type} is SCHOOL, HOSPITAL or HIGH_TRAFFIC_ROAD. */
    public record Zone(String type, double latitude, double longitude, double radiusMeters) {
    }

    public record Flags(boolean nearSchool, boolean nearHospital, boolean highTrafficRoad) {
        public static final Flags NONE = new Flags(false, false, false);
    }

    private LocationSensitivity() {
    }

    public static Flags flagsFor(double latitude, double longitude, List<Zone> zones) {
        boolean school = false;
        boolean hospital = false;
        boolean road = false;
        for (Zone z : zones) {
            if (distanceMeters(latitude, longitude, z.latitude(), z.longitude()) > z.radiusMeters()) {
                continue;
            }
            switch (z.type()) {
                case "SCHOOL" -> school = true;
                case "HOSPITAL" -> hospital = true;
                case "HIGH_TRAFFIC_ROAD" -> road = true;
                default -> { /* unknown type: ignored */ }
            }
        }
        return new Flags(school, hospital, road);
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
