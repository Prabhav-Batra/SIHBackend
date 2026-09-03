package com.sih26046.ctms.trials;

import com.sih26046.ctms.audit.AuditTrail;
import com.sih26046.ctms.security.CurrentUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trial lifecycle and structure (§8.8, §20.2).
 *
 * <p>The transaction boundary lives here rather than in the controller (§24.2): creating a
 * trial spans two tables and must be atomic. Audit writes (§19.2's {@code CREATE_TRIAL},
 * {@code UPDATE_TRIAL}, {@code CHANGE_TRIAL_STATUS}) are recorded here rather than in {@link
 * TrialController} for the same reason: {@link AuditTrail#recordChange} joins the caller's own
 * transaction, and this service — not the controller, which carries no {@code @Transactional}
 * of its own — is that transaction's actual boundary.
 */
@Service
public class TrialService {

    private final TrialRepository trials;
    private final TrialStaffAssignmentRepository assignments;
    private final AuditTrail audit;

    public TrialService(
            TrialRepository trials,
            TrialStaffAssignmentRepository assignments,
            AuditTrail audit) {
        this.trials = trials;
        this.assignments = assignments;
        this.audit = audit;
    }

    /**
     * Creates a trial and assigns its creator as principal investigator.
     *
     * <p>The assignment is not a convenience. The read policy on `trials` resolves through
     * trial_staff (§7.5), so a trial created without one is invisible to everybody including
     * the person who just created it — the row exists and nothing can reach it. Creating the
     * trial and recording who runs it is therefore a single atomic act, not two steps that
     * happen to be adjacent.
     */
    @Transactional
    public TrialEntity create(
            String protocolNumber,
            String title,
            UUID sponsorInstitutionId,
            String phase,
            Integer targetEnrollment,
            CurrentUser creator) {

        TrialEntity trial =
                new TrialEntity(
                        UUID.randomUUID(),
                        protocolNumber,
                        title,
                        sponsorInstitutionId,
                        phase,
                        creator.userId());
        trial.setTargetEnrollment(targetEnrollment);
        TrialEntity saved = trials.save(trial);

        assignments.assignTrialWide(saved.getId(), creator.userId(), "PI");

        audit.recordChange(
                creator.userId(),
                "CREATE_TRIAL",
                "trials",
                saved.getId(),
                saved.getId(),
                null,
                valuesOf(saved));

        return saved;
    }

    @Transactional(readOnly = true)
    public List<TrialEntity> list() {
        return trials.findAllByOrderByProtocolNumber();
    }

    @Transactional(readOnly = true)
    public Optional<TrialEntity> find(UUID id) {
        return trials.findById(id);
    }

    @Transactional
    public TrialEntity update(
            TrialEntity trial,
            String title,
            String shortTitle,
            String therapeuticArea,
            CurrentUser editor) {
        Map<String, Object> before = valuesOf(trial);
        trial.rename(title, shortTitle, therapeuticArea, editor.userId());
        TrialEntity saved = trials.save(trial);

        audit.recordChange(
                editor.userId(), "UPDATE_TRIAL", "trials", saved.getId(), saved.getId(), before,
                valuesOf(saved));

        return saved;
    }

    @Transactional
    public TrialEntity transition(TrialEntity trial, TrialStatus next, CurrentUser actor) {
        Map<String, Object> before = valuesOf(trial);
        trial.transitionTo(next, actor.userId());
        TrialEntity saved = trials.save(trial);

        audit.recordChange(
                actor.userId(),
                "CHANGE_TRIAL_STATUS",
                "trials",
                saved.getId(),
                saved.getId(),
                before,
                valuesOf(saved));

        return saved;
    }

    private static Map<String, Object> valuesOf(TrialEntity trial) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("protocolNumber", trial.getProtocolNumber());
        values.put("title", trial.getTitle());
        values.put("shortTitle", trial.getShortTitle());
        values.put("sponsorInstitutionId", trial.getSponsorInstitutionId());
        values.put("phase", trial.getPhase());
        values.put("therapeuticArea", trial.getTherapeuticArea());
        values.put("status", trial.getStatus());
        values.put("targetEnrollment", trial.getTargetEnrollment());
        return values;
    }
}
