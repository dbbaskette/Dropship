package com.baskette.dropship.model;

public record TaskResult(
        String taskGuid,
        String appGuid,
        int exitCode,
        State state,
        long durationMs,
        int memoryMb,
        String command
) {

    public enum State {
        SUCCEEDED, FAILED
    }
}
