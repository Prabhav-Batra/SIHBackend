package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** §8.9, §8.10, §21.2 — trial sites and the staff assignments RLS resolves through. */
@SpringBootTest
@AutoConfigureMockMvc
class SitesAndStaffApiIT extends ApiTestSupport {

    private static UUID institutionId;
    private static boolean seeded;

    private Cookie[] investigator;
    private String trialId;

    @BeforeEach
    void seedInstitution() {
        if (!seeded) {
            seeded = true;
            institutionId =
                    UUID.fromString(
                            ownerJdbc()
                                    .queryForObject(
                                            "INSERT INTO institutions (name, institution_type,"
                                                + " city, state) VALUES"
                                                + " (?,'MEDICAL_COLLEGE','Delhi','Delhi')"
                                                + " RETURNING id",
                                            String.class,
                                            "Site Institute " + UUID.randomUUID()));
        }
    }

    /** A trial owned by a fresh investigator, so each test starts in its own scope. */
    private void givenATrial() throws Exception {
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/trials")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"protocolNumber":"%s","title":"Site study",
                                                 "sponsorInstitutionId":"%s","phase":"II"}
                                                """
                                                        .formatted(
                                                                "SITE-" + UUID.randomUUID(),
                                                                institutionId)))
                        .andExpect(status().isCreated())
                        .andReturn();
        trialId = read(created, "$.id");
    }

    private MvcResult createSite(Cookie[] auth, String code) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/sites")
                                .cookie(auth)
                                .with(csrf(auth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"trialId":"%s","institutionId":"%s","siteCode":"%s"}
                                        """
                                                .formatted(trialId, institutionId, code)))
                .andReturn();
    }

    // ── sites ────────────────────────────────────────────────────────────────

    @Test
    void anInvestigatorCanOpenASiteOnTheirTrial() throws Exception {
        givenATrial();

        MvcResult created = createSite(investigator, "DEL-01");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<String>read(created, "$.status")).isEqualTo("PLANNED");
    }

    @Test
    void researchStaffCannotOpenASite() throws Exception {
        givenATrial();

        assertThat(createSite(loginAs("RESEARCH_STAFF"), "DEL-02").getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void sitesAreListedOnlyForTrialsTheCallerCanSee() throws Exception {
        givenATrial();
        createSite(investigator, "DEL-03");

        mockMvc.perform(get("/api/v1/sites?trialId=" + trialId).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].siteCode").value("DEL-03"));

        // A different investigator has no assignment on this trial, so RLS yields nothing.
        mockMvc.perform(
                        get("/api/v1/sites?trialId=" + trialId)
                                .cookie(loginAs("PRINCIPAL_INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void duplicateSiteCodesWithinATrialAreRejected() throws Exception {
        givenATrial();
        createSite(investigator, "DEL-04");

        assertThat(createSite(investigator, "DEL-04").getResponse().getStatus()).isEqualTo(422);
    }

    // ── staff assignments ────────────────────────────────────────────────────

    private UUID aResearchStaffUser() {
        return UUID.fromString(
                ownerJdbc()
                        .queryForObject(
                                "INSERT INTO users (email, password_hash, full_name, role_id)"
                                    + " VALUES (?,'x','Assignee',(SELECT id FROM roles WHERE name"
                                    + " = 'RESEARCH_STAFF')) RETURNING id",
                                String.class,
                                UUID.randomUUID() + "@example.in"));
    }

    private MvcResult assign(Cookie[] auth, UUID userId, String siteId) throws Exception {
        String site = siteId == null ? "null" : "\"" + siteId + "\"";
        return mockMvc.perform(
                        post("/api/v1/trial-staff")
                                .cookie(auth)
                                .with(csrf(auth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"trialId":"%s","userId":"%s","trialSiteId":%s,
                                         "staffRole":"STAFF"}
                                        """
                                                .formatted(trialId, userId, site)))
                .andReturn();
    }

    @Test
    void anInvestigatorCanAssignStaffToTheirTrial() throws Exception {
        givenATrial();
        String siteId = read(createSite(investigator, "DEL-05"), "$.id");

        MvcResult assigned = assign(investigator, aResearchStaffUser(), siteId);

        assertThat(assigned.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<String>read(assigned, "$.staffRole")).isEqualTo("STAFF");
    }

    @Test
    void anAssignmentGrantsTheAssigneeSightOfTheTrial() throws Exception {
        // The assignment is the mechanism, not a record of one: §7.5 resolves every scoped
        // policy through this table, so creating a row here is what grants access.
        givenATrial();
        String siteId = read(createSite(investigator, "DEL-06"), "$.id");

        String email = UUID.randomUUID() + "@example.in";
        UUID staffId =
                UUID.fromString(
                        ownerJdbc()
                                .queryForObject(
                                        "INSERT INTO users (email, password_hash, full_name,"
                                            + " role_id) VALUES (?,?,'Assignee',(SELECT id FROM"
                                            + " roles WHERE name = 'RESEARCH_STAFF')) RETURNING id",
                                        String.class,
                                        email,
                                        passwordEncoder.encode(PASSWORD)));

        Cookie staff = signIn(email);
        mockMvc.perform(get("/api/v1/trials/" + trialId).cookie(staff))
                .andExpect(status().isNotFound());

        assign(investigator, staffId, siteId);

        mockMvc.perform(get("/api/v1/trials/" + trialId).cookie(signIn(email)))
                .andExpect(status().isOk());
    }

    @Test
    void endingAnAssignmentRemovesAccess() throws Exception {
        // §8.10 — an assignment is ended, never deleted: the record of who worked on a trial
        // is part of its history. No DELETE privilege is granted on the table at all.
        givenATrial();
        String siteId = read(createSite(investigator, "DEL-07"), "$.id");

        String email = UUID.randomUUID() + "@example.in";
        UUID staffId =
                UUID.fromString(
                        ownerJdbc()
                                .queryForObject(
                                        "INSERT INTO users (email, password_hash, full_name,"
                                            + " role_id) VALUES (?,?,'Assignee',(SELECT id FROM"
                                            + " roles WHERE name = 'RESEARCH_STAFF')) RETURNING id",
                                        String.class,
                                        email,
                                        passwordEncoder.encode(PASSWORD)));

        String assignmentId = read(assign(investigator, staffId, siteId), "$.id");
        mockMvc.perform(get("/api/v1/trials/" + trialId).cookie(signIn(email)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        delete("/api/v1/trial-staff/" + assignmentId)
                                .cookie(investigator)
                                .with(csrf(investigator)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/trials/" + trialId).cookie(signIn(email)))
                .andExpect(status().isNotFound());

        // The row survives; only its end_date changed.
        Integer rows =
                ownerJdbc()
                        .queryForObject(
                                "SELECT count(*) FROM trial_staff WHERE id = ?::uuid AND end_date"
                                        + " IS NOT NULL",
                                Integer.class,
                                assignmentId);
        assertThat(rows).isEqualTo(1);
    }

    private Cookie signIn(String email) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                        .formatted(email, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals("access_token")) {
                return c;
            }
        }
        throw new AssertionError("no access cookie");
    }
}
