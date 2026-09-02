package com.sih26046.ctms.documents;

/** An upload that failed validation (§16.5). Carries a reason that is safe to return. */
public class RejectedUploadException extends RuntimeException {

    public RejectedUploadException(String message) {
        super(message);
    }
}
