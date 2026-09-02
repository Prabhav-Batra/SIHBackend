package com.sih26046.ctms.documents;

import java.util.UUID;

/**
 * No document with this id is visible to the caller.
 *
 * <p>Out of scope and non-existent produce the same exception on purpose (§6.4): telling a
 * caller that a document exists but is not theirs discloses the document.
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document " + id + " not found");
    }
}
