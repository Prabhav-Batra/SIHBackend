package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sih26046.ctms.documents.MalwareScanner;
import com.sih26046.ctms.documents.ScanVerdict;
import com.sih26046.ctms.documents.StorageBackend;
import com.sih26046.ctms.documents.DocumentScanWorker;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §16.5, §16.6 — upload validation and the quarantine state machine.
 *
 * <p>The load-bearing claim of this phase is that an infected file never becomes readable, and
 * it is defended twice: the worker refuses to publish it, and
 * {@code ck_documents_available_requires_clean} refuses to store the row that would. The
 * second is what makes the first not the only thing standing between a user and malware, so
 * both are tested.
 *
 * <p>Content sniffing is the validation that matters. Extension and {@code Content-Type} are
 * attacker-controlled; magic bytes are a property of the file itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentUploadIT extends ApiTestSupport {

    /** A minimal but genuine PDF: Tika keys on the {@code %PDF-} signature. */
    private static final byte[] PDF =
            """
            %PDF-1.7
            1 0 obj
            << /Type /Catalog >>
            endobj
            trailer
            << /Root 1 0 R >>
            %%EOF
            """
                    .getBytes(StandardCharsets.US_ASCII);

    private static final byte[] PNG = png();
    private static final byte[] ELF = {0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    private static byte[] png() {
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        byte[] ihdr = {
            0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] out = new byte[header.length + ihdr.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(ihdr, 0, out, header.length, ihdr.length);
        return out;
    }

    /**
     * A scanner whose verdict the test chooses. The state machine is what these tests are
     * about; that real malware is actually detected is proven separately against a live
     * ClamAV in {@code ClamAvScanIT}, because a fake can only ever confirm the plumbing.
     */
    static final class ScriptedScanner implements MalwareScanner {
        volatile ScanVerdict verdict = ScanVerdict.CLEAN;
        volatile RuntimeException failure;

        @Override
        public ScanVerdict scan(InputStream content) throws IOException {
            content.readAllBytes();
            if (failure != null) {
                throw failure;
            }
            return verdict;
        }
    }

    @TestConfiguration
    static class Scanner {
        @Bean
        @Primary
        ScriptedScanner scriptedScanner() {
            return new ScriptedScanner();
        }
    }

    @Autowired private ScriptedScanner scanner;
    @Autowired private DocumentScanWorker worker;
    @Autowired private StorageBackend storage;
    @Autowired private JdbcTemplate jdbc;

    private static UUID institutionId;

    private Cookie[] investigator;
    private String trialId;

    @BeforeEach
    void seed() throws Exception {
        scanner.verdict = ScanVerdict.CLEAN;
        scanner.failure = null;

        // runOnce() claims the oldest due job of this type, which across a shared container is
        // just as likely to be a previous test's upload as this one's. Draining first makes
        // "the job the worker picks up" the same thing as "the job this test enqueued".
        ownerJdbc().update("DELETE FROM jobs WHERE job_type = 'DOCUMENT_SCAN'");

        if (institutionId == null) {
            institutionId =
                    UUID.fromString(
                            ownerJdbc()
                                    .queryForObject(
                                            "INSERT INTO institutions (name, institution_type,"
                                                + " city, state) VALUES"
                                                + " (?,'MEDICAL_COLLEGE','Delhi','Delhi')"
                                                + " RETURNING id",
                                            String.class,
                                            "Docs College " + UUID.randomUUID()));
        }

        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        trialId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/trials")
                                                .cookie(investigator)
                                                .with(csrf(investigator))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"protocolNumber":"%s","title":"Doc study",
                                                         "sponsorInstitutionId":"%s","phase":"II"}
                                                        """
                                                                .formatted(
                                                                        "DOC-" + UUID.randomUUID(),
                                                                        institutionId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
    }

    private MvcResult upload(String fileName, String declaredType, byte[] content) throws Exception {
        return mockMvc.perform(
                        multipart("/api/v1/documents")
                                .file(new MockMultipartFile("file", fileName, declaredType, content))
                                .param("trialId", trialId)
                                .param("documentType", "PROTOCOL")
                                .param("title", "Study protocol")
                                .cookie(investigator)
                                .with(csrf(investigator)))
                .andReturn();
    }

    /** The queue's own view of how the scan went, which the document row does not show. */
    private String jobOutcome(String documentId) {
        return ownerJdbc()
                .queryForObject(
                        "SELECT status || ' ' || coalesce(last_error, '-') FROM jobs"
                                + " WHERE payload->>'documentId' = ?",
                        String.class,
                        documentId);
    }

    private String uploadPdf() throws Exception {
        MvcResult result = upload("protocol.pdf", "application/pdf", PDF);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result, "$.id");
    }

    // ── acceptance ───────────────────────────────────────────────────────────

    @Test
    void anUploadIsAcceptedAndStartsUnscanned() throws Exception {
        mockMvc.perform(
                        multipart("/api/v1/documents")
                                .file(
                                        new MockMultipartFile(
                                                "file", "protocol.pdf", "application/pdf", PDF))
                                .param("trialId", trialId)
                                .param("documentType", "PROTOCOL")
                                .param("title", "Study protocol")
                                .cookie(investigator)
                                .with(csrf(investigator)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_SCAN"))
                .andExpect(jsonPath("$.scanStatus").value("PENDING"))
                .andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.version").value(1))
                // Version 1 opens its own family (§17.2).
                .andExpect(jsonPath("$.documentFamilyId").isNotEmpty());
    }

    @Test
    void theChecksumIsComputedServerSideRatherThanTrusted() throws Exception {
        String id = uploadPdf();

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(sha256.digest(PDF));

        mockMvc.perform(get("/api/v1/documents/" + id).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksumSha256").value(expected))
                .andExpect(jsonPath("$.fileSizeBytes").value(PDF.length));
    }

    // ── validation, §16.5 ────────────────────────────────────────────────────

    @Test
    void anExecutableRenamedAsAPdfIsRejected() throws Exception {
        // The whole point of content sniffing. Extension says pdf, Content-Type says pdf,
        // magic bytes say ELF — and the magic bytes are the only one the uploader does not
        // control by simply typing something else.
        assertThat(upload("protocol.pdf", "application/pdf", ELF).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void aSniffedTypeThatDisagreesWithTheExtensionIsRejectedNotCorrected() throws Exception {
        // A real PNG named .pdf is not malicious, but silently storing it as an image would
        // mean the extension, the declared type and the stored type all disagree with each
        // other. §16.5: rejected outright rather than corrected.
        assertThat(upload("protocol.pdf", "application/pdf", PNG).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void aFileTypeOutsideTheAllowlistIsRejected() throws Exception {
        assertThat(upload("setup.exe", "application/octet-stream", ELF).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void aFilenameCarryingPathSeparatorsIsSanitised() throws Exception {
        MvcResult result = upload("../../etc/passwd.pdf", "application/pdf", PDF);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<String>read(result, "$.fileName")).isEqualTo("passwd.pdf");
    }

    // ── the scan, §16.6 ──────────────────────────────────────────────────────

    @Test
    void uploadingEnqueuesAScan() throws Exception {
        String id = uploadPdf();

        Integer queued =
                ownerJdbc()
                        .queryForObject(
                                "SELECT count(*) FROM jobs WHERE job_type = 'DOCUMENT_SCAN'"
                                        + " AND status = 'PENDING'"
                                        + " AND payload->>'documentId' = ?",
                                Integer.class,
                                id);
        assertThat(queued).isEqualTo(1);
    }

    @Test
    void aCleanScanMakesTheDocumentPublishable() throws Exception {
        String id = uploadPdf();
        scanner.verdict = ScanVerdict.CLEAN;

        assertThat(worker.runOnce()).isTrue();

        mockMvc.perform(get("/api/v1/documents/" + id).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("CLEAN"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // The document reaching DRAFT is not on its own proof the scan worked: an earlier
        // version wrote the status and then threw, leaving the job to be retried forever
        // while the document looked perfectly scanned.
        assertThat(jobOutcome(id)).isEqualTo("SUCCEEDED -");
    }

    @Test
    void anInfectedUploadIsQuarantinedAndItsBytesAreDeleted() throws Exception {
        String id = uploadPdf();
        String publicId =
                ownerJdbc()
                        .queryForObject(
                                "SELECT cloudinary_public_id FROM documents WHERE id = ?::uuid",
                                String.class,
                                id);
        scanner.verdict = ScanVerdict.INFECTED;

        assertThat(worker.runOnce()).isTrue();

        mockMvc.perform(get("/api/v1/documents/" + id).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("INFECTED"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        // A quarantine that completes is a job that succeeded. Asserting this catches a
        // failure *after* the status write, which would otherwise look like a clean pass.
        assertThat(jobOutcome(id)).isEqualTo("SUCCEEDED -");

        // Quarantine is not a flag on a file we kept. The bytes are gone.
        assertThat(storage.exists(publicId, "raw")).isFalse();
    }

    @Test
    void anInfectedDocumentCanNeverBecomeReadableEvenIfCodeTriesToPublishIt() throws Exception {
        String id = uploadPdf();
        scanner.verdict = ScanVerdict.INFECTED;
        worker.runOnce();

        // ck_documents_available_requires_clean. The worker already refuses this transition;
        // this asserts the database would refuse it too, so the guarantee does not rest on
        // one method remembering to check.
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        ownerJdbc()
                                                .update(
                                                        "UPDATE documents SET status = 'CURRENT'"
                                                                + " WHERE id = ?::uuid",
                                                        id)))
                .isNotNull()
                .rootCause()
                .hasMessageContaining("ck_documents_available_requires_clean");
    }

    @Test
    void aTransientScannerFailureLeavesTheDocumentUnscannedAndRetries() throws Exception {
        String id = uploadPdf();
        scanner.failure = new IllegalStateException("clamd refused the connection");

        assertThat(worker.runOnce()).isTrue();

        // Still unpublishable, and the job is queued for another attempt rather than lost.
        mockMvc.perform(get("/api/v1/documents/" + id).cookie(investigator))
                .andExpect(jsonPath("$.scanStatus").value("PENDING"))
                .andExpect(jsonPath("$.status").value("PENDING_SCAN"));

        // Asserting the recorded error, not just the attempt count. An earlier version of
        // this test passed while the worker could not see the document at all: attempts
        // reached 1 for a completely different reason, and the scripted failure never ran.
        assertThat(
                        ownerJdbc()
                                .queryForObject(
                                        "SELECT attempts || ' ' || last_error FROM jobs"
                                                + " WHERE payload->>'documentId' = ?",
                                        String.class,
                                        id))
                .isEqualTo("1 java.lang.IllegalStateException: clamd refused the connection");
    }

    @Test
    void aScanCannotBeUsedToPublishADocument() throws Exception {
        String id = uploadPdf();

        // app.record_scan_result is SECURITY DEFINER, so it is a deliberate hole through RLS.
        // These are the walls around that hole: it may say what a scanner concluded and
        // nothing else. CURRENT is the status that would make a document authoritative.
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        ownerJdbc()
                                                .queryForObject(
                                                        "SELECT app.record_scan_result(?::uuid,"
                                                                + " 'CLEAN', 'CURRENT')",
                                                        String.class,
                                                        id)))
                .isNotNull()
                .rootCause()
                .hasMessageContaining("only to DRAFT or QUARANTINED");
    }

    @Test
    void theWorkerReportsNoWorkWhenTheQueueIsEmpty() throws Exception {
        jdbc.update("UPDATE jobs SET status = 'SUCCEEDED' WHERE job_type = 'DOCUMENT_SCAN'");
        assertThat(worker.runOnce()).isFalse();
    }

    // ── scope ────────────────────────────────────────────────────────────────

    @Test
    void aDocumentIsInvisibleOutsideTheTrialItBelongsTo() throws Exception {
        String id = uploadPdf();

        mockMvc.perform(
                        get("/api/v1/documents/" + id).cookie(loginAs("PRINCIPAL_INVESTIGATOR")))
                .andExpect(status().isNotFound());
    }
}
