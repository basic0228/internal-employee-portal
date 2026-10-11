package com.bit.portal.lab;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record LabRequest(
        @NotBlank @Size(max=256) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String candidateKey,
        @NotNull Type type, @NotNull Endpoint endpoint,
        @Size(max=128) String employeeId, @Size(max=128) String checkId,
        @Size(max=100) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String firstName,
        @Size(max=100) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String lastName,
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String dateOfBirth,
        @Min(1) @Max(20) int repeats, @Min(1) @Max(3) int concurrency,
        @Min(1) @Max(30000) int connectTimeoutMs, @Min(1) @Max(60000) int requestTimeoutMs,
        @Min(1) @Max(120000) int durationMs, @Min(0) @Max(10000) int intervalMs,
        @Min(1) @Max(60000) int pollIntervalMs, @Min(1) @Max(19) int maxPolls,
        @Min(1) @Max(20) int callBudget, boolean postAcknowledged) {
    public enum Type { GET_BASELINE, GET_CONCURRENCY, CREATE_POLL, REPEAT_POST }
    public enum Endpoint { LIST, DETAIL, CREATE }
    @Override public String toString() { return "LabRequest[type="+type+", input=REDACTED]"; }
}
