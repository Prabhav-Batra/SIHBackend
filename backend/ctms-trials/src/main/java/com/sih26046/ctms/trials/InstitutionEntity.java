package com.sih26046.ctms.trials;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A hospital, medical college or research centre (§8.7).
 *
 * <p>{@code location} is deliberately not mapped. It is a generated column derived from
 * latitude and longitude, so writing it is impossible and reading it needs a JTS/PostGIS type
 * that only the GIS work in B7 has a use for. Mapping it now would pull in Hibernate Spatial
 * for a field nothing reads.
 */
@Entity
@Table(name = "institutions")
public class InstitutionEntity {

    @Id private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "institution_type", nullable = false)
    private String institutionType;

    @Column(name = "address_line")
    private String addressLine;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String country;

    @Column(name = "postal_code")
    private String postalCode;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Column(name = "has_ethics_committee", nullable = false)
    private boolean hasEthicsCommittee;

    @Column(nullable = false)
    private String status;

    /**
     * §8.7 defines no {@code version} column for this table, so there is no counter to use as
     * an ETag. {@code updated_at} is maintained by trigger on every write and serves the same
     * purpose: it changes whenever the row does, which is all a validator needs.
     */
    // @Generated makes Hibernate re-read the column after every insert and update. Without it
    // the in-memory entity keeps a null updated_at after a save, so the ETag returned by a
    // create would be a placeholder that never matches the one a subsequent GET computes — and
    // the client's very first conditional write would 409.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected InstitutionEntity() {} // JPA

    public InstitutionEntity(
            UUID id,
            String name,
            String institutionType,
            String city,
            String state,
            BigDecimal latitude,
            BigDecimal longitude) {
        this.id = id;
        this.name = name;
        this.institutionType = institutionType;
        this.city = city;
        this.state = state;
        this.country = "India";
        this.status = "ACTIVE";
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public String getInstitutionType() {
        return institutionType;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getCountry() {
        return country;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public boolean isHasEthicsCommittee() {
        return hasEthicsCommittee;
    }

    public String getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void amend(String name, String city, String state, String addressLine, String postalCode) {
        if (name != null) {
            this.name = name;
        }
        if (city != null) {
            this.city = city;
        }
        if (state != null) {
            this.state = state;
        }
        if (addressLine != null) {
            this.addressLine = addressLine;
        }
        if (postalCode != null) {
            this.postalCode = postalCode;
        }
    }

    void moveTo(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
