package com.sih26046.ctms.documents;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves bytes for a signed URL issued by {@link LocalStorageBackend}.
 *
 * <p>Deliberately unauthenticated, which is what a signed URL means: the signature <em>is</em>
 * the credential, so that a browser can follow a redirect without carrying a session cookie to
 * a delivery host. Authorization already happened at {@code /documents/{id}/download}, which
 * checked the permission, the row-level scope and the scan status before minting this link.
 *
 * <p>Present only while the local backend is: with Cloudinary configured, delivery is
 * Cloudinary's and this endpoint would be an unnecessary second way in.
 */
@RestController
@ConditionalOnProperty(
        name = "ctms.documents.storage-backend",
        havingValue = "local",
        matchIfMissing = true)
public class LocalContentController {

    static final String PATH = "/api/v1/documents/content";

    private final StorageBackend storage;

    public LocalContentController(StorageBackend storage) {
        this.storage = storage;
    }

    @GetMapping(PATH)
    @PreAuthorize("permitAll()")
    public ResponseEntity<InputStreamResource> content(
            @RequestParam("id") String publicId,
            @RequestParam("type") String resourceType,
            @RequestParam("name") String fileName,
            @RequestParam("expires") long expires,
            @RequestParam("signature") String signature)
            throws IOException {

        if (!SignedUrls.isValid(publicId, resourceType, expires, signature)) {
            // One status for expired and for forged alike. Distinguishing them tells a holder
            // of a bad signature whether the id they guessed exists.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The link is not valid");
        }

        InputStream content = storage.open(publicId, resourceType);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName.replace("\"", "") + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(content));
    }
}
