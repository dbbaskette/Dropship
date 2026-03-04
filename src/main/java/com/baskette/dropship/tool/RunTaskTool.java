package com.baskette.dropship.tool;

import com.baskette.dropship.config.CfClientFactory;
import com.baskette.dropship.model.TaskResult;
import com.baskette.dropship.service.TaskService;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RunTaskTool {

    private static final Logger log = LoggerFactory.getLogger(RunTaskTool.class);

    private final TaskService taskService;
    private final CfClientFactory cfClientFactory;

    public RunTaskTool(TaskService taskService, CfClientFactory cfClientFactory) {
        this.taskService = taskService;
        this.cfClientFactory = cfClientFactory;
    }

    @McpTool(
            name = "run_task",
            description = "Execute a command in an isolated Diego Cell container. "
                    + "The container is provisioned from a previously staged droplet, "
                    + "enforcing org/space quotas, ASG network policies, and Garden isolation. "
                    + "Returns exit code and task GUID for log retrieval."
    )
    public TaskResult runTask(
            @McpToolParam(description = "App GUID from prior stage_code result")
            String appGuid,
            @McpToolParam(description = "Droplet GUID from prior stage_code result")
            String dropletGuid,
            @McpToolParam(description = "Shell command to execute in the container")
            String command,
            @McpToolParam(description = "Memory limit in MB for the task container")
            Integer memoryMb,
            @McpToolParam(description = "Maximum execution time in seconds")
            Integer timeoutSeconds,
            @McpToolParam(description = "Environment variables as key-value pairs")
            Map<String, String> environment) {

        ReactorCloudFoundryClient client = cfClientFactory.getClientForCurrentSession();

        if (appGuid == null || appGuid.isBlank()) {
            throw new IllegalArgumentException("appGuid must not be empty");
        }
        if (dropletGuid == null || dropletGuid.isBlank()) {
            throw new IllegalArgumentException("dropletGuid must not be empty");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        log.info("run_task invoked: appGuid={}, dropletGuid={}, command={}, memoryMb={}, timeoutSeconds={}",
                appGuid, dropletGuid, command, memoryMb, timeoutSeconds);

        return taskService.runTask(appGuid, dropletGuid, command,
                memoryMb, timeoutSeconds, environment, client).block();
    }
}
