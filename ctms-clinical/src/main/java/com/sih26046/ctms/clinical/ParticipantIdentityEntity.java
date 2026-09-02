package com.sih26046.ctms.clinical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The re-identification key (§8.12). One row per participant, primary key *is* the foreign key.
 *
 * <p>Reaching this table requires the {@code participant_identity:read} permission in both the
 * application and the database policy, and reading it is an audited event (§19.3). It is never
 * joined into a clinical response.
 */
@Entity
@Table(name = "participant_identities")
public class ParticipantIdentityEntity {

    @Id
    @Column(name = "participant_id")
    private UUID participantId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String phone;

    @Column(name = "address_line")
    private String addressLine;

    private String city;

    /** Hash only — duplicate-enrolment detection without ever storing the number (§8.12). */
    @Column(name = "national_id_hash")
    private String nationalIdHash;

    protected ParticipantIdentityEntity() {} // JPA

    public ParticipantIdentityEntity(
            UUID participantId, String fullName, LocalDate dateOfBirth, String phone) {
        this.participantId = participantId;
        this.fullName = fullName;
        this.dateOfBirth = dateOfBirth;
        this.phone = phone;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public String getFullName() {
        return fullName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getPhone() {
        return phone;
    }
}
