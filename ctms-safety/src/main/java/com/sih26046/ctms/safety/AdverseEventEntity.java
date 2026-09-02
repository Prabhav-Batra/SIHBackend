package com.sih26046.ctms.safety;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** A reported adverse event (§8.17). */
@Entity
@Table(name = "adverse_events")
public class AdverseEventEntity {

    @Id private UUID id;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    /**
     * Denormalised from the participant so cross-trial safety queries and GIS aggregates need
     * no join (§28.3).
     *
     * <p>Written by a database trigger, never by the application: a value taken from the
     * request could disagree with the participant's actual trial, and the Safety Officer's
     * whole view is built on this column. {@code @Generated} makes Hibernate read back what the
     * trigger decided rather than keeping what it sent.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "trial_id", insertable = false, updatable = false)
    private UUID trialId;

    @Column(name = "visit_id")
    private UUID visitId;

    @Column(name = "event_term", nullable = false)
    private String eventTerm;

    @Column(name = "meddra_code")
    private String meddraCode;

    /** Narrative. Never exposed to GIS or aggregates (§11.2). */
    @Column(nullable = false)
    private String description;

    @Column(name = "onset_date", nullable = false)
    private LocalDate onsetDate;

    @Column(name = "resolution_date")
    private LocalDate resolutionDate;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String seriousness;

    @Column(name = "serious_criteria", columnDefinition = "text[]")
    private String[] seriousCriteria;

    /** Set by the Safety Officer at review, never by the reporter (§8.17). */
    private String causality;

    private String outcome;

    @Column(name = "reported_by", nullable = false)
    private UUID reportedBy;

    @Column(nullable = false)
    private String status;

    @Version
    @Column(nullable = false)
    private int version;

    protected AdverseEventEntity() {} // JPA

    public AdverseEventEntity(
            UUID id,
            UUID participantId,
            UUID visitId,
            String eventTerm,
            String description,
            LocalDate onsetDate,
            String severity,
            String seriousness,
            String[] seriousCriteria,
            UUID reportedBy) {
        this.id = id;
        this.participantId = participantId;
        this.visitId = visitId;
        this.eventTerm = eventTerm;
        this.description = description;
        this.onsetDate = onsetDate;
        this.severity = severity;
        this.seriousness = seriousness;
        this.seriousCriteria = seriousCriteria;
        this.reportedBy = reportedBy;
        this.status = "REPORTED";
    }

    public UUID getId() {
        return id;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public UUID getTrialId() {
        return trialId;
    }

    public String getEventTerm() {
        return eventTerm;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getOnsetDate() {
        return onsetDate;
    }

    public String getSeverity() {
        return severity;
    }

    public String getSeriousness() {
        return seriousness;
    }

    public String[] getSeriousCriteria() {
        return seriousCriteria == null ? null : seriousCriteria.clone();
    }

    public String getCausality() {
        return causality;
    }

    public String getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    /** Records the reviewer's causality assessment and closes the event's open state. */
    void recordReview(String assessedCausality) {
        this.causality = assessedCausality;
        this.status = "REVIEWED";
    }
}
