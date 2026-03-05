package com.baskette.dropship.tool;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.service.BuildTracker;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GetBuildStatusTool {

    private static final Logger log = LoggerFactory.getLogger(GetBuildStatusTool.class);

    private final BuildTracker buildTracker;

    public GetBuildStatusTool(BuildTracker buildTracker) {
        this.buildTracker = buildTracker;
    }

    @McpTool(
            name = "get_build_status",
            description = "Poll the status of an async stage_git_repo build. "
                    + "Returns the final StagingResult (with droplet GUID) when complete, "
                    + "or an in-progress indicator if the build is still running. "
                    + "Call this after stage_git_repo returns a buildId."
    )
    public StagingResult getBuildStatus(
            @McpToolParam(description = "Build ID returned by stage_git_repo")
            String buildId) {

        if (buildId == null || buildId.isBlank()) {
            throw new IllegalArgumentException("buildId must not be empty");
        }

        log.info("get_build_status invoked: buildId={}", buildId);
        return buildTracker.getStatus(buildId);
    }
}
