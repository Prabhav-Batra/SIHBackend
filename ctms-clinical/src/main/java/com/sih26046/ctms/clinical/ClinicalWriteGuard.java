package com.sih26046.ctms.clinical;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The precondition for recording anything clinical about a participant.
 *
 * <p>Three conditions, all of which must hold, and all of which are about lawful basis rather
 * than about permissions:
 *
 * <ul>
 *   <li><b>The participant has not withdrawn (§20.3).</b> Withdrawal stops collection.
 *   <li><b>An ACTIVE consent exists (§8.13).</b> Consent is the lawful basis for the data;
 *       withdrawing consent and withdrawing from the trial are separate acts and either one
 *       ends collection.
 *   <li><b>The trial's lifecycle permits data entry (§20.2).</b> Note this is
 *       {@code allowsDataEntry}, which is true for SUSPENDED: suspension halts new enrolment
 *       while participants already enrolled must still be followed up safely.
 * </ul>
 *
 * <p>Checked centrally rather than in each controller because the failure mode of forgetting
 * it is data recorded without permission — which no later review can undo.
 */
@Service
public class ClinicalWriteGuard {

    private final ParticipantRepository participants;
    private final ConsentRepository consents;

    public ClinicalWriteGuard(ParticipantRepository participants, ConsentRepository consents) {
        this.participants = participants;
        this.consents = consents;
    }

    /** @throws ClinicalWriteNotPermittedException if collection is not currently lawful */
    @Transactional(readOnly = true)
    public ParticipantEntity requireCollectable(UUID participantId) {
        ParticipantEntity participant =
                participants
                        .findById(participantId)
                        .orElseThrow(
                                () ->
                                        new ClinicalWriteNotPermittedException(
                                                "The participant is not in scope or does not"
                                                        + " exist"));

        if (participant.isWithdrawn()) {
            throw new ClinicalWriteNotPermittedException(
                    "The participant has withdrawn; no further data may be collected");
        }

        consents.findFirstByParticipantIdAndStatus(participantId, "ACTIVE")
                .orElseThrow(
                        () ->
                                new ClinicalWriteNotPermittedException(
                                        "The participant has no active consent"));

        String trialStatus =
                participants
                        .trialStatus(participant.getTrialId())
                        .orElseThrow(
                                () ->
                                        new ClinicalWriteNotPermittedException(
                                                "The trial is not in scope"));
        if (!"ACTIVE".equals(trialStatus) && !"SUSPENDED".equals(trialStatus)) {
            throw new ClinicalWriteNotPermittedException(
                    "A trial in state " + trialStatus + " does not accept new data");
        }

        return participant;
    }

    /** Resolves a visit to its participant, then applies the same rules. */
    @Transactional(readOnly = true)
    public ParticipantEntity requireCollectableForVisit(UUID visitId, VisitRepository visits) {
        VisitEntity visit =
                visits.findById(visitId)
                        .orElseThrow(
                                () ->
                                        new ClinicalWriteNotPermittedException(
                                                "The visit is not in scope or does not exist"));
        return requireCollectable(visit.getParticipantId());
    }

    /** Collection is not currently lawful for this participant. */
    public static class ClinicalWriteNotPermittedException extends RuntimeException {
        public ClinicalWriteNotPermittedException(String message) {
            super(message);
        }
    }
}
