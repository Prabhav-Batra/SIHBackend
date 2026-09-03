package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** §8.7, §21.1 — institutions: readable by everyone, writable by administrators. */
@SpringBootTest
@AutoConfigureMockMvc
class InstitutionsApiIT extends ApiTestSupport {

    private String body(String name, Double lat, Double lon) {
        return """
               {"name":"%s","institutionType":"MEDICAL_COLLEGE","city":"Delhi","state":"Delhi",
                "latitude":%s,"longitude":%s}
               """
                .formatted(name, lat, lon);
    }

    private MvcResult create(Cookie[] auth, Double lat, Double lon) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/institutions")
                                .cookie(auth)
                                .with(csrf(auth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("Institute " + UUID.randomUUID(), lat, lon)))
                .andReturn();
    }

    @Test
    void anAdministratorCanCreateAnInstitution() throws Exception {
        MvcResult created = create(loginAs("SYSTEM_ADMIN"), 28.6139, 77.2090);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<Double>read(created, "$.latitude")).isEqualTo(28.6139);
        assertThat(created.getResponse().getHeader(HttpHeaders.ETAG)).isNotBlank();
    }

    @Test
    void anInvestigatorCannotCreateOne() throws Exception {
        assertThat(create(loginAs("PRINCIPAL_INVESTIGATOR"), null, null).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void everyAuthenticatedRoleCanListThem() throws Exception {
        // §8.7 and §1.3: institution names and coordinates are public infrastructure facts,
        // and the map is global, so scope does not narrow this list.
        MvcResult created = create(loginAs("SYSTEM_ADMIN"), 28.6139, 77.2090);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID institution = UUID.fromString(read(created, "$.id"));

        for (String role :
                new String[] {
                    "RESEARCH_STAFF", "ETHICS_MEMBER", "SAFETY_OFFICER", "REGULATORY_OFFICER"
                }) {
            // An ethics member must belong to an institution: §8.2's
            // ck_users_ethics_needs_institution refuses to store an active one without a
            // computable review scope. The others are not institution-bound.
            UUID scope = "ETHICS_MEMBER".equals(role) ? institution : null;

            mockMvc.perform(get("/api/v1/institutions").cookie(loginAs(role, scope)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").exists());
        }
    }

    @Test
    void halfACoordinateIsRejected() throws Exception {
        // §8.7 — a latitude without a longitude silently places the institution on the prime
        // meridian. The database refuses it; the API must report that as the client's error.
        assertThat(create(loginAs("SYSTEM_ADMIN"), 28.6139, null).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void anOutOfRangeCoordinateIsRejected() throws Exception {
        assertThat(create(loginAs("SYSTEM_ADMIN"), 120.0, 77.2090).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void updatingRequiresIfMatchAndAdvancesTheEtag() throws Exception {
        Cookie[] admin = loginAs("SYSTEM_ADMIN");
        MvcResult created = create(admin, 28.6139, 77.2090);
        String id = read(created, "$.id");
        String etag = created.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(
                        patch("/api/v1/institutions/" + id)
                                .cookie(admin)
                                .with(csrf(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"city\":\"New Delhi\"}"))
                .andExpect(status().isPreconditionRequired());

        MvcResult updated =
                mockMvc.perform(
                                patch("/api/v1/institutions/" + id)
                                        .cookie(admin)
                                        .with(csrf(admin))
                                        .header(HttpHeaders.IF_MATCH, etag)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"city\":\"New Delhi\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.city").value("New Delhi"))
                        .andReturn();

        assertThat(updated.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(etag);
    }

    @Test
    void aStaleUpdateIsRejected() throws Exception {
        Cookie[] admin = loginAs("SYSTEM_ADMIN");
        MvcResult created = create(admin, 28.6139, 77.2090);
        String id = read(created, "$.id");
        String stale = created.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(
                        patch("/api/v1/institutions/" + id)
                                .cookie(admin)
                                .with(csrf(admin))
                                .header(HttpHeaders.IF_MATCH, stale)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"city\":\"First\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        patch("/api/v1/institutions/" + id)
                                .cookie(admin)
                                .with(csrf(admin))
                                .header(HttpHeaders.IF_MATCH, stale)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"city\":\"Second\"}"))
                .andExpect(status().isConflict());
    }
}
