package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sih26046.ctms.security.RlsUserContext;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §5.5, §5.7, §8.19, §8.20 — ethics submission, review, and decision.
 *
 * <p>Two separations carry this phase. The <em>institution</em> separation: an ethics committee
 * reviews its own institution's submissions and no others, because an IEC's remit is its own
 * institution. The <em>deliberation</em> separation: the committee's reviews are private to the
 * committee — not to the submitting investigator, who would otherwise read the reviewers'
 * candid assessment of their own trial, and not to the regulator, who verifies that an approval
 * exists without reading the argument that produced it (§5.7).
 */
@SpringBootTest
@AutoConfigureMockMvc
class EthicsApiIT extends ApiTestSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private static UUID hostInstitution;
    private static UUID otherInstitution;

    private Cookie investigator;
    private String trialId;

    @BeforeEach
    void seedInstitutions() {
        if (hostInstitution == null) {
            hostInstitution = institution("Host IEC College");
            otherInstitution = institution("Unrelated College");
        }
    }

    private static UUID institution(String label) {
        return UUID.fromString(
                ownerJdbc()
                        .queryForObject(
                                "INSERT INTO institutions (name, institution_type, city, state)"
                                    + " VALUES (?,'MEDICAL_COLLEGE','Delhi','Delhi') RETURNING id",
                                String.class,
                                label + " " + UUID.randomUUID()));
    }

    /** A trial owned by a PI, moved to PENDING_ETHICS — the state in which ethics review happens. */
    private void givenATrialAwaitingEthics() throws Exception {
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        trialId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/trials")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"protocolNumber":"%s","title":"Ethics study",
                                                         "sponsorInstitutionId":"%s","phase":"II"}
                                                        """
                                                                .formatted(
                                                                        "ETH-" + UUID.randomUUID(),
                                                                        hostInstitution)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");

        String etag =
                mockMvc.perform(get("/api/v1/trials/" + trialId).cookie(investigator))
                        .andReturn()
                        .getResponse()
                        .getHeader("ETag");
        mockMvc.perform(
                        post("/api/v1/trials/" + trialId + "/status")
                                .cookie(investigator)
                                .header(HttpHeaders.IF_MATCH, etag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"PENDING_ETHICS\"}"))
                .andExpect(status().isOk());
    }

    private String submitToCommittee() throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/ethics/submissions")
                                        .cookie(investigator)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"trialId":"%s","institutionId":"%s",
                                                 "submissionNumber":"%s","submissionType":"INITIAL",
                                                 "summary":"Initial protocol for committee review"}
                                                """
                                                        .formatted(
                                                                trialId,
                                                                hostInstitution,
                                                                "IEC-" + UUID.randomUUID())))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    private String etagOfSubmission(String submissionId, Cookie who) throws Exception {
        return mockMvc.perform(get("/api/v1/ethics/submissions/" + submissionId).cookie(who))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
    }

    // ── submission ───────────────────────────────────────────────────────────

    @Test
    void anInvestigatorSubmitsTheirTrialToTheCommittee() throws Exception {
        givenATrialAwaitingEthics();

        mockMvc.perform(get("/api/v1/ethics/submissions?trialId=" + trialId).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        String submissionId = submitToCommittee();

        mockMvc.perform(get("/api/v1/ethics/submissions/" + submissionId).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.trialId").value(trialId));
    }

    @Test
    void anInvestigatorCannotSubmitOnBehalfOfATrialTheyAreNotAssignedTo() throws Exception {
        givenATrialAwaitingEthics();
        Cookie stranger = loginAs("PRINCIPAL_INVESTIGATOR");

        mockMvc.perform(
                        post("/api/v1/ethics/submissions")
                                .cookie(stranger)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"trialId":"%s","institutionId":"%s",
                                         "submissionNumber":"%s","submissionType":"INITIAL",
                                         "summary":"Not my trial"}
                                        """
                                                .formatted(
                                                        trialId,
                                                        hostInstitution,
                                                        "IEC-" + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    // ── institution scope ────────────────────────────────────────────────────

    @Test
    void theCommitteeAtTheHostInstitutionSeesTheSubmission() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();

        mockMvc.perform(
                        get("/api/v1/ethics/submissions/" + submissionId)
                                .cookie(loginAs("ETHICS_MEMBER", hostInstitution)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submissionId));
    }

    @Test
    void aCommitteeAtAnotherInstitutionDoesNotSeeIt() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();

        // §6.4 — out of scope is indistinguishable from non-existent, on purpose.
        mockMvc.perform(
                        get("/api/v1/ethics/submissions/" + submissionId)
                                .cookie(loginAs("ETHICS_MEMBER", otherInstitution)))
                .andExpect(status().isNotFound());
    }

    // ── deliberation privacy, §5.7 ───────────────────────────────────────────

    @Test
    void theCommitteeRecordsAReview() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();
        Cookie member = loginAs("ETHICS_MEMBER", hostInstitution);

        mockMvc.perform(
                        post("/api/v1/ethics/reviews")
                                .cookie(member)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"ethicsSubmissionId":"%s","recommendation":"APPROVE",
                                         "comments":"Risk-benefit acceptable; consent form clear."}
                                        """
                                                .formatted(submissionId)))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/ethics/reviews?submissionId=" + submissionId).cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recommendation").value("APPROVE"))
                .andExpect(jsonPath("$[0].comments").value(
                        "Risk-benefit acceptable; consent form clear."));
    }

    @Test
    void theSubmittingInvestigatorCannotReadTheDeliberation() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();
        recordReview(submissionId, "APPROVE");

        // The PI reads their own submission but not the committee's assessment of it.
        mockMvc.perform(get("/api/v1/ethics/submissions/" + submissionId).cookie(investigator))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/api/v1/ethics/reviews?submissionId=" + submissionId)
                                .cookie(investigator))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRegulatorReadsTheDecisionButNotTheDeliberation() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();
        recordReview(submissionId, "APPROVE");
        Cookie regulator = loginAs("REGULATORY_OFFICER");

        mockMvc.perform(get("/api/v1/ethics/submissions/" + submissionId).cookie(regulator))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/api/v1/ethics/reviews?submissionId=" + submissionId)
                                .cookie(regulator))
                .andExpect(status().isForbidden());
    }

    private void recordReview(String submissionId, String recommendation) throws Exception {
        mockMvc.perform(
                        post("/api/v1/ethics/reviews")
                                .cookie(loginAs("ETHICS_MEMBER", hostInstitution))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"ethicsSubmissionId":"%s","recommendation":"%s",
                                         "comments":"Committee deliberation, not for the file."}
                                        """
                                                .formatted(submissionId, recommendation)))
                .andExpect(status().isCreated());
    }

    @Test
    void aMemberCannotReviewAnotherInstitutionsSubmission() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();

        // The WITH CHECK on ethics_reviews must consider the submission's institution, not
        // merely that the caller is on some committee. Otherwise a member elsewhere writes a
        // review onto a submission they cannot read, which the host committee then sees.
        mockMvc.perform(
                        post("/api/v1/ethics/reviews")
                                .cookie(loginAs("ETHICS_MEMBER", otherInstitution))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"ethicsSubmissionId":"%s","recommendation":"REJECT",
                                         "comments":"Not my committee's business."}
                                        """
                                                .formatted(submissionId)))
                .andExpect(status().isNotFound());
    }

    // ── decision ─────────────────────────────────────────────────────────────

    @Test
    void theCommitteeRecordsItsDecision() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();
        Cookie member = loginAs("ETHICS_MEMBER", hostInstitution);

        mockMvc.perform(
                        post("/api/v1/ethics/submissions/" + submissionId + "/decision")
                                .cookie(member)
                                .header(HttpHeaders.IF_MATCH, etagOfSubmission(submissionId, member))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"status":"APPROVED","approvalValidUntil":"2027-12-31"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionDate").isNotEmpty());

        // The decision is a fact the submitting investigator is entitled to.
        mockMvc.perform(get("/api/v1/ethics/submissions/" + submissionId).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void aDecisionRequiresIfMatch() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();

        mockMvc.perform(
                        post("/api/v1/ethics/submissions/" + submissionId + "/decision")
                                .cookie(loginAs("ETHICS_MEMBER", hostInstitution))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void anApprovalWithConditionsMustRecordThem() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();
        Cookie member = loginAs("ETHICS_MEMBER", hostInstitution);

        // ck_ethics_submissions_conditions: an approval with conditions that records none is
        // not an auditable decision.
        mockMvc.perform(
                        post("/api/v1/ethics/submissions/" + submissionId + "/decision")
                                .cookie(member)
                                .header(HttpHeaders.IF_MATCH, etagOfSubmission(submissionId, member))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"APPROVED_WITH_CONDITIONS\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anInvestigatorCannotDecideOnTheirOwnSubmission() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();

        mockMvc.perform(
                        post("/api/v1/ethics/submissions/" + submissionId + "/decision")
                                .cookie(investigator)
                                .header(HttpHeaders.IF_MATCH, "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anInvestigatorMayWithdrawTheirOwnSubmission() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();

        mockMvc.perform(
                        post("/api/v1/ethics/submissions/" + submissionId + "/withdraw")
                                .cookie(investigator)
                                .header(
                                        HttpHeaders.IF_MATCH,
                                        etagOfSubmission(submissionId, investigator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    /**
     * The RBAC layer above already denies this, so this test asks the narrower question: with
     * the permission check removed, would the database still refuse? Self-approval is the one
     * failure this table exists to prevent, and it should not rest on a single annotation.
     */
    @Test
    void theDatabaseAlsoRefusesSelfApprovalIndependentlyOfRbac() throws Exception {
        givenATrialAwaitingEthics();
        String submissionId = submitToCommittee();
        UUID pi =
                UUID.fromString(
                        ownerJdbc()
                                .queryForObject(
                                        "SELECT submitted_by::text FROM ethics_submissions WHERE"
                                                + " id = ?::uuid",
                                        String.class,
                                        submissionId));

        assertThatThrownBy(
                        () ->
                                RlsUserContext.callAs(
                                        pi,
                                        () ->
                                                transactions.execute(
                                                        tx ->
                                                                jdbc.update(
                                                                        "UPDATE ethics_submissions"
                                                                            + " SET status ="
                                                                            + " 'APPROVED' WHERE id"
                                                                            + " = ?::uuid",
                                                                        submissionId))))
                // The root cause, not the wrapper: Spring's SQLStateSQLExceptionTranslator
                // maps SQLSTATE 42501 to BadSqlGrammarException, whose own message says only
                // "bad SQL grammar". That mapping is why RowLevelSecurityDenialAdvice keys on
                // the SQLSTATE rather than on the Spring exception type.
                .rootCause()
                .hasMessageContaining("violates row-level security policy");

        assertThat(
                        ownerJdbc()
                                .queryForObject(
                                        "SELECT status FROM ethics_submissions WHERE id = ?::uuid",
                                        String.class,
                                        submissionId))
                .isEqualTo("SUBMITTED");
    }
}
