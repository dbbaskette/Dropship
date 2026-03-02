package com.baskette.dropship.model;

import java.time.Instant;
import java.util.List;

public record TaskLogs(
        String taskGuid,
        List<LogEntry> entries,
        int totalLines,
        boolean truncated
) {

    public record LogEntry(
            Instant timestamp,
            String source,
            String message
    ) {
    }
}
