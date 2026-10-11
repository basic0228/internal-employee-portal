package com.bit.portal.backgroundcheck;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public record CreateBackgroundCheckRequest(@NotNull UUID requestId,
        @NotBlank @Size(max=100) String firstName, @NotBlank @Size(max=100) String lastName,
        @NotNull @Past LocalDate dateOfBirth, boolean confirmRecheck) {
    @Override public String toString() { return "CreateBackgroundCheckRequest[payload=REDACTED]"; }
}
