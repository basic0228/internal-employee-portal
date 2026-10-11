package com.bit.portal.lab.store;

public record LabStoredRun(String runId, long ownerAccountId, String campaignId, String caseId,
        String instanceId, String status, int plannedCalls, int observedCalls, String snapshotJson) { }
