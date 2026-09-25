package com.jannetai.backend.storage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Audit GAP-031 (SRS 15.5 "EXIF GPS extraction fallback"): reads the GPS
 * position a camera stored in a JPEG's EXIF block. Must run on the ORIGINAL
 * upload - {@link ImageValidationService} re-encodes the image and strips all
 * metadata afterwards (privacy), so the position is only available before that.
 *
 * Pure JDK, reusing {@link ExifOrientation}'s APP1/TIFF walker. Reads the GPS
 * IFD (IFD0 tag 0x8825) tags GPSLatitudeRef (1), GPSLatitude (2),
 * GPSLongitudeRef (3) and GPSLongitude (4): three RATIONALs each (degrees,
 * minutes, seconds). Returns empty - never throws - when the tags are absent,
 * malformed, out of range, or exactly 0,0 (a common "no fix" placeholder).
 *
 * Scope note: only JPEG is read. Phone cameras write JPEG (HEIC is not an
 * accepted upload type); PNG/WEBP EXIF is rare and not parsed.
 */
public final class ExifGps {

    /** Degrees with the same precision as locations.latitude/longitude (DECIMAL(9,6)). */
    public record Coordinates(BigDecimal latitude, BigDecimal longitude) {
    }

    private static final int GPS_IFD_POINTER = 0x8825;
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;

    private ExifGps() {
    }

    public static Optional<Coordinates> read(byte[] jpeg) {
        try {
            int[] tiff = ExifOrientation.locateExifTiff(jpeg);
            if (tiff == null) {
                return Optional.empty();
            }
            return readTiff(jpeg, tiff[0], tiff[1]);
        } catch (RuntimeException e) {
            return Optional.empty(); // malformed metadata must never block an upload
        }
    }

    private static Optional<Coordinates> readTiff(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return Optional.empty();
        }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return Optional.empty();
        }
        int ifd0 = entryDirectory(b, tiff, end, ExifOrientation.u32(b, tiff + 4, little));
        if (ifd0 < 0) {
            return Optional.empty();
        }
        int pointerEntry = findEntry(b, ifd0, end, GPS_IFD_POINTER, little);
        if (pointerEntry < 0 || ExifOrientation.u16(b, pointerEntry + 2, little) != TYPE_LONG) {
            return Optional.empty();
        }
        int gps = entryDirectory(b, tiff, end, ExifOrientation.u32(b, pointerEntry + 8, little));
        if (gps < 0) {
            return Optional.empty();
        }
        Character latRef = asciiRef(b, findEntry(b, gps, end, 1, little), little);
        BigDecimal lat = degrees(b, tiff, end, findEntry(b, gps, end, 2, little), little);
        Character lngRef = asciiRef(b, findEntry(b, gps, end, 3, little), little);
        BigDecimal lng = degrees(b, tiff, end, findEntry(b, gps, end, 4, little), little);
        if (latRef == null || lat == null || lngRef == null || lng == null) {
            return Optional.empty();
        }
        if (latRef == 'S') {
            lat = lat.negate();
        } else if (latRef != 'N') {
            return Optional.empty();
        }
        if (lngRef == 'W') {
            lng = lng.negate();
        } else if (lngRef != 'E') {
            return Optional.empty();
        }
        lat = lat.setScale(6, RoundingMode.HALF_UP);
        lng = lng.setScale(6, RoundingMode.HALF_UP);
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            return Optional.empty();
        }
        if (lat.signum() == 0 && lng.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(new Coordinates(lat, lng));
    }

    /** Absolute offset of an IFD (its entry count), or -1 when outside the segment. */
    private static int entryDirectory(byte[] b, int tiff, int end, long relative) {
        if (relative < 8 || tiff + relative + 2 > end) {
            return -1;
        }
        return (int) (tiff + relative);
    }

    /** Absolute offset of the 12-byte entry for {@code tag}, or -1. */
    private static int findEntry(byte[] b, int dir, int end, int tag, boolean little) {
        int entries = ExifOrientation.u16(b, dir, little);
        for (int e = 0; e < entries; e++) {
            int entry = dir + 2 + e * 12;
            if (entry + 12 > end) {
                return -1;
            }
            if (ExifOrientation.u16(b, entry, little) == tag) {
                return entry;
            }
        }
        return -1;
    }

    /** GPSLatitudeRef / GPSLongitudeRef: ASCII, count 2 ("N\0"), stored inline. */
    private static Character asciiRef(byte[] b, int entry, boolean little) {
        if (entry < 0 || ExifOrientation.u16(b, entry + 2, little) != TYPE_ASCII) {
            return null;
        }
        char c = (char) (b[entry + 8] & 0xFF);
        return Character.toUpperCase(c);
    }

    /** Three RATIONALs (deg, min, sec) at the entry's offset -> decimal degrees; null when invalid. */
    private static BigDecimal degrees(byte[] b, int tiff, int end, int entry, boolean little) {
        if (entry < 0 || ExifOrientation.u16(b, entry + 2, little) != TYPE_RATIONAL
                || ExifOrientation.u32(b, entry + 4, little) != 3) {
            return null;
        }
        long offset = ExifOrientation.u32(b, entry + 8, little);
        int at = (int) (tiff + offset);
        if (offset < 8 || at + 24 > end) {
            return null;
        }
        BigDecimal deg = rational(b, at, little);
        BigDecimal min = rational(b, at + 8, little);
        BigDecimal sec = rational(b, at + 16, little);
        if (deg == null || min == null || sec == null
                || min.compareTo(BigDecimal.valueOf(60)) >= 0 || sec.compareTo(BigDecimal.valueOf(60)) >= 0) {
            return null;
        }
        MathContext mc = MathContext.DECIMAL64;
        return deg.add(min.divide(BigDecimal.valueOf(60), mc), mc).add(sec.divide(BigDecimal.valueOf(3600), mc), mc);
    }

    private static BigDecimal rational(byte[] b, int at, boolean little) {
        long numerator = ExifOrientation.u32(b, at, little);
        long denominator = ExifOrientation.u32(b, at + 4, little);
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL64);
    }
}
