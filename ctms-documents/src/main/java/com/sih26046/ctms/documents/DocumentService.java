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
        Path spooled = spool(file);
        StoredObject stored = null;
        try {
            UploadValidator.ValidatedUpload validated =
                    validator.validate(
                            file.getOriginalFilename(), Files.size(spooled), open(spooled));

            String checksum = sha256(spooled);
            UUID id = UUID.randomUUID();

            stored =
                    storage.put(
                            id.toString(), validated.resourceType(), validated.mimeType(), spooled);

            DocumentEntity saved =
                    documents.saveAndFlush(
                            new DocumentEntity(
                                    id,
                                    // Version 1 opens its own family (§17.2).
                                    id,
                                    1,
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
