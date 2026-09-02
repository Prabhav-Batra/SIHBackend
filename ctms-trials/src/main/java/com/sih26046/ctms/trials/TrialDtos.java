package com.sih26046.ctms.trials;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** Request and response bodies for /api/v1/trials. */
public final class TrialDtos {

    private TrialDtos() {}

    public record CreateTrial(
            @NotBlank String protocolNumber,
            @NotBlank String title,
            @NotNull UUID sponsorInstitutionId,
            @NotBlank String phase,
            @Positive Integer targetEnrollment) {}

    /** Every field optional: a PATCH says what changed, not what the resource now is. */
    public record UpdateTrial(String title, String shortTitle, String therapeuticArea) {}

    public record ChangeStatus(@NotNull TrialStatus status) {}

    /**
     * A projection, never the entity.
     *
     * <p>Serialising entities directly is how internal columns reach the wire the first time
     * someone adds one.
     */
    public record TrialView(
            UUID id,
            String protocolNumber,
            String title,
            String shortTitle,
            UUID sponsorInstitutionId,
            String phase,
            String therapeuticArea,
            TrialStatus status,
            Integer targetEnrollment,
            int currentEnrollment,
            int version) {

        public static TrialView of(TrialEntity trial) {
            return new TrialView(
                    trial.getId(),
                    trial.getProtocolNumber(),
                    trial.getTitle(),
                    trial.getShortTitle(),
                    trial.getSponsorInstitutionId(),
                    trial.getPhase(),
                    trial.getTherapeuticArea(),
                    trial.getStatus(),
                    trial.getTargetEnrollment(),
                    trial.getCurrentEnrollment(),
                    trial.getVersion());
        }
    }
}
