package com.bit.portal.lab;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Plans contain conditions only: targets and authentication are supplied per bounded execution. */
public record LabCampaignRequest(@NotEmpty @Size(max=12) List<@NotNull @Valid Case> cases) {
    public enum Unit { REQUESTS }
    public record Case(@NotNull LabRequest.Type type,@NotNull LabRequest.Endpoint endpoint,
            @NotNull Unit unit,@Min(1) @Max(100) int goal,
            @Min(1) @Max(3) int concurrency,@Min(1) @Max(30000) int connectTimeoutMs,
            @Min(1) @Max(60000) int requestTimeoutMs,@Min(0) @Max(10000) int intervalMs,
            @Min(1) @Max(60000) int pollIntervalMs,@Min(1) @Max(19) int maxPolls) {
        public boolean matches(LabRequest r) {
            return type==r.type() && endpoint==r.endpoint() && concurrency==r.concurrency()
                && connectTimeoutMs==r.connectTimeoutMs() && requestTimeoutMs==r.requestTimeoutMs()
                && intervalMs==r.intervalMs() && pollIntervalMs==r.pollIntervalMs()
                && (type==LabRequest.Type.CREATE_POLL ? r.maxPolls()>=0 && r.maxPolls()<=maxPolls : maxPolls==r.maxPolls());
        }
    }
}
