package com.sih26046.ctms.clinical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A concomitant, study or rescue medication (§8.16). */
@Entity
@Table(name = "medications")
public class MedicationEntity {

    @Id private UUID id;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "medication_name", nullable = false)
    private String medicationName;

    /** Study drug, concomitant or rescue — the distinction causality assessment rests on. */
    @Column(name = "medication_type", nullable = false)
    private String medicationType;

    private BigDecimal dose;

    @Column(name = "dose_unit")
    private String doseUnit;

    private String frequency;

    private String route;

    private String indication;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_ongoing", nullable = false)
    private boolean ongoing;

    @Version
    @Column(nullable = false)
    private int version;

    protected MedicationEntity() {} // JPA

    public MedicationEntity(
            UUID id,
            UUID participantId,
            String medicationName,
            String medicationType,
            BigDecimal dose,
            String route,
            LocalDate startDate) {
        this.id = id;
        this.participantId = participantId;
        this.medicationName = medicationName;
        this.medicationType = medicationType;
        this.dose = dose;
        this.route = route;
        this.startDate = startDate;
        this.ongoing = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public String getMedicationName() {
        return medicationName;
    }

    public String getMedicationType() {
        return medicationType;
    }

    public BigDecimal getDose() {
        return dose;
    }

    public String getRoute() {
        return route;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public boolean isOngoing() {
        return ongoing;
    }

    public int getVersion() {
        return version;
    }
}
