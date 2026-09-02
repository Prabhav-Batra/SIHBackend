package com.sih26046.ctms.documents;

/**
 * A handle to bytes in the storage backend.
 *
 * <p>Deliberately generic (ADR-005). {@code documents} stores this handle rather than anything
 * Cloudinary-shaped, so replacing the backend with S3 or GCS means implementing a second
 * {@link StorageBackend} and re-uploading assets — not touching the schema or any caller.
 */
public record StoredObject(String publicId, String resourceType, Long version) {}
