package com.sih26046.ctms.documents;

import java.time.Instant;

/**
 * One object found in the storage backend's own namespace, independent of what {@code
 * documents} says about it.
 *
 * <p>The input to the orphan sweep (§16.7): {@code storedAt} is what lets it tell an orphan
 * from an upload whose transaction has not committed yet.
 */
public record StoredAsset(String publicId, String resourceType, Instant storedAt) {}
