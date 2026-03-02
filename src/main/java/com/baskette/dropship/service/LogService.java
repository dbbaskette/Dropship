package com.baskette.dropship.service;

import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskLogs.LogEntry;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationLogType;
import org.cloudfoundry.operations.applications.ApplicationLogsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);
    private static final int DEFAULT_MAX_LINES = 500;

    private final DefaultCloudFoundryOperations cfOperations;

    public LogService(DefaultCloudFoundryOperations cfOperations) {
        this.cfOperations = cfOperations;
    }

    public Mono<TaskLogs> getTaskLogs(String taskGuid, String appName,
                                       Integer maxLines, String source) {
        int limit = maxLines != null ? maxLines : DEFAULT_MAX_LINES;
        String sourceFilter = source != null ? source : "all";

        log.info("Retrieving logs: taskGuid={}, appName={}, maxLines={}, source={}",
                taskGuid, appName, limit, sourceFilter);

        return cfOperations.applications()
                .logs(ApplicationLogsRequest.builder()
                        .name(appName)
                        .recent(true)
                        .build())
                .filter(appLog -> matchesSource(appLog.getLogType(), sourceFilter))
                .map(appLog -> new LogEntry(
                        appLog.getTimestamp() != null
                                ? Instant.ofEpochMilli(appLog.getTimestamp() / 1_000_000)
                                : Instant.now(),
                        appLog.getLogType() == ApplicationLogType.ERR ? "stderr" : "stdout",
                        appLog.getMessage()))
                .sort(Comparator.comparing(LogEntry::timestamp))
                .collectList()
                .map(entries -> toTaskLogs(taskGuid, entries, limit));
    }

    private boolean matchesSource(ApplicationLogType logType, String source) {
        return switch (source) {
            case "stdout" -> logType == ApplicationLogType.OUT;
            case "stderr" -> logType == ApplicationLogType.ERR;
            default -> true;
        };
    }

    private TaskLogs toTaskLogs(String taskGuid, List<LogEntry> entries, int maxLines) {
        int totalLines = entries.size();
        boolean truncated = totalLines > maxLines;
        List<LogEntry> result = truncated ? entries.subList(0, maxLines) : entries;

        log.info("Retrieved {} log entries for task {}, truncated={}", result.size(), taskGuid, truncated);
        return new TaskLogs(taskGuid, result, totalLines, truncated);
    }
}
