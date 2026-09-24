package com.jannetai.backend.storage;

import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wraps an in-memory byte array (the re-encoded, EXIF-stripped output of
 * {@link ImageValidationService}) back into a {@link MultipartFile}, so
 * downstream code ({@code StorageService.store}) doesn't need a second,
 * parallel "store these raw bytes instead" code path - it always calls
 * {@code store(MultipartFile, String)}, whether the file came straight
 * off the wire or was sanitized first.
 */
final class SanitizedMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] bytes;

    SanitizedMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    @NonNull
    public byte[] getBytes() {
        return bytes;
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(@NonNull java.io.File dest) throws IOException, IllegalStateException {
        try (OutputStream out = new java.io.FileOutputStream(dest)) {
            out.write(bytes);
        }
    }
}
