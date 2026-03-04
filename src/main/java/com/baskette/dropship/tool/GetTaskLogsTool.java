package com.baskette.dropship.tool;

import com.baskette.dropship.config.CfClientFactory;
import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.service.LogService;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GetTaskLogsTool {

    private static final Logger log = LoggerFactory.getLogger(GetTaskLogsTool.class);

    private final LogService logService;
    private final CfClientFactory cfClientFactory;

    public GetTaskLogsTool(LogService logService, CfClientFactory cfClientFactory) {
        this.logService = logService;
        this.cfClientFactory = cfClientFactory;
    }

    @McpTool(
            name = "get_task_logs",
            description = "Retrieve structured stdout and stderr logs from a task execution. "
                    + "Logs are sourced from Loggregator with platform/app log separation, "
                    + "structured metadata, and retention independent of container lifecycle."
    )
    public TaskLogs getTaskLogs(
            @McpToolParam(description = "Task GUID from prior run_task result")
            String taskGuid,
            @McpToolParam(description = "App name associated with the task")
            String appName,
            @McpToolParam(description = "Maximum number of log lines to return (default: 500)")
            Integer maxLines,
            @McpToolParam(description = "Filter by source: 'stdout', 'stderr', or 'all' (default: 'all')")
            String source) {

        DefaultCloudFoundryOperations operations = cfClientFactory.getOperationsForCurrentSession();

        if (taskGuid == null || taskGuid.isBlank()) {
            throw new IllegalArgumentException("taskGuid must not be empty");
        }
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName must not be empty");
        }

        log.info("get_task_logs invoked: taskGuid={}, appName={}, maxLines={}, source={}",
                taskGuid, appName, maxLines, source != null ? source : "all");

        return logService.getTaskLogs(taskGuid, appName, maxLines, source, operations).block();
    }
}
