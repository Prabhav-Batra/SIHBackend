package com.sih26046.ctms.documents;

/** An operation the document's current state does not allow. Maps to 409. */
public class IllegalDocumentStateException extends RuntimeException {

    public IllegalDocumentStateException(String message) {
        super(message);
    }
}
