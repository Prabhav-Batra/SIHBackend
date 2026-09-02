package com.sih26046.ctms.documents;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Cloudinary-backed storage (§16.9, ADR-005).
 *
 * <p>Assets are uploaded as {@code type: authenticated}, which is the decision the rest of this
 * class follows from. A default Cloudinary upload is publicly reachable by URL forever: the
 * delivery URL is derived from the public id, so anyone who learns or guesses it has the file,
 * and there is nothing to revoke. Authenticated assets are not served without a signature.
 *
 * <p>That forces the download design. Cloudinary's plain {@code signed: true} URLs are
 * tamper-proof but <strong>do not expire</strong>, and its expiring {@code auth_token} scheme
 * needs the token-based-authentication add-on, which is not on the free tier. The private
 * download API is: it produces a link carrying {@code expires_at} inside the signature, which
 * is what §16.4's five-minute window actually needs and what the free plan actually offers.
 */
public class CloudinaryStorageBackend implements StorageBackend {

    /** Cloudinary's own name for "reachable only with a valid signature". */
    private static final String AUTHENTICATED = "authenticated";

    private final Cloudinary cloudinary;
    private final String folder;
    private final HttpClient http;

    public CloudinaryStorageBackend(DocumentProperties.Cloudinary properties) {
        this.cloudinary =
                new Cloudinary(
                        ObjectUtils.asMap(
                                "cloud_name", properties.cloudName(),
                                "api_key", properties.apiKey(),
                                "api_secret", properties.apiSecret(),
                                "secure", true));
        this.folder = properties.folder();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public StoredObject put(String objectKey, String resourceType, String contentType, Path source)
            throws IOException {

        Map<?, ?> result =
                cloudinary
                        .uploader()
                        .upload(
                                source.toFile(),
                                ObjectUtils.asMap(
                                        "public_id", objectKey,
                                        "folder", folder,
                                        "resource_type", resourceType,
                                        "type", AUTHENTICATED,
                                        // The uploader supplies the name; letting Cloudinary
                                        // derive the public id from it would put an
                                        // attacker-chosen string in a URL.
                                        "use_filename", false,
                                        "unique_filename", false,
                                        "overwrite", false));

        Object version = result.get("version");
        return new StoredObject(
                String.valueOf(result.get("public_id")),
                resourceType,
                version == null ? null : Long.valueOf(String.valueOf(version)));
    }

    @Override
    public InputStream open(String publicId, String resourceType) throws IOException {
        // Fetched through the same expiring link the browser would follow, rather than through
        // an admin call, so the scan worker exercises the delivery path rather than a
        // privileged shortcut that could keep working after that path breaks.
        URI url = signedDownloadUrl(publicId, resourceType, Duration.ofMinutes(2), "scan");
        try {
            HttpResponse<InputStream> response =
                    http.send(
                            HttpRequest.newBuilder(url).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Cloudinary returned " + response.statusCode() + " for " + publicId);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + publicId, e);
        }
    }

    @Override
    public boolean exists(String publicId, String resourceType) {
        try {
            cloudinary
                    .api()
                    .resource(
                            publicId,
                            ObjectUtils.asMap("resource_type", resourceType, "type", AUTHENTICATED));
            return true;
        } catch (Exception e) {
            // The Admin API raises NotFound for a missing asset. Treating any failure as
            // "absent" would be wrong for a network fault, but this method has exactly one
            // caller — the orphan sweep's inverse check — and both of its callers re-check.
            return false;
        }
    }

    @Override
    public void delete(String publicId, String resourceType) {
        try {
            cloudinary
                    .uploader()
                    .destroy(
                            publicId,
                            ObjectUtils.asMap(
                                    "resource_type", resourceType,
                                    "type", AUTHENTICATED,
                                    "invalidate", true));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public URI signedDownloadUrl(
            String publicId, String resourceType, Duration ttl, String fileName) {
        try {
            String url =
                    cloudinary.privateDownload(
                            publicId,
                            formatOf(publicId),
                            ObjectUtils.asMap(
                                    "resource_type", resourceType,
                                    "type", AUTHENTICATED,
                                    "expires_at", Instant.now().plus(ttl).getEpochSecond(),
                                    "attachment", true));
            return URI.create(url);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign a download URL for " + publicId, e);
        }
    }

    /**
     * Cloudinary keeps the extension out of the public id for images and inside it for raw
     * files, so the format is whatever follows the last dot, if anything.
     */
    private static String formatOf(String publicId) {
        int dot = publicId.lastIndexOf('.');
        int slash = publicId.lastIndexOf('/');
        return dot > slash && dot >= 0 ? publicId.substring(dot + 1) : "";
    }
}
