package com.baskette.dropship.tool;

import com.baskette.dropship.model.TaskResult;
import com.baskette.dropship.service.TaskService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GetTaskStatusTool {

    private static final Logger log = LoggerFactory.getLogger(GetTaskStatusTool.class);

    private final TaskService taskService;
    private final boolean skipSslValidation;

    public GetTaskStatusTool(TaskService taskService,
                             @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        this.taskService = taskService;
        this.skipSslValidation = skipSslValidation;
    }

    @McpTool(
            name = "get_task_status",
            description = "Poll the status of a running task. Returns RUNNING, SUCCEEDED, or FAILED. "
                    + "Call this after run_task to check if the task has completed."
    )
    public TaskResult getTaskStatus(
            McpSyncRequestContext context,
            @McpToolParam(description = "Task GUID from prior run_task result")
            String taskGuid,
            @McpToolParam(description = "App GUID from prior staging result")
            String appGuid) {

        var client = CfCredentialHelper.buildClient(context, skipSslValidation);

        if (taskGuid == null || taskGuid.isBlank()) {
            throw new IllegalArgumentException("taskGuid must not be empty");
        }
        if (appGuid == null || appGuid.isBlank()) {
            throw new IllegalArgumentException("appGuid must not be empty");
        }

        log.info("get_task_status invoked: taskGuid={}, appGuid={}", taskGuid, appGuid);

        return taskService.getTaskStatus(taskGuid, appGuid, client).block();
    }
}
