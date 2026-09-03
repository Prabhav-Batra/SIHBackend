package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** §21.1, §14.4, §20.2 — the trials API: permissions, scope, concurrency and lifecycle. */
@SpringBootTest
@AutoConfigureMockMvc
class TrialsApiIT extends ApiTestSupport {

    private static UUID institutionId;
    private static boolean seeded;

    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        seeded = true;
        institutionId =
                UUID.fromString(
                        ownerJdbc()
                                .queryForObject(
                                        "INSERT INTO institutions (name, institution_type, city,"
                                            + " state) VALUES (?,'MEDICAL_COLLEGE','Delhi','Delhi')"
                                            + " RETURNING id",
                                        String.class,
                                        "API Institute " + UUID.randomUUID()));
    }



    private String createTrialBody() {
        return """
               {"protocolNumber":"%s","title":"A study of something",
                "sponsorInstitutionId":"%s","phase":"III"}
               """
                .formatted("API-" + UUID.randomUUID(), institutionId);
    }

    private MvcResult createTrial(Cookie[] auth) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/trials")
                                .cookie(auth)
                                .with(csrf(auth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createTrialBody()))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    void anInvestigatorCanCreateATrial() throws Exception {
        MvcResult created = createTrial(loginAs("PRINCIPAL_INVESTIGATOR"));

        assertThat(this.<String>read(created, "$.status")).isEqualTo("DRAFT");
        assertThat(created.getResponse().getHeader(HttpHeaders.ETAG)).isNotBlank();
    }

    @Test
    void researchStaffCannotCreateATrial() throws Exception {
        Cookie[] staff = loginAs("RESEARCH_STAFF");
        mockMvc.perform(
                        post("/api/v1/trials")
                                .cookie(staff)
                                .with(csrf(staff))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createTrialBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theCreatingInvestigatorCanReadTheTrialBack() throws Exception {
        // Creating a trial nobody can then see would be a complete dead end: the read policy
        // resolves through trial_staff, so creation must also record the creator's assignment.
        Cookie[] auth = loginAs("PRINCIPAL_INVESTIGATOR");
        String id = read(createTrial(auth), "$.id");

        mockMvc.perform(get("/api/v1/trials/" + id).cookie(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void anotherInvestigatorCannotSeeIt() throws Exception {
        // RLS reaching the API surface: out of scope is 404, not 403 (§6.4) — telling a caller
        // a trial exists but is not theirs is itself a disclosure.
        String id =
                read(createTrial(loginAs("PRINCIPAL_INVESTIGATOR")), "$.id");

        mockMvc.perform(get("/api/v1/trials/" + id).cookie(loginAs("PRINCIPAL_INVESTIGATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingRequiresIfMatch() throws Exception {
        Cookie[] auth = loginAs("PRINCIPAL_INVESTIGATOR");
        String id = read(createTrial(auth), "$.id");

        mockMvc.perform(
                        patch("/api/v1/trials/" + id)
                                .cookie(auth)
                                .with(csrf(auth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Renamed\"}"))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void updatingWithTheCurrentVersionSucceedsAndAdvancesIt() throws Exception {
        Cookie[] auth = loginAs("PRINCIPAL_INVESTIGATOR");
        MvcResult created = createTrial(auth);
        String id = read(created, "$.id");
        String etag = created.getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult updated =
                mockMvc.perform(
                                patch("/api/v1/trials/" + id)
                                        .cookie(auth)
                                        .with(csrf(auth))
                                        .header(HttpHeaders.IF_MATCH, etag)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"Renamed\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.title").value("Renamed"))
                        .andReturn();

        assertThat(updated.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(etag);
    }

    @Test
    void aStaleVersionIsRejectedRatherThanOverwriting() throws Exception {
        // §14.4 — last-write-wins silently discards a colleague's edit. Two coordinators
        // editing the same trial is routine, so the second must be told, not ignored.
        Cookie[] auth = loginAs("PRINCIPAL_INVESTIGATOR");
        MvcResult created = createTrial(auth);
        String id = read(created, "$.id");
        String staleEtag = created.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(
                        patch("/api/v1/trials/" + id)
                                .cookie(auth)
                                .with(csrf(auth))
                                .header(HttpHeaders.IF_MATCH, staleEtag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"First writer\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        patch("/api/v1/trials/" + id)
                                .cookie(auth)
                                .with(csrf(auth))
                                .header(HttpHeaders.IF_MATCH, staleEtag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Second writer\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aTrialCannotBeActivatedWithoutEthicsApproval() throws Exception {
        Cookie[] auth = loginAs("PRINCIPAL_INVESTIGATOR");
        MvcResult created = createTrial(auth);
        String id = read(created, "$.id");
        String etag = created.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(
                        post("/api/v1/trials/" + id + "/status")
                                .cookie(auth)
                                .with(csrf(auth))
                                .header(HttpHeaders.IF_MATCH, etag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aTrialAdvancesThroughTheApprovalPath() throws Exception {
        Cookie[] auth = loginAs("PRINCIPAL_INVESTIGATOR");
        MvcResult created = createTrial(auth);
        String id = read(created, "$.id");
        String etag = created.getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult submitted =
                mockMvc.perform(
                                post("/api/v1/trials/" + id + "/status")
                                        .cookie(auth)
                                        .with(csrf(auth))
                                        .header(HttpHeaders.IF_MATCH, etag)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"status\":\"PENDING_ETHICS\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PENDING_ETHICS"))
                        .andReturn();

        assertThat(submitted.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(etag);
    }
}
