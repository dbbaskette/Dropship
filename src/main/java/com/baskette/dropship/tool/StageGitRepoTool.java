package com.baskette.dropship.tool;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.model.TaskResult;
import com.baskette.dropship.service.BuildTracker;
import com.baskette.dropship.service.GitCloneService;
import com.baskette.dropship.service.StagingService;
import com.baskette.dropship.service.TaskService;
import org.cloudfoundry.logcache.v1.LogCacheClient;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StageGitRepoTool {

    private static final Logger log = LoggerFactory.getLogger(StageGitRepoTool.class);

    private final GitCloneService gitCloneService;
    private final StagingService stagingService;
    private final TaskService taskService;
    private final BuildTracker buildTracker;
    private final boolean skipSslValidation;

    public StageGitRepoTool(GitCloneService gitCloneService,
                            StagingService stagingService,
                            TaskService taskService,
                            BuildTracker buildTracker,
                            @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        this.gitCloneService = gitCloneService;
        this.stagingService = stagingService;
        this.taskService = taskService;
        this.buildTracker = buildTracker;
        this.skipSslValidation = skipSslValidation;
    }

    @McpTool(
            name = "stage_git_repo",
            description = "Stage source code from a public git repository through the Cloud Foundry "
                    + "buildpack pipeline. The server clones the repo remotely — do NOT clone locally, "
                    + "just pass the git URL. Use this instead of stage_code when source is in a git repo. "
                    + "Java projects (Maven/Gradle) are automatically detected, built on the server, "
                    + "and the compiled JAR is staged. "
                    + "If a command is provided, a task is automatically run after staging succeeds. "
                    + "Returns immediately with a buildId. Poll get_build_status(buildId) for the result."
    )
    public StagingResult stageGitRepo(
            McpSyncRequestContext context,
            @McpToolParam(description = "Git repository URL (HTTPS)")
            String repoUrl,
            @McpToolParam(description = "Branch or tag to checkout (default: repo default branch)")
            String branch,
            @McpToolParam(description = "Subdirectory within the repo to stage (default: repo root)")
            String subdirectory,
            @McpToolParam(description = "Buildpack hint: java_buildpack, nodejs_buildpack, etc.")
            String buildpack,
            @McpToolParam(description = "Memory limit in MB for staging (default: 1024)")
            Integer memoryMb,
            @McpToolParam(description = "Disk limit in MB for staging (default: 2048)")
            Integer diskMb,
            @McpToolParam(description = "Shell command to execute after staging succeeds (optional). "
                    + "If provided, a task is automatically run using the staged droplet.")
            String command) {

        String org = CfCredentialHelper.requireHeader(context, CfCredentialHelper.KEY_ORG);
        String space = CfCredentialHelper.requireHeader(context, CfCredentialHelper.KEY_SPACE);
        CfCredentialHelper.CfClients clients = CfCredentialHelper.buildClients(context, skipSslValidation);
        ReactorCloudFoundryClient client = clients.client();
        LogCacheClient logCacheClient = clients.logCacheClient();

        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl must not be empty");
        }

        log.info("stage_git_repo invoked: repoUrl={}, branch={}, subdirectory={}, buildpack={}, command={}",
                repoUrl,
                branch != null ? branch : "(default)",
                subdirectory != null ? subdirectory : "(root)",
                buildpack != null ? buildpack : "auto-detect",
                command != null ? command : "(none)");

        String buildId = buildTracker.submit(() -> {
            byte[] sourceBytes = gitCloneService.cloneAndZip(repoUrl, branch, subdirectory).block();
            StagingResult stagingResult = stagingService.stage(sourceBytes, buildpack, memoryMb, diskMb,
                    org, space, client, logCacheClient).block();

            if (command != null && !command.isBlank() && stagingResult != null && stagingResult.success()) {
                log.info("Staging succeeded, auto-running task: command={}", command);
                TaskResult taskResult = taskService.runTask(
                        stagingResult.appGuid(), stagingResult.dropletGuid(),
                        command, memoryMb, null, null, client).block();

                if (taskResult != null) {
                    return stagingResult.withTaskResult(
                            taskResult.taskGuid(), taskResult.exitCode(), taskResult.command());
                }
            }

            return stagingResult;
        });

        return new StagingResult(null, null, null, buildpack, null, 0, false,
                "Build submitted. Poll get_build_status with buildId: " + buildId);
    }
}
