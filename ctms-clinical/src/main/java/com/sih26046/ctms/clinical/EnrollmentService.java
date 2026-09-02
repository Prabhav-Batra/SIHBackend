package com.sih26046.ctms.clinical;

import com.sih26046.ctms.security.CurrentUser;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Participant enrolment (§14.6) — the platform's one genuinely multi-table write.
 *
 * <p>Five tables move together: the participant, their identity, their consent, and the
 * enrolment counters on the trial and the site. Any partial application leaves the platform
 * describing a trial that does not exist — a counter without a participant, or worse, a
 * participant with no consent on record and therefore no lawful basis for the clinical data
 * about to be collected against them. The transaction boundary is the whole point of this
 * class, which is why it is a service rather than logic spread across a controller.
 */
@Service
public class EnrollmentService {

    private final ParticipantRepository participants;
    private final ParticipantIdentityRepository identities;
    private final ConsentRepository consents;

    public EnrollmentService(
            ParticipantRepository participants,
            ParticipantIdentityRepository identities,
            ConsentRepository consents) {
        this.participants = participants;
        this.identities = identities;
        this.consents = consents;
    }

    /**
     * @throws TrialNotEnrollingException if the trial's lifecycle state forbids enrolment
     */
    @Transactional
    public ParticipantEntity enrol(EnrollmentRequest request, CurrentUser actor) {
        String status =
                participants
                        .trialStatus(request.trialId())
                        .orElseThrow(() -> new TrialNotEnrollingException("unknown"));

        // §20.2 — only an ACTIVE trial enrols. A DRAFT or PENDING_ETHICS trial has no ethics
        // approval, so enrolling into one is precisely the failure the lifecycle prevents.
        if (!"ACTIVE".equals(status)) {
            throw new TrialNotEnrollingException(status);
        }

        ParticipantEntity participant =
                participants.save(
                        new ParticipantEntity(
                                UUID.randomUUID(),
                                request.trialId(),
                                request.trialSiteId(),
                                request.subjectCode(),
                                request.dateOfBirthYear(),
                                request.sex(),
                                actor.userId()));

        identities.save(
                new ParticipantIdentityEntity(
                        participant.getId(),
                        request.identity().fullName(),
                        request.identity().dateOfBirth(),
                        request.identity().phone()));

        consents.save(
                new ConsentEntity(
                        UUID.randomUUID(),
                        participant.getId(),
                        request.consent().consentVersion(),
                        request.consent().consentMethod(),
                        actor.userId()));

        // Last, and as statements rather than reads: the CHECK constraint on the counter is
        // what stops enrolment past target, and it must fail the transaction that created the
        // participant rather than a later one.
        participants.incrementTrialEnrollment(request.trialId());
        participants.incrementSiteEnrollment(request.trialSiteId());
        participants.flush();

        return participant;
    }

    /** A lifecycle state in which enrolment is not permitted. */
    public static class TrialNotEnrollingException extends RuntimeException {
        public TrialNotEnrollingException(String status) {
            super("A trial in state " + status + " does not enrol participants");
        }
    }
}
