package com.sih26046.ctms.trials;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** §20.2 — the trial lifecycle. */
class TrialStatusTest {

    @Test
    void followsTheApprovalPath() {
        assertThat(TrialStatus.DRAFT.canTransitionTo(TrialStatus.PENDING_ETHICS)).isTrue();
        assertThat(TrialStatus.PENDING_ETHICS.canTransitionTo(TrialStatus.APPROVED)).isTrue();
        assertThat(TrialStatus.APPROVED.canTransitionTo(TrialStatus.ACTIVE)).isTrue();
        assertThat(TrialStatus.ACTIVE.canTransitionTo(TrialStatus.COMPLETED)).isTrue();
        assertThat(TrialStatus.COMPLETED.canTransitionTo(TrialStatus.ARCHIVED)).isTrue();
    }

    @Test
    void aTrialCannotSkipEthics() {
        // The transition that matters most: enrolling participants into a trial no committee
        // has approved is the failure this state machine exists to prevent.
        assertThat(TrialStatus.DRAFT.canTransitionTo(TrialStatus.ACTIVE)).isFalse();
        assertThat(TrialStatus.PENDING_ETHICS.canTransitionTo(TrialStatus.ACTIVE)).isFalse();
    }

    @Test
    void suspensionIsReversible() {
        assertThat(TrialStatus.ACTIVE.canTransitionTo(TrialStatus.SUSPENDED)).isTrue();
        assertThat(TrialStatus.SUSPENDED.canTransitionTo(TrialStatus.ACTIVE)).isTrue();
        assertThat(TrialStatus.SUSPENDED.canTransitionTo(TrialStatus.TERMINATED)).isTrue();
    }

    @Test
    void archivedIsTerminal() {
        assertThat(Arrays.stream(TrialStatus.values()).noneMatch(TrialStatus.ARCHIVED::canTransitionTo))
                .isTrue();
    }

    @Test
    void aCompletedTrialCannotRestart() {
        assertThat(TrialStatus.COMPLETED.canTransitionTo(TrialStatus.ACTIVE)).isFalse();
        assertThat(TrialStatus.TERMINATED.canTransitionTo(TrialStatus.ACTIVE)).isFalse();
    }

    @Test
    void onlyAnActiveTrialEnrols() {
        for (TrialStatus status : TrialStatus.values()) {
            assertThat(status.allowsEnrollment())
                    .as("enrolment in %s", status)
                    .isEqualTo(status == TrialStatus.ACTIVE);
        }
    }

    @Test
    void aSuspendedTrialStillAcceptsFollowUpData() {
        // §20.2, deliberate: suspension stops new enrolment, but participants already
        // enrolled must still be followed up safely. Blocking data entry here would make a
        // safety suspension itself a safety risk.
        assertThat(TrialStatus.SUSPENDED.allowsEnrollment()).isFalse();
        assertThat(TrialStatus.SUSPENDED.allowsDataEntry()).isTrue();
    }

    @Test
    void aFinishedTrialAcceptsCorrectionsButNotNewData() {
        assertThat(TrialStatus.COMPLETED.allowsDataEntry()).isFalse();
        assertThat(TrialStatus.COMPLETED.allowsCorrections()).isTrue();
        assertThat(TrialStatus.TERMINATED.allowsCorrections()).isTrue();
    }

    @Test
    void anArchivedTrialIsReadOnly() {
        assertThat(TrialStatus.ARCHIVED.allowsDataEntry()).isFalse();
        assertThat(TrialStatus.ARCHIVED.allowsCorrections()).isFalse();
    }
}
