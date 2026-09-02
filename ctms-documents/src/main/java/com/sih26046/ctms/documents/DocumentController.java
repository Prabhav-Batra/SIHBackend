package com.sih26046.ctms.documents;

import com.sih26046.ctms.audit.AuditTrail;
import com.sih26046.ctms.security.CurrentUser;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Document upload and metadata (§16). */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService service;
    private final DocumentRepository documents;
    private final DocumentProperties properties;
    private final StorageBackend storage;
    private final AuditTrail audit;

    public DocumentController(
            DocumentService service,
            DocumentRepository documents,
            DocumentProperties properties,
            StorageBackend storage,
            AuditTrail audit) {
        this.service = service;
        this.documents = documents;
        this.properties = properties;
        this.storage = storage;
        this.audit = audit;
    }

    /**
     * What the API returns about a document.
     *
     * <p>The storage handle is deliberately absent. A public id plus a resource type is most of
     * what an attacker needs to guess at a delivery URL, and the caller has no use for it —
     * downloads go through the signed-URL endpoint, which re-checks scope on every request.
     */
    public record DocumentView(
            UUID id,
            UUID documentFamilyId,
            UUID trialId,
            UUID institutionId,
            UUID trialSiteId,
            String documentType,
            String title,
            String fileName,
            String mimeType,
            long fileSizeBytes,
            String checksumSha256,
            int version,
            String status,
            String scanStatus,
            Instant scannedAt,
            UUID supersededById,
            UUID uploadedBy,
            Instant uploadedAt,
            LocalDate effectiveDate,
            LocalDate expiryDate) {

        static DocumentView of(DocumentEntity d) {
            return new DocumentView(
                    d.getId(),
                    d.getDocumentFamilyId(),
                    d.getTrialId(),
                    d.getInstitutionId(),
                    d.getTrialSiteId(),
                    d.getDocumentType(),
                    d.getTitle(),
                    d.getFileName(),
                    d.getMimeType(),
                    d.getFileSizeBytes(),
                    d.getChecksumSha256(),
                    d.getVersion(),
                    d.getStatus(),
                    d.getScanStatus(),
                    d.getScannedAt(),
                    d.getSupersededById(),
                    d.getUploadedBy(),
                    d.getUploadedAt(),
                    d.getEffectiveDate(),
                    d.getExpiryDate());
        }
    }

    @PostMapping
    @PreAuthorize("hasAuthority('document:upload')")
    public ResponseEntity<DocumentView> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) UUID trialId,
            @RequestParam(required = false) UUID institutionId,
            @RequestParam(required = false) UUID trialSiteId,
            @RequestParam String documentType,
            @RequestParam String title,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate effectiveDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate expiryDate,
            @AuthenticationPrincipal CurrentUser caller) {

        if (trialId == null && institutionId == null) {
            // An unscoped document is one no policy can place, so it would be invisible to
            // everyone including its uploader.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A document must belong to a trial or an institution");
        }

        DocumentEntity saved =
                service.upload(
                        file,
                        new DocumentService.UploadRequest(
                                trialId,
                                institutionId,
                                trialSiteId,
                                documentType,
                                title,
                                effectiveDate,
                                expiryDate,
                                null),
                        caller.userId());

        return ResponseEntity.created(URI.create("/api/v1/documents/" + saved.getId()))
                .body(DocumentView.of(saved));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('document:read')")
    @Transactional(readOnly = true)
    public List<DocumentView> list(@RequestParam UUID trialId) {
        return documents.findAllByTrialIdOrderByUploadedAtDesc(trialId).stream()
                .map(DocumentView::of)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('document:read')")
    @Transactional(readOnly = true)
    public DocumentView get(@PathVariable UUID id) {
        return DocumentView.of(service.require(id));
    }

    // ── the version chain, §17.2 ─────────────────────────────────────────────

    @PostMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('document:upload')")
    public ResponseEntity<DocumentView> addVersion(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @AuthenticationPrincipal CurrentUser caller) {

        DocumentEntity saved = service.addVersion(file, id, title, caller.userId());
        return ResponseEntity.created(URI.create("/api/v1/documents/" + saved.getId()))
                .body(DocumentView.of(saved));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('document:read')")
    @Transactional(readOnly = true)
    public List<DocumentView> versions(@PathVariable UUID id) {
        return service.versionsOf(id).stream().map(DocumentView::of).toList();
    }

    /**
     * Declares which version is authoritative.
     *
     * <p>Gated on {@code document:supersede} rather than on upload: adding a draft and deciding
     * that the trial now runs on it are different acts, and §5.8 gives them to different sets
     * of roles — a coordinator may upload an amendment, but retiring the protocol in force is
     * the investigator's call.
     */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('document:supersede')")
    public DocumentView publish(@PathVariable UUID id) {
        return DocumentView.of(service.publish(id));
    }

    // ── download, §16.4 ──────────────────────────────────────────────────────

    /**
     * Authorises, then redirects to a short-lived signed URL.
     *
     * <p>Two steps because authorization and delivery are separate concerns. The permission
     * check, the row-level scope and the scan status are all decided here; the signed URL
     * carries the result for five minutes and is never stored or cached, so it cannot outlive
     * the check that produced it (§12.2).
     */
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('document:read')")
    @Transactional(readOnly = true)
    public ResponseEntity<Void> download(
            @PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {

        DocumentEntity document = service.require(id);
        if (!document.isDownloadable()) {
            throw new IllegalDocumentStateException(
                    "The document is not available for download; its scan status is "
                            + document.getScanStatus());
        }

        // Recorded at the authorization step, not at delivery: this is the moment the platform
        // decided this caller could have the file, and §19.3 asks who read which protocol
        // version and when. Whether the browser then followed the redirect is not the question.
        audit.record(
                caller.userId(),
                "DOWNLOAD_DOCUMENT",
                "documents",
                document.getId(),
                document.getTrialId());

        URI signed =
                storage.signedDownloadUrl(
                        document.getStoragePublicId(),
                        document.getStorageResourceType(),
                        properties.downloadUrlTtl(),
                        document.getFileName());

        return ResponseEntity.status(HttpStatus.FOUND).location(signed).build();
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<Void> onNotFound() {
        // §6.4 — out of scope and non-existent are deliberately indistinguishable.
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalDocumentStateException.class)
    public ResponseEntity<String> onIllegalState(IllegalDocumentStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /**
     * A rejected upload is 422, not 400: the request was well-formed and the server understood
     * it perfectly — it is the content that is unacceptable.
     */
    @ExceptionHandler(RejectedUploadException.class)
    public ResponseEntity<String> onRejected(RejectedUploadException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(e.getMessage());
    }
}
