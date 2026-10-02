package com.jannetai.backend.service.complaint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * V33 incident-location review flags. Pure functions (no Spring, no clock of
 * its own) so the rules are unit-tested directly. Every flag is a WARNING for
 * staff - none of them blocks or changes the location the citizen confirmed.
 *
 * <ul>
 *   <li>{@link #STALE_PHOTO} - the photo was taken more than
 *       {@code app.location.stale-photo-days} before it was reported.</li>
 *   <li>{@link #LOW_ACCURACY} - the detected GPS fix was less precise than
 *       {@code app.location.low-accuracy-meters}.</li>
 *   <li>{@link #PIN_MOVED_FAR} - the confirmed pin is more than
 *       {@code app.location.pin-moved-far-meters} from the detected point.</li>
 *   <li>{@link #NO_PHOTO_LOCATION} - no location came with the photo (no
 *       capture GPS, no EXIF): the point was placed by hand, chosen as a ward,
 *       or is an older client's submission-time GPS.</li>
 *   <li>{@link #OUT_OF_JURISDICTION} - outside the municipal area (SRS 15.5).</li>
 * </ul>
 */
public final class LocationFlags {

    public static final String STALE_PHOTO = "STALE_PHOTO";
    public static final String LOW_ACCURACY = "LOW_ACCURACY";
    public static final String PIN_MOVED_FAR = "PIN_MOVED_FAR";
    public static final String NO_PHOTO_LOCATION = "NO_PHOTO_LOCATION";
    public static final String OUT_OF_JURISDICTION = "OUT_OF_JURISDICTION";

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private LocationFlags() {
    }

    /** Configurable thresholds (application.yml app.location.*). */
    public record Thresholds(int stalePhotoDays, double lowAccuracyMeters, double pinMovedFarMeters) {
        public static final Thresholds DEFAULTS = new Thresholds(7, 50, 200);
    }

    /**
     * @param incidentLat/incidentLng the confirmed (stored) point
     * @param detectedLat/detectedLng automatically detected point, or null
     * @param accuracyMeters device accuracy of the detected fix, or null
     * @param capturedAtUtc when the photo was taken (UTC), or null
     * @param nowUtc the submission time (UTC)
     */
    public static List<String> compute(BigDecimal incidentLat, BigDecimal incidentLng,
                                       BigDecimal detectedLat, BigDecimal detectedLng,
                                       BigDecimal accuracyMeters, LocalDateTime capturedAtUtc,
                                       boolean outOfJurisdiction, LocalDateTime nowUtc,
                                       Thresholds thresholds) {
        List<String> flags = new ArrayList<>();
        if (capturedAtUtc != null && capturedAtUtc.isBefore(nowUtc.minusDays(thresholds.stalePhotoDays()))) {
            flags.add(STALE_PHOTO);
        }
        if (accuracyMeters != null && accuracyMeters.doubleValue() > thresholds.lowAccuracyMeters()) {
            flags.add(LOW_ACCURACY);
        }
        if (detectedLat == null || detectedLng == null) {
            flags.add(NO_PHOTO_LOCATION);
        } else if (incidentLat != null && incidentLng != null
                && distanceMeters(detectedLat.doubleValue(), detectedLng.doubleValue(),
                incidentLat.doubleValue(), incidentLng.doubleValue()) > thresholds.pinMovedFarMeters()) {
            flags.add(PIN_MOVED_FAR);
        }
        if (outOfJurisdiction) {
            flags.add(OUT_OF_JURISDICTION);
        }
        return flags;
    }

    /** Stored form: comma-separated, or null when there are no flags. */
    public static String join(List<String> flags) {
        return flags == null || flags.isEmpty() ? null : String.join(",", flags);
    }

    /** Parses the stored form; never null. */
    public static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Great-circle (haversine) distance in metres. */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
