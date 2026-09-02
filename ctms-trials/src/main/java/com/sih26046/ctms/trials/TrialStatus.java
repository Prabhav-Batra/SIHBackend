package com.sih26046.ctms.trials;

import java.util.EnumSet;
import java.util.Set;

/**
 * The trial lifecycle (§20.2).
 *
 * <p>Encoded as a state machine rather than a free-text column with a CHECK constraint,
 * because the constraint can say which values are legal but not which *transitions* are. The
 * transition that matters is DRAFT to ACTIVE: enrolling participants into a trial no ethics
 * committee has approved is the failure this exists to prevent.
 */
public enum TrialStatus {
    DRAFT,
    PENDING_ETHICS,
    APPROVED,
    REJECTED,
    ACTIVE,
    SUSPENDED,
    COMPLETED,
    TERMINATED,
    ARCHIVED;

    private static final Set<TrialStatus> ENROLLING = EnumSet.of(ACTIVE);

    /** §20.2: suspension halts new enrolment while follow-up on enrolled participants goes on. */
    private static final Set<TrialStatus> ACCEPTS_NEW_DATA = EnumSet.of(ACTIVE, SUSPENDED);

    private static final Set<TrialStatus> ACCEPTS_CORRECTIONS =
            EnumSet.of(ACTIVE, SUSPENDED, COMPLETED, TERMINATED);

    public boolean canTransitionTo(TrialStatus next) {
        return switch (this) {
            case DRAFT -> next == PENDING_ETHICS;
            case PENDING_ETHICS -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == ACTIVE;
            case ACTIVE -> next == SUSPENDED || next == COMPLETED;
            case SUSPENDED -> next == ACTIVE || next == TERMINATED;
            case COMPLETED, TERMINATED -> next == ARCHIVED;
            // §20.2 draws no outgoing arrow from REJECTED, so a rejected trial is terminal
            // here. In practice a committee rejection is usually followed by an amended
            // resubmission; if that is wanted it is a documented change to the diagram, not
            // an assumption to make silently in code.
            case REJECTED, ARCHIVED -> false;
        };
    }

    /** Whether new participants may be enrolled. */
    public boolean allowsEnrollment() {
        return ENROLLING.contains(this);
    }

    /** Whether new clinical data may be recorded. */
    public boolean allowsDataEntry() {
        return ACCEPTS_NEW_DATA.contains(this);
    }

    /** Whether existing records may still be corrected (§20.2: "corrections only"). */
    public boolean allowsCorrections() {
        return ACCEPTS_CORRECTIONS.contains(this);
    }
}
