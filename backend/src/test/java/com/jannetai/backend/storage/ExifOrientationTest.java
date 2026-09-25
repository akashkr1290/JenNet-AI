package com.jannetai.backend.storage;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-058: EXIF orientation is read and applied before the metadata is stripped. */
class ExifOrientationTest {

    /** Minimal JPEG prefix: SOI + APP1 Exif with one IFD0 entry (Orientation), then EOI. */
    static byte[] jpegWithOrientation(int orientation, boolean littleEndian) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        if (littleEndian) {
            tiff.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0,
                    0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0});
        } else {
            tiff.writeBytes(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1,
                    0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0});
        }
        byte[] exif = tiff.toByteArray();
        int length = 2 + 6 + exif.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length,
                'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(exif);
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    @Test
    void readsOrientationInBothByteOrders() {
        assertThat(ExifOrientation.read(jpegWithOrientation(6, true))).isEqualTo(6);
        assertThat(ExifOrientation.read(jpegWithOrientation(8, false))).isEqualTo(8);
    }

    @Test
    void missingOrGarbageMetadataMeansNormalAndNeverThrows() {
        assertThat(ExifOrientation.read(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9})).isEqualTo(1);
        assertThat(ExifOrientation.read(new byte[]{1, 2, 3})).isEqualTo(1);
        assertThat(ExifOrientation.read(null)).isEqualTo(1);
    }

    @Test
    void rotate90ClockwiseTurnsALandscapeSensorImageIntoPortrait() {
        BufferedImage stored = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        stored.setRGB(0, 0, 0xFF0000); // top-left marker

        BufferedImage shown = ExifOrientation.apply(stored, 6);

        assertThat(shown.getWidth()).isEqualTo(2);
        assertThat(shown.getHeight()).isEqualTo(4);
        assertThat(shown.getRGB(1, 0) & 0xFFFFFF).isEqualTo(0xFF0000); // now top-right
    }
}
