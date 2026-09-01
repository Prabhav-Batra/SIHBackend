package com.sih26046.ctms.security;

/**
 * Authentication failed.
 *
 * <p>Deliberately one exception for every cause — unknown account, wrong password, inactive,
 * locked. §18.5 requires the response be "Invalid email or password" regardless, because a
 * differing response is an account-enumeration oracle.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
