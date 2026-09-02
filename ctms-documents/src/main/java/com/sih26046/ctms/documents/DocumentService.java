package com.sih26046.ctms.documents;

import com.sih26046.ctms.jobs.JobQueue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Upload orchestration: validate, checksum, store, record, enqueue the scan (§16.5–§16.7). */
@Service
public class DocumentService {

    /** The job type the scan worker drains. */
    public static final String SCAN_JOB = "DOCUMENT_SCAN";

    private final DocumentRepository documents;
    private final StorageBackend storage;
    private final UploadValidator validator;
    private final JobQueue jobs;

    public DocumentService(
            DocumentRepository documents,
            StorageBackend storage,
            UploadValidator validator,
            JobQueue jobs) {
        this.documents = documents;
        this.storage = storage;
        this.validator = validator;
        this.jobs = jobs;
    }

    /** What the caller asked to store, apart from the bytes. */
    public record UploadRequest(
            UUID trialId,
            UUID institutionId,
            UUID trialSiteId,
            String documentType,
            String title,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            UUID supersedes) {}

    /**
     * Stores a file and schedules its scan.
     *
     * <p>The bytes are written to storage <em>before</em> this transaction commits, which
     * §16.7 argues is the correct ordering: a failed commit then leaves an unreferenced object,
     * whereas committing metadata first would leave a document row whose file does not exist —
     * a platform that believes it holds a protocol it cannot produce. The exception path below
     * removes the object, and the nightly sweep catches what a crash between the two skips.
     */
    @Transactional
    public DocumentEntity upload(MultipartFile file, UploadRequest request, UUID uploader) {
        UUID id = UUID.randomUUID();
        // Version 1 opens its own family (§17.2).
        return store(file, request, uploader, id, id, 1);
    }

    /**
     * Adds the next version to an existing document's family.
     *
     * <p>The previous version is not touched. An amendment that has been uploaded is not yet an
     * amendment that has been approved, and the version people are working from must stay
     * current until its replacement is published.
     */
    @Transactional
    public DocumentEntity addVersion(
            MultipartFile file, UUID previousId, String title, UUID uploader) {

        DocumentEntity previous = require(previousId);
        List<DocumentEntity> family =
                documents.findAllByDocumentFamilyIdOrderByVersion(previous.getDocumentFamilyId());
        int next = family.stream().mapToInt(DocumentEntity::getVersion).max().orElse(0) + 1;

        // Scope and classification are properties of the family, not of the upload: a new
        // version of a site's consent form is still that site's consent form, and letting a
        // caller restate them would let version 2 land somewhere version 1 could not.
        UploadRequest request =
                new UploadRequest(
                        previous.getTrialId(),
                        previous.getInstitutionId(),
                        previous.getTrialSiteId(),
                        previous.getDocumentType(),
                        title == null || title.isBlank() ? previous.getTitle() : title,
                        null,
                        null,
                        previousId);

        return store(
                file, request, uploader, UUID.randomUUID(), previous.getDocumentFamilyId(), next);
    }

    /**
     * Makes a scanned draft the authoritative version, retiring whichever version held that
     * position.
     *
     * <p>One transaction, because a family with two CURRENT versions — or none — is a worse
     * state than either outcome. The promotion and the demotion are the same decision.
     */
    @Transactional
    public DocumentEntity publish(UUID id) {
        DocumentEntity promoted = require(id);
        // Before any write, so a refused publication does not retire the version in force.
        promoted.requirePublishable();

        // Demote first, and flush before promoting. uq_documents_one_current_per_family is a
        // partial unique index, and an index — unlike a constraint — cannot be deferred to
        // commit. Promoting first leaves two CURRENT rows for the length of one statement,
        // which is long enough to be rejected.
        List<DocumentEntity> retiring =
                documents.findAllByDocumentFamilyIdOrderByVersion(promoted.getDocumentFamilyId())
                        .stream()
                        .filter(d -> !d.getId().equals(id))
                        .filter(d -> DocumentEntity.CURRENT.equals(d.getStatus()))
                        .toList();
        retiring.forEach(d -> d.supersededBy(promoted));
        documents.saveAllAndFlush(retiring);

        promoted.publish();
        return documents.saveAndFlush(promoted);
    }

    public DocumentEntity require(UUID id) {
        // RLS has already filtered: out of scope and non-existent are the same answer (§6.4).
        return documents.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    public List<DocumentEntity> versionsOf(UUID id) {
        return documents.findAllByDocumentFamilyIdOrderByVersion(
                require(id).getDocumentFamilyId());
    }

    private DocumentEntity store(
            MultipartFile file,
            UploadRequest request,
            UUID uploader,
            UUID id,
            UUID familyId,
            int version) {

        Path spooled = spool(file);
        StoredObject stored = null;
        try {
            UploadValidator.ValidatedUpload validated =
                    validator.validate(
                            file.getOriginalFilename(), Files.size(spooled), open(spooled));

            String checksum = sha256(spooled);
            stored =
                    storage.put(
                            id.toString(), validated.resourceType(), validated.mimeType(), spooled);

            DocumentEntity saved =
                    documents.saveAndFlush(
                            new DocumentEntity(
                                    id,
                                    familyId,
                                    version,
                                    request.trialId(),
                                    request.institutionId(),
                                    request.trialSiteId(),
                                    request.documentType(),
                                    request.title(),
                                    validated.fileName(),
                                    validated.mimeType(),
                                    Files.size(spooled),
                                    checksum,
                                    stored,
                                    uploader,
                                    request.effectiveDate(),
                                    request.expiryDate()));

            // Enqueued in this transaction, so a rollback takes the job with it. A broker
            // would need an outbox to promise the same (§10).
            jobs.enqueue(SCAN_JOB, payloadFor(saved.getId()));
            return saved;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            if (stored != null) {
                storage.delete(stored.publicId(), stored.resourceType());
            }
            throw e;
        } finally {
            deleteQuietly(spooled);
        }
    }

    /**
     * The scan payload.
     *
     * <p>Built by concatenation rather than through a serialiser because the only interpolated
     * value is a {@link UUID}, whose {@code toString} cannot produce a quote or a backslash.
     * Any field that came from a caller would need real encoding.
     */
    static String payloadFor(UUID documentId) {
        return "{\"documentId\":\"" + documentId + "\"}";
    }

    private static Path spool(MultipartFile file) {
        // Sniffing, checksumming and storing each need the bytes from the start. Spooling once
        // to disk beats holding a 50 MB array in heap for the duration of three passes.
        try {
            Path temp = Files.createTempFile("ctms-upload-", ".bin");
            file.transferTo(temp);
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    /**
     * Computed server-side, never accepted from the caller: a checksum the uploader supplies
     * attests to nothing, since anyone who can alter the file can alter the claim about it.
     */
    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover temp file is not worth failing an otherwise successful upload.
        }
    }
}
