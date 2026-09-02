package com.sih26046.ctms.ethics;

import java.util.Set;

/**
 * The statuses of {@code ck_ethics_submissions_status}, and which of them are decisions.
 *
 * <p>The distinction matters because the decision endpoint must not become a general status
 * setter: {@code SUBMITTED} and {@code WITHDRAWN} are reached by submitting and withdrawing,
 * and a committee that could set either through {@code /decision} could un-submit an
 * application or retract it on the applicant's behalf.
 */
public final class EthicsDecision {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String UNDER_REVIEW = "UNDER_REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String APPROVED_WITH_CONDITIONS = "APPROVED_WITH_CONDITIONS";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String DEFERRED = "DEFERRED";

    private static final Set<String> DECISIONS =
            Set.of(APPROVED, APPROVED_WITH_CONDITIONS, REJECTED, DEFERRED);

    private EthicsDecision() {}

    public static boolean isDecision(String status) {
        return DECISIONS.contains(status);
    }

    /** An approval qualified by conditions that records none is not an auditable decision. */
    public static boolean requiresConditions(String status) {
        return APPROVED_WITH_CONDITIONS.equals(status);
    }
}
