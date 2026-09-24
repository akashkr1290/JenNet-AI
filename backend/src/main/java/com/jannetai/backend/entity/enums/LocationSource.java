package com.jannetai.backend.entity.enums;

/** Matches locations.source CHECK constraint (V5__create_locations.sql). */
public enum LocationSource {
    DEVICE_GPS,
    EXIF,
    MANUAL_PIN
}
