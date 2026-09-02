package com.sih26046.ctms.documents;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Filesystem storage.
 *
 * <p>Present so that every layer above it — validation, the quarantine state machine, the
 * version chain — is testable without a Cloudinary account, and so local development works
 * offline. Which backend is active is chosen by {@code ctms.documents.storage-backend} in
 * {@link DocumentsConfig}.
 */
public class LocalStorageBackend implements StorageBackend {

    private final Path root;

    public LocalStorageBackend(DocumentProperties properties) {
        this.root = Path.of(properties.local().root());
    }

    @Override
    public StoredObject put(String objectKey, String resourceType, String contentType, Path source)
            throws IOException {
        Path target = resolve(objectKey, resourceType);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredObject(objectKey, resourceType, null);
    }

    @Override
    public InputStream open(String publicId, String resourceType) throws IOException {
        return Files.newInputStream(resolve(publicId, resourceType));
    }

    @Override
    public boolean exists(String publicId, String resourceType) {
        return Files.exists(resolve(publicId, resourceType));
    }

    @Override
    public void delete(String publicId, String resourceType) {
        try {
            Files.deleteIfExists(resolve(publicId, resourceType));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<StoredAsset> list() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StoredAsset> found = new ArrayList<>();
        try (Stream<Path> resourceTypeDirs = Files.list(root)) {
            for (Path typeDir : resourceTypeDirs.filter(Files::isDirectory).toList()) {
                String resourceType = typeDir.getFileName().toString();
                try (Stream<Path> objects = Files.list(typeDir)) {
                    for (Path object : objects.filter(Files::isRegularFile).toList()) {
                        found.add(
                                new StoredAsset(
                                        object.getFileName().toString(),
                                        resourceType,
                                        Files.getLastModifiedTime(object).toInstant()));
                    }
                }
            }
        }
        return found;
    }

    @Override
    public URI signedDownloadUrl(
            String publicId, String resourceType, Duration ttl, String fileName) {
        long expires = SignedUrls.expiryFor(ttl);
        return URI.create(
                "%s?id=%s&type=%s&name=%s&expires=%d&signature=%s"
                        .formatted(
                                LocalContentController.PATH,
                                encode(publicId),
                                encode(resourceType),
                                encode(fileName),
                                expires,
                                SignedUrls.sign(publicId, resourceType, expires)));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Path resolve(String publicId, String resourceType) {
        // normalize() then a containment check: publicId is server-generated today, but a
        // path-building helper that trusts its input is one refactor away from not being safe.
        Path candidate = root.resolve(resourceType).resolve(publicId).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Object key escapes the storage root");
        }
        return candidate;
    }
}
