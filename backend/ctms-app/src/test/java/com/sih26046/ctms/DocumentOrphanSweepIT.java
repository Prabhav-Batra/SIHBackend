package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sih26046.ctms.documents.DocumentOrphanSweepWorker;
import com.sih26046.ctms.documents.DocumentProperties;
import com.sih26046.ctms.documents.StorageBackend;
import com.sih26046.ctms.documents.StoredObject;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §16.7 — the orphan sweep.
 *
 * <p>The load-bearing claims: an object still referenced by a {@code documents} row is never
 * touched, however old it is; a fresh, genuinely unreferenced object survives the grace period
 * because it may simply be an upload whose transaction has not committed yet; and only an
 * unreferenced object past that grace period is actually removed.
 *
 * <p>The first claim is also what proves {@code app.referenced_storage_public_ids()} (V20)
 * works: the sweep runs on the pooled {@code ctms_app} connection with no identity bound, the
 * same as {@link com.sih26046.ctms.documents.DocumentScanWorker}, so if that SECURITY DEFINER
 * function did not exist or did not see past RLS, "referenced" would read back empty and this
 * test's backdated-but-referenced object would be deleted right alongside the true orphan.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentOrphanSweepIT extends ApiTestSupport {

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

    @Autowired private DocumentOrphanSweepWorker sweeper;
    @Autowired private StorageBackend storage;
    @Autowired private DocumentProperties properties;

    private static UUID institutionId;

    private Cookie investigator;
    private String trialId;

    @BeforeEach
    void seed() throws Exception {
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
                                            "Sweep College " + UUID.randomUUID()));
        }

        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        trialId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/trials")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"protocolNumber":"%s","title":"Sweep study",
                                                         "sponsorInstitutionId":"%s","phase":"II"}
                                                        """
                                                                .formatted(
                                                                        "SWEEP-" + UUID.randomUUID(),
                                                                        institutionId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
    }

    private String uploadPdf() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                multipart("/api/v1/documents")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "protocol.pdf",
                                                        "application/pdf",
                                                        PDF))
                                        .param("trialId", trialId)
                                        .param("documentType", "PROTOCOL")
                                        .param("title", "Study protocol")
                                        .cookie(investigator))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result, "$.id");
    }

    /** Backdates the on-disk object so it looks like it has sat there past the grace period. */
    private void backdate(String publicId, String resourceType, Duration age) throws Exception {
        Path onDisk = Path.of(properties.local().root(), resourceType, publicId);
        Files.setLastModifiedTime(onDisk, FileTime.from(Instant.now().minus(age)));
    }

    @Test
    void aReferencedObjectIsNeverRemovedEvenWhenOld() throws Exception {
        String id = uploadPdf();
        String publicId =
                ownerJdbc()
                        .queryForObject(
                                "SELECT cloudinary_public_id FROM documents WHERE id = ?::uuid",
                                String.class,
                                id);

        // Old enough to be a candidate on age alone. It must survive purely because a
        // document row still points at it — the sweep's other, and only other, condition.
        backdate(publicId, "raw", Duration.ofHours(25));

        sweeper.sweep();

        assertThat(storage.exists(publicId, "raw")).isTrue();
    }

    @Test
    void aFreshUnreferencedObjectSurvivesTheGracePeriod() throws Exception {
        Path source = Files.createTempFile("ctms-orphan-fresh-", ".pdf");
        Files.write(source, PDF);
        String key = "orphan-fresh-" + UUID.randomUUID();

        try {
            StoredObject stored = storage.put(key, "raw", "application/pdf", source);

            // No backdating: this object was "stored" moments ago, exactly like an upload
            // whose transaction has not committed. §16.7's 24-hour delay exists precisely so
            // this sweep cannot race that.
            sweeper.sweep();

            assertThat(storage.exists(stored.publicId(), "raw")).isTrue();
        } finally {
            storage.delete(key, "raw");
            Files.deleteIfExists(source);
        }
    }

    @Test
    void anOldUnreferencedObjectIsRemoved() throws Exception {
        Path source = Files.createTempFile("ctms-orphan-old-", ".pdf");
        Files.write(source, PDF);
        String key = "orphan-old-" + UUID.randomUUID();
        StoredObject stored = storage.put(key, "raw", "application/pdf", source);

        try {
            backdate(stored.publicId(), "raw", Duration.ofHours(25));

            int removed = sweeper.sweep();

            assertThat(removed).isGreaterThanOrEqualTo(1);
            assertThat(storage.exists(stored.publicId(), "raw")).isFalse();
        } finally {
            Files.deleteIfExists(source);
        }
    }
}
