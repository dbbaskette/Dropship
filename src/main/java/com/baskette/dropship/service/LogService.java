package com.baskette.dropship.service;

import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskLogs.LogEntry;
import org.cloudfoundry.logcache.v1.Envelope;
import org.cloudfoundry.logcache.v1.EnvelopeType;
import org.cloudfoundry.logcache.v1.LogCacheClient;
import org.cloudfoundry.logcache.v1.LogType;
import org.cloudfoundry.logcache.v1.ReadRequest;
import org.cloudfoundry.logcache.v1.ReadResponse;
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

    /**
     * Retrieve task logs via Log Cache REST API.
     *
     * @param taskGuid       task GUID (for tagging the result)
     * @param appGuid        app GUID used as the Log Cache source ID
     * @param maxLines       max log lines to return
     * @param source         filter: "stdout", "stderr", or "all"
     * @param logCacheClient Log Cache client for REST-based log retrieval
     */
    public Mono<TaskLogs> getTaskLogs(String taskGuid, String appGuid,
                                       Integer maxLines, String source,
                                       LogCacheClient logCacheClient) {
        int limit = maxLines != null ? maxLines : DEFAULT_MAX_LINES;
        String sourceFilter = source != null ? source : "all";

        log.info("Retrieving logs via Log Cache: taskGuid={}, appGuid={}, maxLines={}, source={}",
                taskGuid, appGuid, limit, sourceFilter);

        return logCacheClient.read(ReadRequest.builder()
                        .sourceId(appGuid)
                        .envelopeType(EnvelopeType.LOG)
                        .descending(false)
                        .limit(limit * 2)
                        .build())
                .map(response -> processResponse(taskGuid, response, limit, sourceFilter));
    }

    private TaskLogs processResponse(String taskGuid, ReadResponse response,
                                      int maxLines, String sourceFilter) {
        List<Envelope> batch = response.getEnvelopes() != null
                ? response.getEnvelopes().getBatch()
                : List.of();

        List<LogEntry> entries = batch.stream()
                .filter(env -> env.getLog() != null)
                .filter(env -> matchesSource(env.getLog().getType(), sourceFilter))
                .map(env -> new LogEntry(
                        env.getTimestamp() != null
                                ? Instant.ofEpochSecond(0, env.getTimestamp())
                                : Instant.now(),
                        env.getLog().getType() == LogType.ERR ? "stderr" : "stdout",
                        env.getLog().getPayloadAsText()))
                .sorted(Comparator.comparing(LogEntry::timestamp))
                .toList();

        int totalLines = entries.size();
        boolean truncated = totalLines > maxLines;
        List<LogEntry> result = truncated ? entries.subList(0, maxLines) : entries;

        log.info("Retrieved {} log entries for task {} via Log Cache, truncated={}",
                result.size(), taskGuid, truncated);
        return new TaskLogs(taskGuid, result, totalLines, truncated);
    }

    private boolean matchesSource(LogType logType, String source) {
        return switch (source) {
            case "stdout" -> logType == LogType.OUT;
            case "stderr" -> logType == LogType.ERR;
            default -> true;
        };
    }
}
