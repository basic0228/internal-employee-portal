package com.bit.portal.employee;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Replaces only the two contact fields; an empty value clears that field. */
public record ContactUpdateRequest(
        @Email @Size(max = 254) String email,
        @Size(max = 32) @Pattern(regexp = "^$|^(?=.*[0-9])[0-9+() .-]+$") String phone) {
    public ContactUpdateRequest {
        email = normalize(email);
        phone = normalize(phone);
    }
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Only email and phone may be changed.");
    }
}
