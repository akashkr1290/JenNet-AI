package com.jannetai.backend.storage;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit GAP-031 (SRS 15.5 EXIF GPS fallback). The Phase 06 pure-JDK harness
 * also checks this parser against JPEGs written by Pillow (little-endian).
 * NOT EXECUTED here via Maven.
 */
class ExifGpsTest {

    /** Big-endian EXIF APP1 with 28 36 50.04 N, 77 12 32.4 E, then a real JPEG body. */
    static byte[] jpegWithGps(char latRef, char lngRef) throws Exception {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        w(t, 'M', 'M', 0, 42); u32(t, 8);
        u16(t, 1); u16(t, 0x8825); u16(t, 4); u32(t, 1); u32(t, 26); u32(t, 0);
        u16(t, 4);
        u16(t, 1); u16(t, 2); u32(t, 2); w(t, latRef, 0, 0, 0);
        u16(t, 2); u16(t, 5); u32(t, 3); u32(t, 80);
        u16(t, 3); u16(t, 2); u32(t, 2); w(t, lngRef, 0, 0, 0);
        u16(t, 4); u16(t, 5); u32(t, 3); u32(t, 104);
        u32(t, 0);
        u32(t, 28); u32(t, 1); u32(t, 36); u32(t, 1); u32(t, 5004); u32(t, 100);
        u32(t, 77); u32(t, 1); u32(t, 12); u32(t, 1); u32(t, 324); u32(t, 10);
        byte[] tiff = t.toByteArray();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", body);
        byte[] plain = body.toByteArray();

        ByteArrayOutputStream j = new ByteArrayOutputStream();
        w(j, 0xFF, 0xD8, 0xFF, 0xE1);
        int len = 2 + 6 + tiff.length;
        w(j, len >> 8, len & 0xFF);
        w(j, 'E', 'x', 'i', 'f', 0, 0);
        j.writeBytes(tiff);
        j.write(plain, 2, plain.length - 2); // the encoder's own segments after SOI
        return j.toByteArray();
    }

    @Test
    void readsNorthEastCoordinates() throws Exception {
        var gps = ExifGps.read(jpegWithGps('N', 'E')).orElseThrow();
        assertThat(gps.latitude().toPlainString()).isEqualTo("28.613900");
        assertThat(gps.longitude().toPlainString()).isEqualTo("77.209000");
    }

    @Test
    void southAndWestAreNegative() throws Exception {
        var gps = ExifGps.read(jpegWithGps('S', 'W')).orElseThrow();
        assertThat(gps.latitude().signum()).isNegative();
        assertThat(gps.longitude().signum()).isNegative();
    }

    @Test
    void invalidReferenceOrMissingMetadataGivesEmptyAndNeverThrows() throws Exception {
        assertThat(ExifGps.read(jpegWithGps('X', 'E'))).isEmpty();
        assertThat(ExifGps.read(null)).isEmpty();
        assertThat(ExifGps.read(new byte[]{1, 2, 3})).isEmpty();
        byte[] full = jpegWithGps('N', 'E');
        for (int cut = 0; cut < 140; cut++) { // the APP1 segment (with the GPS data) ends at byte 140
            assertThat(ExifGps.read(Arrays.copyOf(full, cut))).isEmpty();
        }
    }

    @Test
    void theImageStillDecodesAndOrientationReaderSharesTheWalker() throws Exception {
        byte[] jpeg = jpegWithGps('N', 'E');
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(jpeg))).isNotNull();
        assertThat(ExifOrientation.read(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    private static void w(ByteArrayOutputStream o, int... b) {
        for (int x : b) {
            o.write(x);
        }
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write(v >> 8);
        o.write(v & 0xFF);
    }

    private static void u32(ByteArrayOutputStream o, long v) {
        o.write((int) (v >>> 24) & 0xFF);
        o.write((int) (v >>> 16) & 0xFF);
        o.write((int) (v >>> 8) & 0xFF);
        o.write((int) v & 0xFF);
    }
}
