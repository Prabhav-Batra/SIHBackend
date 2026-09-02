package com.sih26046.ctms.clinical;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** Everything needed to enrol one participant in a single call (§14.6). */
public record EnrollmentRequest(
        @NotNull UUID trialId,
        @NotNull UUID trialSiteId,
        @NotBlank String subjectCode,
        Integer dateOfBirthYear,
        String sex,
        @NotNull @Valid Identity identity,
        @NotNull @Valid Consent consent) {

    /**
     * Identifying details, accepted here and never returned.
     *
     * <p>Enrolment is the one moment identity legitimately crosses the API boundary — someone
     * has to record who the participant is. It goes straight to participant_identities and is
     * absent from the response.
     */
    public record Identity(
            @NotBlank String fullName, LocalDate dateOfBirth, String phone) {}

    public record Consent(@NotBlank String consentVersion, @NotBlank String consentMethod) {}
}
