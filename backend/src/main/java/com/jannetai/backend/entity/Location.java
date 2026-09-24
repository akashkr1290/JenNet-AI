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
 * Resolved GPS/ward location for a complaint.
 * Maps onto database/migrations/V5__create_locations.sql.
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

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
