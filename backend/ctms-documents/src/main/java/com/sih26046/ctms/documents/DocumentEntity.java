package com.sih26046.ctms.documents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** One stored file and its metadata (§8.23, §17.2). */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    public static final String PENDING_SCAN = "PENDING_SCAN";
    public static final String QUARANTINED = "QUARANTINED";
    public static final String DRAFT = "DRAFT";
    public static final String CURRENT = "CURRENT";
    public static final String SUPERSEDED = "SUPERSEDED";

    public static final String SCAN_PENDING = "PENDING";
    public static final String SCAN_CLEAN = "CLEAN";
    public static final String SCAN_INFECTED = "INFECTED";
    public static final String SCAN_ERROR = "ERROR";

    @Id private UUID id;

    /** Groups every version of one logical document; version 1 sets it to its own id (§17.2). */
    @Column(name = "document_family_id", nullable = false, updatable = false)
    private UUID documentFamilyId;

    @Column(name = "trial_id", updatable = false)
    private UUID trialId;

    @Column(name = "institution_id", updatable = false)
    private UUID institutionId;

    @Column(name = "trial_site_id", updatable = false)
    private UUID trialSiteId;

    @Column(name = "document_type", nullable = false, updatable = false)
    private String documentType;

    @Column(nullable = false)
    private String title;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    /** From content sniffing, not from the extension (§16.5). */
    @Column(name = "mime_type", nullable = false, updatable = false)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false, updatable = false)
    private long fileSizeBytes;

    @Column(name = "checksum_sha256", nullable = false, updatable = false)
    private String checksumSha256;

    @Column(name = "cloudinary_public_id", nullable = false, updatable = false)
    private String storagePublicId;

    @Column(name = "cloudinary_resource_type", nullable = false, updatable = false)
    private String storageResourceType;

    @Column(name = "cloudinary_version")
    private Long storageVersion;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String status;

    @Column(name = "superseded_by_id")
    private UUID supersededById;

    @Column(name = "scan_status", nullable = false)
    private String scanStatus;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "uploaded_at", insertable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    protected DocumentEntity() {} // JPA

    public DocumentEntity(
            UUID id,
            UUID documentFamilyId,
            int version,
            UUID trialId,
            UUID institutionId,
            UUID trialSiteId,
            String documentType,
            String title,
            String fileName,
            String mimeType,
            long fileSizeBytes,
            String checksumSha256,
            StoredObject stored,
            UUID uploadedBy,
            LocalDate effectiveDate,
            LocalDate expiryDate) {
        this.id = id;
        this.documentFamilyId = documentFamilyId;
        this.version = version;
        this.trialId = trialId;
        this.institutionId = institutionId;
        this.trialSiteId = trialSiteId;
        this.documentType = documentType;
        this.title = title;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.fileSizeBytes = fileSizeBytes;
        this.checksumSha256 = checksumSha256;
        this.storagePublicId = stored.publicId();
        this.storageResourceType = stored.resourceType();
        this.storageVersion = stored.version();
        this.uploadedBy = uploadedBy;
        this.effectiveDate = effectiveDate;
        this.expiryDate = expiryDate;
        // Unscanned and unpublishable until the scan says otherwise (§16.6).
        this.status = PENDING_SCAN;
        this.scanStatus = SCAN_PENDING;
    }

    /**
     * The scan cleared it, so it becomes publishable.
     *
     * <p>DRAFT, not CURRENT: passing a virus scan says the bytes are safe, not that the
     * document is the authoritative version of anything. Promotion is a separate, deliberate
     * act (§17.2).
     */
    public void markClean() {
        this.scanStatus = SCAN_CLEAN;
        this.status = DRAFT;
        this.scannedAt = Instant.now();
    }

    public void quarantine() {
        this.scanStatus = SCAN_INFECTED;
        this.status = QUARANTINED;
        this.scannedAt = Instant.now();
    }

    /**
     * The scanner never reached a verdict and has run out of attempts.
     *
     * <p>The status stays PENDING_SCAN, which is what keeps the file undownloadable —
     * {@code ck_documents_available_requires_clean} would refuse anything else anyway.
     */
    public void markScanFailed() {
        this.scanStatus = SCAN_ERROR;
        this.scannedAt = Instant.now();
    }

    public boolean isDownloadable() {
        return SCAN_CLEAN.equals(scanStatus);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentFamilyId() {
        return documentFamilyId;
    }

    public UUID getTrialId() {
        return trialId;
    }

    public UUID getInstitutionId() {
        return institutionId;
    }

    public UUID getTrialSiteId() {
        return trialSiteId;
    }

    public String getDocumentType() {
        return documentType;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getStoragePublicId() {
        return storagePublicId;
    }

    public String getStorageResourceType() {
        return storageResourceType;
    }

    public int getVersion() {
        return version;
    }

    public String getStatus() {
        return status;
    }

    public UUID getSupersededById() {
        return supersededById;
    }

    public String getScanStatus() {
        return scanStatus;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }
}
