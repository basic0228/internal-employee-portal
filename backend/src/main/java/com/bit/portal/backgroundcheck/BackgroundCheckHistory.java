package com.bit.portal.backgroundcheck;

import java.time.OffsetDateTime;

public record BackgroundCheckHistory(String requestId, String employeeId, long requestedBy, String requestedByUsername,
        OffsetDateTime requestedAt, OffsetDateTime dispatchDeadline, String checkId, String localState, String status,
        OffsetDateTime observedAt, OffsetDateTime completedAt, Integer lastHttpCode, String lastOutcome,
        OffsetDateTime pollDeadline, OffsetDateTime nextPollAt, int pollCount, boolean pollStopped,
        String stopReason, OffsetDateTime refreshingUntil) {
    public boolean blocksCreation() {
        return "DISPATCHING".equals(localState) || "UNKNOWN".equals(localState) || "pending".equals(status);
    }
}
