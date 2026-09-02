package com.sih26046.ctms.trials;

import com.sih26046.ctms.security.CurrentUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trial lifecycle and structure (§8.8, §20.2).
 *
 * <p>The transaction boundary lives here rather than in the controller (§24.2): creating a
 * trial spans two tables and must be atomic.
 */
@Service
public class TrialService {

    private final TrialRepository trials;
    private final TrialStaffAssignmentRepository assignments;

    public TrialService(TrialRepository trials, TrialStaffAssignmentRepository assignments) {
        this.trials = trials;
        this.assignments = assignments;
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
        trial.rename(title, shortTitle, therapeuticArea, editor.userId());
        return trials.save(trial);
    }

    @Transactional
    public TrialEntity transition(TrialEntity trial, TrialStatus next, CurrentUser actor) {
        trial.transitionTo(next, actor.userId());
        return trials.save(trial);
    }
}
