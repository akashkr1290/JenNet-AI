package com.jannetai.backend.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Gap-backlog Patch 21 / 49 (Sep 2026 audit): real upload validation
 * beyond the declared {@code Content-Type} header (which
 * {@code ComplaintService.validatePhoto} already checks, but a header is
 * client-supplied and trivially spoofable - a file renamed/relabeled
 * ".jpg" with a completely different payload would pass that check
 * alone).
 *
 * <p>This service checks the file's real magic-byte signature, decodes it
 * as an actual image (rejecting anything that isn't - a corrupt file, a
 * polyglot payload, an executable renamed to look like an image), bounds
 * its pixel dimensions (defends against a decompression-bomb-style
 * resource exhaustion - an image whose compressed size is small but whose
 * decoded pixel dimensions are enormous), and - for JPEG/PNG, where
 * Java's built-in {@link ImageIO} can do it - re-encodes the image from
 * freshly-decoded pixel data, which strips EXIF and any other embedded
 * metadata (location, camera make/model, etc.) as a side effect, since
 * the re-encoded bytes carry only pixel data, never the original file's
 * metadata segments. Audit GAP-058: the JPEG EXIF Orientation is applied
 * to the pixels first ({@link ExifOrientation}), so rotation survives the strip.
 *
 * <p>Honest limitation: {@link ImageIO} ships no WEBP plugin by default -
 * WEBP uploads (accepted by {@code ComplaintService.ALLOWED_CONTENT_TYPES})
 * get the magic-byte + basic RIFF-container check only, not a full
 * decode/re-encode/EXIF-strip pass. Not silently skipped - logged, and
 * documented here, rather than claiming a guarantee this code doesn't
 * actually provide for that one format.
 */
@Slf4j
@Service
public class ImageValidationService {

    private static final int MAX_DIMENSION_PX = 8000; // defends against decompression-bomb-style resource exhaustion
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF_MAGIC = {0x52, 0x49, 0x46, 0x46}; // "RIFF" - WEBP's container

    /**
     * Validates the file's real bytes (not just its declared content
     * type) and, where possible, returns a sanitized replacement with
     * EXIF/metadata stripped. Throws {@link IllegalArgumentException} -
     * caught the same way as every other {@code ComplaintService}
     * validation failure - on anything that fails the real-bytes check,
     * even if the declared content type looked fine.
     */
    public MultipartFile validateAndSanitize(MultipartFile photo) {
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (IOException e) {
            throw new StorageException("Could not read uploaded file", e);
        }

        String declaredType = photo.getContentType();
        if (startsWith(bytes, JPEG_MAGIC) && "image/jpeg".equals(declaredType)) {
            return sanitizeRasterImage(photo, bytes, "jpg", "image/jpeg");
        }
        if (startsWith(bytes, PNG_MAGIC) && "image/png".equals(declaredType)) {
            return sanitizeRasterImage(photo, bytes, "png", "image/png");
        }
        if (startsWith(bytes, RIFF_MAGIC) && containsWebpFourCc(bytes) && "image/webp".equals(declaredType)) {
            log.info("WEBP upload passed magic-byte/container check; EXIF-strip/re-encode skipped "
                    + "(no built-in ImageIO WEBP codec) - see this class's Javadoc");
            requireDecodableDimensions(bytes, declaredType); // still bounds-checked via ImageIO's best-effort read, if any reader is registered
            return photo;
        }

        throw new IllegalArgumentException(
                "The uploaded file's actual content does not match a valid image of the declared type ("
                        + declaredType + ") - it may be corrupted or mislabeled");
    }

    /**
     * Audit GAP-031 (SRS 15.5 EXIF GPS fallback): the camera's GPS position
     * from the ORIGINAL upload's EXIF. Call before {@link #validateAndSanitize},
     * whose re-encode removes all metadata. Empty for non-JPEG input, missing
     * or malformed tags; never throws for bad metadata.
     */
    public java.util.Optional<ExifGps.Coordinates> readExifGps(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            return java.util.Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (IOException e) {
            return java.util.Optional.empty(); // validateAndSanitize reports the unreadable file
        }
        return startsWith(bytes, JPEG_MAGIC) ? ExifGps.read(bytes) : java.util.Optional.empty();
    }

    private MultipartFile sanitizeRasterImage(MultipartFile original, byte[] bytes, String formatName, String contentType) {
        BufferedImage image;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            image = ImageIO.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (image == null) {
            throw new IllegalArgumentException(
                    "The uploaded file could not be decoded as a valid " + formatName.toUpperCase() + " image");
        }
        if (image.getWidth() > MAX_DIMENSION_PX || image.getHeight() > MAX_DIMENSION_PX) {
            throw new IllegalArgumentException(
                    "Image dimensions (" + image.getWidth() + "x" + image.getHeight()
                            + ") exceed the " + MAX_DIMENSION_PX + "px limit");
        }

        // Audit GAP-058: apply the EXIF Orientation BEFORE re-encoding - the
        // re-encoded file carries no EXIF, so without this a portrait phone
        // photo would be stored (and shown to officers and the AI) sideways.
        if ("jpg".equals(formatName)) {
            int orientation = ExifOrientation.read(bytes);
            if (orientation != ExifOrientation.NORMAL) {
                image = ExifOrientation.apply(image, orientation);
            }
        }

        // Re-encoding from the decoded BufferedImage - not just copying
        // the original bytes back out - is what actually strips EXIF/
        // metadata: the new byte stream contains only pixel data ImageIO
        // just wrote, never the original file's metadata segments.
        ByteArrayOutputStream sanitized = new ByteArrayOutputStream();
        try {
            boolean wrote = ImageIO.write(image, formatName, sanitized);
            if (!wrote) {
                throw new IllegalArgumentException("No ImageIO writer available for " + formatName);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new SanitizedMultipartFile(
                original.getName(), original.getOriginalFilename(), contentType, sanitized.toByteArray());
    }

    /** WEBP path: ImageIO.read still works if some registered plugin (not guaranteed) can decode it; otherwise this is a no-op bounds check, honestly not a hard guarantee - see class Javadoc. */
    private void requireDecodableDimensions(byte[] bytes, String declaredType) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image != null
                    && (image.getWidth() > MAX_DIMENSION_PX || image.getHeight() > MAX_DIMENSION_PX)) {
                throw new IllegalArgumentException(
                        "Image dimensions exceed the " + MAX_DIMENSION_PX + "px limit");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }

    /** RIFF containers carry a 4-byte size field then a four-CC - WEBP's is the bytes "WEBP" at offset 8. */
    private boolean containsWebpFourCc(byte[] data) {
        if (data.length < 12) {
            return false;
        }
        return data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }
}
