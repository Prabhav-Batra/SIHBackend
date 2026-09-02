package com.sih26046.ctms.clinical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A single clinical measurement (§8.15). */
@Entity
@Table(name = "observations")
public class ObservationEntity {

    @Id private UUID id;

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "observation_code", nullable = false)
    private String observationCode;

    @Column(name = "observation_name", nullable = false)
    private String observationName;

    @Column(nullable = false)
    private String category;

    @Column(name = "value_numeric")
    private BigDecimal valueNumeric;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    private String unit;

    /** Clinician judgement, not derived: a value in range may still be clinically abnormal. */
    @Column(name = "is_abnormal")
    private Boolean isAbnormal;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private String status;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_by")
    private UUID createdBy;

    protected ObservationEntity() {} // JPA

    public ObservationEntity(
            UUID id,
            UUID visitId,
            String code,
            String name,
            String category,
            BigDecimal valueNumeric,
            String valueText,
            Boolean valueBoolean,
            String unit,
            UUID createdBy) {
        this.id = id;
        this.visitId = visitId;
        this.observationCode = code;
        this.observationName = name;
        this.category = category;
        this.valueNumeric = valueNumeric;
        this.valueText = valueText;
        this.valueBoolean = valueBoolean;
        this.unit = unit;
        this.recordedAt = Instant.now();
        this.status = "RECORDED";
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVisitId() {
        return visitId;
    }

    public String getObservationCode() {
        return observationCode;
    }

    public String getObservationName() {
        return observationName;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getValueNumeric() {
        return valueNumeric;
    }

    public String getValueText() {
        return valueText;
    }

    public Boolean getValueBoolean() {
        return valueBoolean;
    }

    public String getUnit() {
        return unit;
    }

    public Boolean getIsAbnormal() {
        return isAbnormal;
    }

    public String getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }
}
