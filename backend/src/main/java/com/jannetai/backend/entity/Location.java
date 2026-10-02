package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.LocationSource;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The complaint's INCIDENT location - where the problem is, which is not
 * necessarily where the citizen was when submitting. Maps onto
 * database/migrations/V5__create_locations.sql (+ V23, V33).
 */
@Entity
@Table(name = "locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id")
    private Ward ward;

    @Column(name = "formatted_address", length = 300)
    private String formattedAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private LocationSource source;

    @Column(name = "out_of_jurisdiction", nullable = false)
    private Boolean outOfJurisdiction;

    /** V33: device-reported GPS accuracy radius (metres) of the detected fix, when known. */
    @Column(name = "accuracy_m", precision = 9, scale = 1)
    private BigDecimal accuracyMeters;

    /** V33: when the photo was taken (UTC), when known. */
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    /**
     * V33: the automatically detected point (capture GPS or photo EXIF). Kept
     * even when the citizen moved the pin, so staff can compare both.
     */
    @Column(name = "detected_latitude", precision = 9, scale = 6)
    private BigDecimal detectedLatitude;

    @Column(name = "detected_longitude", precision = 9, scale = 6)
    private BigDecimal detectedLongitude;

    /** V33: the citizen confirmed this point on the map (false for older clients). */
    @Builder.Default
    @Column(name = "confirmed_by_citizen", nullable = false)
    private Boolean confirmedByCitizen = Boolean.FALSE;

    /** V33: comma-separated review flags (see LocationFlags) - warnings only, never blocking. */
    @Column(name = "flags", length = 200)
    private String flags;

    /**
     * V33: distance (metres) between this point and where the citizen was when
     * submitting, when known. The citizen's own coordinates are never stored.
     */
    @Column(name = "submission_distance_m", precision = 10, scale = 1)
    private BigDecimal submissionDistanceMeters;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
