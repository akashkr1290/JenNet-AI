package com.jannetai.backend.storage;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Audit GAP-058 (SRS 21.3 normalisation): phone cameras usually store the
 * sensor image unrotated plus an EXIF Orientation tag (0x0112). Re-encoding a
 * JPEG through ImageIO drops EXIF - so without this, portrait photos were
 * stored sideways for officers and for the AI. Pure JDK: a minimal reader for
 * the one tag we need (no new dependency) and the matching pixel transform.
 */
public final class ExifOrientation {

    /** EXIF "top-left": no transform needed. */
    public static final int NORMAL = 1;

    private ExifOrientation() {
    }

    /**
     * @return the EXIF Orientation value 1-8 of a JPEG, or {@link #NORMAL} when
     *         absent, unreadable or not a JPEG. Never throws.
     */
    public static int read(byte[] jpeg) {
        try {
            int[] tiff = locateExifTiff(jpeg);
            if (tiff != null) {
                return readTiffOrientation(jpeg, tiff[0], tiff[1]);
            }
        } catch (RuntimeException e) {
            // malformed metadata must never block an upload
        }
        return NORMAL;
    }

    /**
     * Finds the TIFF structure inside a JPEG's EXIF APP1 segment. Shared with
     * {@link ExifGps} (audit GAP-031) so both readers walk the same markers.
     *
     * @return {tiffStart, exclusiveEndOfApp1}, or null when the input is not a
     *         JPEG or has no EXIF APP1 segment before the image data. May throw
     *         on malformed input; callers catch.
     */
    static int[] locateExifTiff(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return null;
        }
        int i = 2;
        while (i + 4 <= jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) { // end of image / start of scan: no more metadata
                return null;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > jpeg.length) {
                return null;
            }
            if (marker == 0xE1 && length >= 8 && isExifHeader(jpeg, i + 4)) {
                return new int[]{i + 10, i + 2 + length};
            }
            i += 2 + length;
        }
        return null;
    }

    private static boolean isExifHeader(byte[] b, int at) {
        return b[at] == 'E' && b[at + 1] == 'x' && b[at + 2] == 'i' && b[at + 3] == 'f' && b[at + 4] == 0 && b[at + 5] == 0;
    }

    /** @param tiff start of the TIFF header, @param end exclusive end of the APP1 segment */
    private static int readTiffOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return NORMAL;
        }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return NORMAL;
        }
        long ifd0 = u32(b, tiff + 4, little);
        int dir = (int) (tiff + ifd0);
        if (ifd0 < 8 || dir + 2 > end) {
            return NORMAL;
        }
        int entries = u16(b, dir, little);
        for (int e = 0; e < entries; e++) {
            int entry = dir + 2 + e * 12;
            if (entry + 12 > end) {
                return NORMAL;
            }
            if (u16(b, entry, little) == 0x0112) {
                int type = u16(b, entry + 2, little);
                int value = type == 3 ? u16(b, entry + 8, little) : (int) u32(b, entry + 8, little);
                return value >= 1 && value <= 8 ? value : NORMAL;
            }
        }
        return NORMAL;
    }

    static int u16(byte[] b, int at, boolean little) {
        int a0 = b[at] & 0xFF;
        int a1 = b[at + 1] & 0xFF;
        return little ? (a1 << 8) | a0 : (a0 << 8) | a1;
    }

    static long u32(byte[] b, int at, boolean little) {
        long a0 = b[at] & 0xFF;
        long a1 = b[at + 1] & 0xFF;
        long a2 = b[at + 2] & 0xFF;
        long a3 = b[at + 3] & 0xFF;
        return little ? (a3 << 24) | (a2 << 16) | (a1 << 8) | a0 : (a0 << 24) | (a1 << 16) | (a2 << 8) | a3;
    }

    /**
     * Returns the image as it should be displayed for the given EXIF orientation
     * (the input itself for {@link #NORMAL} or an unknown value).
     */
    public static BufferedImage apply(BufferedImage image, int orientation) {
        if (orientation <= NORMAL || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        // Stored pixel (x, y) -> displayed pixel (x', y'), per the EXIF 2.3 definition of each value.
        // AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12.
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirror horizontal: x' = w - x
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // rotate 180
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirror vertical: y' = h - y
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // transpose: x' = y, y' = x
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // rotate 90 CW: x' = h - y, y' = x
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // transverse: x' = h - y, y' = w - x
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // rotate 90 CCW: x' = y, y' = w - x
            default -> null;
        };
        if (t == null) {
            return image;
        }
        int type = image.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : image.getType();
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, type);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
