package com.sih26046.ctms.documents;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Where document bytes live (§16.9, ADR-005).
 *
 * <p>{@link #open} exists for the scanner: §16.6 scans asynchronously, so by the time a file is
 * examined the request that uploaded it is long finished and the bytes must be re-read from
 * wherever they were put.
 */
public interface StorageBackend {

    /**
     * Stores a file.
     *
     * @param objectKey caller-chosen key, unique within the deployment's namespace
     * @param resourceType {@code image}, {@code raw} or {@code video} — Cloudinary's taxonomy,
     *     which {@code ck_documents_resource_type} pins, and which other backends may ignore
     */
    StoredObject put(String objectKey, String resourceType, String contentType, Path source)
            throws IOException;

    InputStream open(String publicId, String resourceType) throws IOException;

    boolean exists(String publicId, String resourceType);

    /**
     * Removes the bytes.
     *
     * <p>Must not throw when the object is already gone: both callers — the upload's exception
     * path and quarantine — may race a previous delete, and failing there would turn cleanup
     * into a second fault.
     */
    void delete(String publicId, String resourceType);

    /**
     * A URL that grants access to these bytes until it expires.
     *
     * <p>Minted per request and never stored or cached (§12.2): a cached signed URL outlives
     * the authorization that produced it, which is the whole property this design exists to
     * avoid. The URL is itself the credential, so it carries no session and must be short.
     *
     * @param fileName what the browser should call the download; it does not affect access
     */
    URI signedDownloadUrl(String publicId, String resourceType, Duration ttl, String fileName);
}
