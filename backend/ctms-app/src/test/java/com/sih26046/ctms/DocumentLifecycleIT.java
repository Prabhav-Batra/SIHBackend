package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sih26046.ctms.documents.DocumentScanWorker;
import com.sih26046.ctms.documents.MalwareScanner;
import com.sih26046.ctms.documents.ScanVerdict;
import com.sih26046.ctms.documents.StorageBackend;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §16.4, §17.2 — the version chain and signed download.
 *
 * <p>Two rules shape the chain. A superseded version is never deleted or altered: the question
 * an inspection asks is which protocol was in force on a given date, and that is unanswerable
 * if history is overwritten. And the current version stays current until its replacement is
 * ready — uploading a new draft must not silently retire the document people are working from.
 *
 * <p>Download is a two-step exchange because authorization and delivery are separate concerns.
 * The signed URL is minted per request and never stored, so it cannot outlive the check that
 * produced it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentLifecycleIT extends ApiTestSupport {

    private static final byte[] PDF_V1 = pdf("original protocol");
    private static final byte[] PDF_V2 = pdf("amended protocol");

    private static byte[] pdf(String marker) {
        return ("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n% "
                        + marker
                        + "\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    static final class ScriptedScanner implements MalwareScanner {
        volatile ScanVerdict verdict = ScanVerdict.CLEAN;

        @Override
        public ScanVerdict scan(InputStream content) throws IOException {
            content.readAllBytes();
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

    private static UUID institutionId;

    private Cookie[] investigator;
    private String trialId;

    @BeforeEach
    void seed() throws Exception {
        scanner.verdict = ScanVerdict.CLEAN;
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
                                            "Lifecycle College " + UUID.randomUUID()));
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
                                                        {"protocolNumber":"%s","title":"Versioned",
                                                         "sponsorInstitutionId":"%s","phase":"II"}
                                                        """
                                                                .formatted(
                                                                        "VER-" + UUID.randomUUID(),
                                                                        institutionId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String uploadV1() throws Exception {
        return read(
                mockMvc.perform(
                                multipart("/api/v1/documents")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "protocol.pdf",
                                                        "application/pdf",
                                                        PDF_V1))
                                        .param("trialId", trialId)
                                        .param("documentType", "PROTOCOL")
                                        .param("title", "Study protocol")
                                        .cookie(investigator)
                                        .with(csrf(investigator)))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    private String addVersion(String documentId, byte[] content) throws Exception {
        return read(
                mockMvc.perform(
                                multipart("/api/v1/documents/" + documentId + "/versions")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "protocol-v2.pdf",
                                                        "application/pdf",
                                                        content))
                                        .param("title", "Study protocol, amendment 1")
                                        .cookie(investigator)
                                        .with(csrf(investigator)))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    private void scanClean() throws Exception {
        assertThat(worker.runOnce()).isTrue();
    }

    private void publish(String documentId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/documents/" + documentId + "/publish")
                                .cookie(investigator)
                                .with(csrf(investigator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CURRENT"));
    }

    /** A published v1: uploaded, scanned clean, promoted to CURRENT. */
    private String givenAPublishedDocument() throws Exception {
        String id = uploadV1();
        scanClean();
        publish(id);
        return id;
    }

    private String statusOf(String documentId) throws Exception {
        return read(
                mockMvc.perform(get("/api/v1/documents/" + documentId).cookie(investigator))
                        .andExpect(status().isOk())
                        .andReturn(),
                "$.status");
    }

    // ── the version chain, §17.2 ─────────────────────────────────────────────

    @Test
    void aNewVersionJoinsTheFamilyRatherThanStartingItsOwn() throws Exception {
        String v1 = givenAPublishedDocument();
        String family = read(
                mockMvc.perform(get("/api/v1/documents/" + v1).cookie(investigator)).andReturn(),
                "$.documentFamilyId");

        String v2 = addVersion(v1, PDF_V2);

        mockMvc.perform(get("/api/v1/documents/" + v2).cookie(investigator))
                .andExpect(jsonPath("$.documentFamilyId").value(family))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("PENDING_SCAN"));
    }

    @Test
    void theCurrentVersionStaysAuthoritativeUntilItsReplacementIsPublished() throws Exception {
        String v1 = givenAPublishedDocument();
        addVersion(v1, PDF_V2);

        // The amendment exists but has not been approved. A site reading the protocol today
        // must still get the one in force, not a draft nobody has accepted.
        assertThat(statusOf(v1)).isEqualTo("CURRENT");
    }

    @Test
    void publishingTheNewVersionSupersedesTheOldOne() throws Exception {
        String v1 = givenAPublishedDocument();
        String v2 = addVersion(v1, PDF_V2);
        scanClean();

        publish(v2);

        assertThat(statusOf(v1)).isEqualTo("SUPERSEDED");
        mockMvc.perform(get("/api/v1/documents/" + v1).cookie(investigator))
                .andExpect(jsonPath("$.supersededById").value(v2));
    }

    @Test
    void aSupersededVersionRemainsReadableAndUnchanged() throws Exception {
        String v1 = givenAPublishedDocument();
        String checksum =
                read(
                        mockMvc.perform(get("/api/v1/documents/" + v1).cookie(investigator))
                                .andReturn(),
                        "$.checksumSha256");
        String v2 = addVersion(v1, PDF_V2);
        scanClean();
        publish(v2);

        // Which protocol was in force on a given date is unanswerable if history is
        // overwritten, so the superseded row keeps its content and stays downloadable.
        mockMvc.perform(get("/api/v1/documents/" + v1).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksumSha256").value(checksum))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/v1/documents/" + v1 + "/download").cookie(investigator))
                .andExpect(status().isFound());
    }

    @Test
    void theChainListsEveryVersionInOrder() throws Exception {
        String v1 = givenAPublishedDocument();
        String v2 = addVersion(v1, PDF_V2);
        scanClean();
        publish(v2);

        mockMvc.perform(get("/api/v1/documents/" + v2 + "/versions").cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(v1))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].id").value(v2))
                .andExpect(jsonPath("$[1].version").value(2));
    }

    @Test
    void anUnscannedVersionCannotBePublished() throws Exception {
        String v1 = uploadV1();

        mockMvc.perform(
                        post("/api/v1/documents/" + v1 + "/publish")
                                .cookie(investigator)
                                .with(csrf(investigator)))
                .andExpect(status().isConflict());
    }

    @Test
    void aQuarantinedVersionCannotBePublished() throws Exception {
        String v1 = uploadV1();
        scanner.verdict = ScanVerdict.INFECTED;
        scanClean();

        mockMvc.perform(
                        post("/api/v1/documents/" + v1 + "/publish")
                                .cookie(investigator)
                                .with(csrf(investigator)))
                .andExpect(status().isConflict());
        assertThat(statusOf(v1)).isEqualTo("QUARANTINED");
    }

    // ── signed download, §16.4 ───────────────────────────────────────────────

    @Test
    void anUnscannedDocumentCannotBeDownloaded() throws Exception {
        String v1 = uploadV1();

        mockMvc.perform(get("/api/v1/documents/" + v1 + "/download").cookie(investigator))
                .andExpect(status().isConflict());
    }

    @Test
    void aCleanDocumentRedirectsToAShortLivedSignedUrl() throws Exception {
        String v1 = givenAPublishedDocument();

        MvcResult result =
                mockMvc.perform(get("/api/v1/documents/" + v1 + "/download").cookie(investigator))
                        .andExpect(status().isFound())
                        .andExpect(header().exists("Location"))
                        .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).contains("expires=").contains("signature=");
    }

    @Test
    void theSignedUrlServesTheOriginalBytes() throws Exception {
        String v1 = givenAPublishedDocument();
        String location =
                mockMvc.perform(get("/api/v1/documents/" + v1 + "/download").cookie(investigator))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");

        URI uri = URI.create(location);
        MvcResult content =
                mockMvc.perform(get(uri.getPath() + "?" + uri.getQuery()))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(PDF_V1);
    }

    @Test
    void aTamperedSignedUrlIsRefused() throws Exception {
        String v1 = givenAPublishedDocument();
        String location =
                mockMvc.perform(get("/api/v1/documents/" + v1 + "/download").cookie(investigator))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location");

        URI uri = URI.create(location);
        // Push the expiry out by an hour without re-signing. The signature covers the expiry
        // precisely so that a leaked URL cannot be given a longer life than it was issued with.
        String query = uri.getQuery().replaceAll("expires=\\d+", "expires=99999999999");

        mockMvc.perform(get(uri.getPath() + "?" + query)).andExpect(status().isForbidden());
    }

    @Test
    void anExpiredSignedUrlIsRefused() throws Exception {
        String v1 = givenAPublishedDocument();
        String publicId =
                ownerJdbc()
                        .queryForObject(
                                "SELECT cloudinary_public_id FROM documents WHERE id = ?::uuid",
                                String.class,
                                v1);

        // Correctly signed, but for a moment that has passed. §16.4's five minutes exist so a
        // URL leaked into a log, a screenshot or a chat message is dead on arrival.
        URI expired =
                storage.signedDownloadUrl(publicId, "raw", Duration.ofSeconds(-30), "p.pdf");

        mockMvc.perform(get(expired.getPath() + "?" + expired.getQuery()))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyDownloadIsAudited() throws Exception {
        String v1 = givenAPublishedDocument();
        mockMvc.perform(get("/api/v1/documents/" + v1 + "/download").cookie(investigator))
                .andExpect(status().isFound());

        // §19.3 — who read which protocol version, and when, is exactly what an inspection
        // asks. The audit row is written at the authorization step, not at delivery, because
        // that is the moment the platform decided this caller could have the file.
        Integer audited =
                ownerJdbc()
                        .queryForObject(
                                "SELECT count(*) FROM audit_logs WHERE action = 'DOWNLOAD_DOCUMENT'"
                                        + " AND entity_type = 'documents' AND entity_id = ?::uuid",
                                Integer.class,
                                v1);
        assertThat(audited).isEqualTo(1);
    }
}
