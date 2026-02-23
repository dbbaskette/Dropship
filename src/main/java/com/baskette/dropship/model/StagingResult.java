package com.baskette.dropship.model;

public record StagingResult(
        String dropletGuid,
        String appGuid,
        String buildpack,
        String stagingLogs,
        long durationMs,
        boolean success,
        String errorMessage
) {
}
