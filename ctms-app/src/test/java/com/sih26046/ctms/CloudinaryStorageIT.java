package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sih26046.ctms.documents.CloudinaryStorageBackend;
import com.sih26046.ctms.documents.DocumentProperties;
import com.sih26046.ctms.documents.StorageBackend;
import com.sih26046.ctms.documents.StoredObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link CloudinaryStorageBackend} against the real service.
 *
 * <p>Tagged {@code external} and excluded from the normal build: it needs credentials and the
 * network, and it writes to a real account. Run it with {@code ./gradlew externalTest}.
 *
 * <p>It exists because an adapter exercised only against a fake proves the plumbing and nothing
 * about the contract. The precedent is this project's Flyway bug — a dependency that was on the
 * classpath, looked configured, raised no error, and silently did nothing. Signing schemes and
 * delivery-type semantics fail the same quiet way.
 *
 * <p>Everything it uploads is deleted in the same test, under the configured folder.
 */
@Tag("external")
class CloudinaryStorageIT {

    private static final byte[] CONTENT =
            "%PDF-1.7\nCloudinary round-trip probe\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private static DocumentProperties.Cloudinary credentials;
    private static StorageBackend storage;

    @BeforeAll
    static void loadCredentials() {
        Map<String, String> env = dotenv();
        credentials =
                new DocumentProperties.Cloudinary(
                        env.getOrDefault("CLOUDINARY_CLOUD_NAME", ""),
                        env.getOrDefault("CLOUDINARY_API_KEY", ""),
                        env.getOrDefault("CLOUDINARY_API_SECRET", ""),
                        env.getOrDefault("CLOUDINARY_FOLDER", "ctms/dev"));

        assumeTrue(credentials.isConfigured(), "No Cloudinary credentials in backend/.env");
        storage = new CloudinaryStorageBackend(credentials);
    }

    /** Reads backend/.env. The credentials are not in the environment and must not be. */
    private static Map<String, String> dotenv() {
        Map<String, String> values = new HashMap<>();
        for (Path dir = Path.of("").toAbsolutePath();
                dir != null;
                dir = dir.getParent()) {
            Path candidate = dir.resolve("backend/.env");
            if (!Files.isReadable(candidate)) {
                candidate = dir.resolve(".env");
            }
            if (Files.isReadable(candidate)) {
                try {
                    for (String line : Files.readAllLines(candidate)) {
                        int eq = line.indexOf('=');
                        if (eq > 0 && !line.strip().startsWith("#")) {
                            values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                        }
                    }
                } catch (IOException ignored) {
                    // Treated as "not configured", which the assumption then skips on.
                }
                return values;
            }
        }
        return values;
    }

    @Test
    void aFileSurvivesAFullRoundTripAndIsThenRemoved() throws Exception {
        Path source = Files.createTempFile("ctms-cloudinary-probe-", ".pdf");
        Files.write(source, CONTENT);
        String key = "probe-" + UUID.randomUUID();
        StoredObject stored = null;

        try {
            stored = storage.put(key, "raw", "application/pdf", source);

            assertThat(stored.publicId()).contains(key);
            assertThat(storage.exists(stored.publicId(), "raw")).isTrue();

            // The scan worker's path: fetch the bytes back.
            try (InputStream fetched = storage.open(stored.publicId(), "raw")) {
                assertThat(fetched.readAllBytes()).isEqualTo(CONTENT);
            }

            // The browser's path: follow a signed URL over real HTTP.
            URI signed =
                    storage.signedDownloadUrl(
                            stored.publicId(), "raw", Duration.ofMinutes(5), "probe.pdf");
            HttpResponse<byte[]> served = fetch(signed);
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).isEqualTo(CONTENT);

            // The claim that makes authenticated delivery worth the trouble: without a valid
            // signature the asset is not served at all. A default Cloudinary upload would be
            // readable by anyone who learned the URL, forever, with nothing to revoke.
            assertThat(fetch(tamper(signed)).statusCode()).isNotEqualTo(200);

        } finally {
            if (stored != null) {
                storage.delete(stored.publicId(), "raw");
                assertThat(storage.exists(stored.publicId(), "raw")).isFalse();
            }
            Files.deleteIfExists(source);
        }
    }

    @Test
    void anExpiredSignatureIsNotServed() throws Exception {
        Path source = Files.createTempFile("ctms-cloudinary-expiry-", ".pdf");
        Files.write(source, CONTENT);
        String key = "expiry-" + UUID.randomUUID();
        StoredObject stored = null;

        try {
            stored = storage.put(key, "raw", "application/pdf", source);

            // §16.4's whole point: a URL leaked into a log or a screenshot is dead on arrival.
            // Cloudinary's plain signed URLs never expire, so this is what confirms the
            // private-download API was the right call rather than the convenient one.
            URI expired =
                    storage.signedDownloadUrl(
                            stored.publicId(), "raw", Duration.ofSeconds(-60), "probe.pdf");

            assertThat(fetch(expired).statusCode()).isNotEqualTo(200);

        } finally {
            if (stored != null) {
                storage.delete(stored.publicId(), "raw");
            }
            Files.deleteIfExists(source);
        }
    }

    private static HttpResponse<byte[]> fetch(URI uri) throws Exception {
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static URI tamper(URI signed) {
        String query = signed.getQuery();
        String broken =
                query.replaceAll("signature=([0-9a-f])", "signature=" + "0123456789".charAt(3));
        return URI.create(signed.getScheme() + "://" + signed.getAuthority() + signed.getPath()
                + "?" + broken);
    }
}
