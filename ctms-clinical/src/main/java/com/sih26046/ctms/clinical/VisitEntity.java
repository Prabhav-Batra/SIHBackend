package com.sih26046.ctms.clinical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

/** A protocol visit (§8.14). */
@Entity
@Table(name = "visits")
public class VisitEntity {

    @Id private UUID id;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "visit_name", nullable = false)
    private String visitName;

    @Column(name = "visit_number", nullable = false)
    private int visitNumber;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "actual_date")
    private LocalDate actualDate;

    @Column(nullable = false)
    private String status;

    @Column(name = "performed_by")
    private UUID performedBy;

    private String notes;

    @Version
    @Column(nullable = false)
    private int version;

    protected VisitEntity() {} // JPA

    public VisitEntity(
            UUID id, UUID participantId, String visitName, int visitNumber, LocalDate scheduled) {
        this.id = id;
        this.participantId = participantId;
        this.visitName = visitName;
        this.visitNumber = visitNumber;
        this.scheduledDate = scheduled;
        this.status = "SCHEDULED";
    }

    public UUID getId() {
        return id;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public String getVisitName() {
        return visitName;
    }

    public int getVisitNumber() {
        return visitNumber;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public String getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }
}
