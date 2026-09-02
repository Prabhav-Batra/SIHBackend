package com.sih26046.ctms.clinical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Informed consent (§8.13). The lawful basis for every clinical row that follows. */
@Entity
@Table(name = "consents")
public class ConsentEntity {

    @Id private UUID id;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "consent_version", nullable = false)
    private String consentVersion;

    @Column(name = "consent_type", nullable = false)
    private String consentType;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "consent_method", nullable = false)
    private String consentMethod;

    @Column(name = "witness_name")
    private String witnessName;

    @Column(name = "obtained_by", nullable = false)
    private UUID obtainedBy;

    @Column(nullable = false)
    private String status;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawal_reason")
    private String withdrawalReason;

    protected ConsentEntity() {} // JPA

    public ConsentEntity(
            UUID id,
            UUID participantId,
            String consentVersion,
            String consentMethod,
            UUID obtainedBy) {
        this.id = id;
        this.participantId = participantId;
        this.consentVersion = consentVersion;
        this.consentType = "INITIAL";
        this.consentedAt = Instant.now();
        this.consentMethod = consentMethod;
        this.obtainedBy = obtainedBy;
        this.status = "ACTIVE";
    }

    public UUID getId() {
        return id;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public String getStatus() {
        return status;
    }

    void withdraw(String reason) {
        this.status = "WITHDRAWN";
        this.withdrawnAt = Instant.now();
        this.withdrawalReason = reason;
    }
}
