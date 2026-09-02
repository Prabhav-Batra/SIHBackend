package com.sih26046.ctms.documents;

/**
 * What a scanner concluded.
 *
 * <p>There is no {@code ERROR} member. A scanner that could not reach a conclusion has not
 * produced a verdict, so it throws instead — which lets the job queue's backoff and
 * dead-lettering handle it, and keeps "we do not know" from being stored as if it were an
 * answer.
 */
public enum ScanVerdict {
    CLEAN,
    INFECTED
}
