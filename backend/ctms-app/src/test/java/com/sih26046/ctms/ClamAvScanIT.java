package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sih26046.ctms.documents.DocumentScanWorker;
import com.sih26046.ctms.documents.StorageBackend;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.ClamavException;

/**
 * §16.6's actual done-condition, proven against a real clamd rather than the scripted verdict
 * {@code DocumentUploadIT} uses: an EICAR upload never leaves quarantine.
 *
 * <p>{@code DocumentUploadIT}'s {@code ScriptedScanner} exercises the state machine — upload,
 * enqueue, quarantine, byte deletion — and proves none of that has regressed, but it can never
 * prove detection, because it never asks clamd anything. This is the other half: the real
 * {@link com.sih26046.ctms.documents.ClamAvScanner}, talking to a real clamd, over the EICAR
 * test string every antivirus engine recognises without it being live malware.
 *
 * <p>Tagged {@code external} for the same reason {@code CloudinaryStorageIT} is: it needs a
 * ~1 GB one-time image pull and roughly 40 seconds for clamd to load its signature database
 * before it will answer, so it does not belong in the suite that runs on every build. Run it
 * with {@code ./gradlew externalTest}.
 */
@Tag("external")
@SpringBootTest
@AutoConfigureMockMvc
class ClamAvScanIT extends ApiTestSupport {

    /**
     * The standard EICAR antivirus test string (eicar.org). Every scan engine, ClamAV
     * included, recognises it as a positive without it containing any real malicious code.
     */
    private static final byte[] EICAR =
            ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
                    .getBytes(StandardCharsets.US_ASCII);

    /**
     * A minimal but genuine PDF, so the companion clean-file test is a real round trip through
     * clamd rather than a scan that was always going to say CLEAN because nothing was sent.
     */
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

    private static final GenericContainer<?> CLAMAV =
            new GenericContainer<>(DockerImageName.parse("clamav/clamav:stable"))
                    .withExposedPorts(3310)
                    // The image ships a HEALTHCHECK that pings clamd directly, which is a
                    // truer readiness signal than "the port accepts connections" — clamd
                    // listens before its signature database has finished loading, and a scan
                    // sent in that window fails rather than answering CLEAN or INFECTED.
                    .waitingFor(Wait.forHealthcheck())
                    .withStartupTimeout(Duration.ofMinutes(3));

    static {
        CLAMAV.start();
    }

    @DynamicPropertySource
    static void clamavProperties(DynamicPropertyRegistry registry) {
        registry.add("ctms.documents.clamav.host", CLAMAV::getHost);
        registry.add("ctms.documents.clamav.port", () -> CLAMAV.getMappedPort(3310));
    }

    @Autowired private DocumentScanWorker worker;
    @Autowired private StorageBackend storage;

    private static UUID institutionId;

    private Cookie[] investigator;
    private String trialId;

    @BeforeAll
    static void clamdIsAcceptingCommands() {
        // Belt and suspenders on top of the container healthcheck: confirm the PING command
        // this test's own scans depend on actually gets a reply before any test runs, so a
        // slow-loading signature database fails here with a clear message instead of as a
        // scan timeout three tests in.
        ClamavClient probe = new ClamavClient(CLAMAV.getHost(), CLAMAV.getMappedPort(3310));
        ClamavException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                probe.ping();
                return;
            } catch (ClamavException e) {
                last = e;
                sleep(Duration.ofSeconds(2));
            }
        }
        throw new AssertionError("clamd never answered PING", last);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        // A shared container across a class means "the oldest due job" is only meaningfully
        // this test's job once any earlier run's leftovers are gone.
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
                                            "ClamAV College " + UUID.randomUUID()));
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
                                                        {"protocolNumber":"%s","title":"ClamAV study",
                                                         "sponsorInstitutionId":"%s","phase":"II"}
                                                        """
                                                                .formatted(
                                                                        "CLAM-" + UUID.randomUUID(),
                                                                        institutionId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
    }

    private MvcResult upload(String fileName, String declaredType, byte[] content)
            throws Exception {
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

    private String jobOutcome(String documentId) {
        return ownerJdbc()
                .queryForObject(
                        "SELECT status || ' ' || coalesce(last_error, '-') FROM jobs"
                                + " WHERE payload->>'documentId' = ?",
                        String.class,
                        documentId);
    }

    @Test
    void anEicarUploadIsQuarantinedAndItsBytesAreDeleted() throws Exception {
        // §16.5's CSV allowance is content sniffed as text/plain, which is exactly what the
        // EICAR string is: plain ASCII with no format markers of its own.
        MvcResult uploaded = upload("eicar.csv", "text/csv", EICAR);
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
        String id = read(uploaded, "$.id");
        String publicId =
                ownerJdbc()
                        .queryForObject(
                                "SELECT cloudinary_public_id FROM documents WHERE id = ?::uuid",
                                String.class,
                                id);

        assertThat(worker.runOnce()).isTrue();

        mockMvc.perform(get("/api/v1/documents/" + id).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("INFECTED"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        // The job completed rather than erroring after a partial write (§5.2's trap: a status
        // written just before an exception looks exactly like success from the row alone).
        assertThat(jobOutcome(id)).isEqualTo("SUCCEEDED -");

        // The bytes are gone, not merely flagged.
        assertThat(storage.exists(publicId, "raw")).isFalse();

        // And the platform's actual promise: a quarantined document is never served, checked
        // through the real endpoint rather than only through internal state.
        mockMvc.perform(get("/api/v1/documents/" + id + "/download").cookie(investigator))
                .andExpect(status().isConflict());
    }

    @Test
    void aCleanFileIsConfirmedCleanByARealClamd() throws Exception {
        String id = read(upload("protocol.pdf", "application/pdf", PDF), "$.id");

        assertThat(worker.runOnce()).isTrue();

        mockMvc.perform(get("/api/v1/documents/" + id).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("CLEAN"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
