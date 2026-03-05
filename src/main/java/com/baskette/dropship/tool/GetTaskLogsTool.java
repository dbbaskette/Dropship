package com.baskette.dropship.tool;

import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.service.LogService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GetTaskLogsTool {

    private static final Logger log = LoggerFactory.getLogger(GetTaskLogsTool.class);

    private final LogService logService;
    private final boolean skipSslValidation;

    public GetTaskLogsTool(LogService logService,
                           @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        this.logService = logService;
        this.skipSslValidation = skipSslValidation;
    }

    @McpTool(
            name = "get_task_logs",
            description = "Retrieve structured stdout and stderr logs from a task execution. "
                    + "Logs are sourced from Log Cache with platform/app log separation, "
                    + "structured metadata, and retention independent of container lifecycle."
    )
    public TaskLogs getTaskLogs(
            McpSyncRequestContext context,
            @McpToolParam(description = "Task GUID from prior run_task result")
            String taskGuid,
            @McpToolParam(description = "App GUID from prior stage_code or stage_git_repo result")
            String appGuid,
            @McpToolParam(description = "Maximum number of log lines to return (default: 500)")
            Integer maxLines,
            @McpToolParam(description = "Filter by source: 'stdout', 'stderr', or 'all' (default: 'all')")
            String source) {

        CfCredentialHelper.CfClients clients = CfCredentialHelper.buildClients(context, skipSslValidation);

        if (taskGuid == null || taskGuid.isBlank()) {
            throw new IllegalArgumentException("taskGuid must not be empty");
        }
        if (appGuid == null || appGuid.isBlank()) {
            throw new IllegalArgumentException("appGuid must not be empty");
        }

        log.info("get_task_logs invoked: taskGuid={}, appGuid={}, maxLines={}, source={}",
                taskGuid, appGuid, maxLines, source != null ? source : "all");

        return logService.getTaskLogs(taskGuid, appGuid, maxLines, source,
                clients.logCacheClient()).block();
    }
}
