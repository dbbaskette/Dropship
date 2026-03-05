package com.baskette.dropship.model;

public record StagingResult(
        String dropletGuid,
        String appGuid,
        String appName,
        String buildpack,
        String stagingLogs,
        long durationMs,
        boolean success,
        String errorMessage,
        String taskGuid,
        Integer taskExitCode,
        String taskCommand,
        String detectedCommand
) {

    /**
     * Backward-compatible constructor for staging-only results (no task execution).
     */
    public StagingResult(String dropletGuid, String appGuid, String appName, String buildpack,
                         String stagingLogs, long durationMs, boolean success, String errorMessage) {
        this(dropletGuid, appGuid, appName, buildpack, stagingLogs, durationMs, success, errorMessage,
                null, null, null, null);
    }

    /**
     * Returns a new StagingResult with the detected start command from the droplet.
     */
    public StagingResult withDetectedCommand(String detectedCommand) {
        return new StagingResult(dropletGuid, appGuid, appName, buildpack, stagingLogs,
                durationMs, success, errorMessage, taskGuid, taskExitCode, taskCommand,
                detectedCommand);
    }

    /**
     * Returns a new StagingResult with task execution details appended.
     * Success is determined by the task exit code (0 = success).
     */
    public StagingResult withTaskResult(String taskGuid, int exitCode, String command) {
        return new StagingResult(dropletGuid, appGuid, appName, buildpack, stagingLogs,
                durationMs, exitCode == 0, errorMessage, taskGuid, exitCode, command,
                detectedCommand);
    }
}
