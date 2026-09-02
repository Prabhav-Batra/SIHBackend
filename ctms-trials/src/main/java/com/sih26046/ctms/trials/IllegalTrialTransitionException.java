package com.sih26046.ctms.trials;

/** A lifecycle move §20.2 does not permit. */
public class IllegalTrialTransitionException extends RuntimeException {

    public IllegalTrialTransitionException(TrialStatus from, TrialStatus to) {
        super("A trial cannot move from " + from + " to " + to);
    }
}
