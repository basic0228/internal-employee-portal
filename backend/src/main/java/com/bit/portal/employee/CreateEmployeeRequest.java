package com.bit.portal.employee;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateEmployeeRequest(
        @NotBlank @Pattern(regexp = "EMP-[0-9]{3,}") @Size(max = 32) String employeeId,
        @NotBlank @Size(max = 100) String fullName,
        @Past LocalDate dateOfBirth,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,64}") String username,
        @NotBlank @Size(min = 8, max = 72)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    @Override
    public String toString() { return "CreateEmployeeRequest[employeeId=" + employeeId + "]"; }
}
