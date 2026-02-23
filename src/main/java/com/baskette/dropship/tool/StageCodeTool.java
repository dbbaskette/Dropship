package com.baskette.dropship.tool;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.service.StagingService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;

@Service
public class StageCodeTool {

    private static final Logger log = LoggerFactory.getLogger(StageCodeTool.class);

    private final StagingService stagingService;

    public StageCodeTool(StagingService stagingService) {
        this.stagingService = stagingService;
    }

    @McpTool(
            name = "stage_code",
            description = "Stage source code through Cloud Foundry buildpack pipeline. "
                    + "Validates compilation and dependency resolution in an isolated environment. "
                    + "Returns a droplet GUID for subsequent execution via run_task."
    )
    public StagingResult stageCode(
            @McpToolParam(description = "Base64-encoded source bundle (tarball or zip)")
            String sourceBundle,
            @McpToolParam(description = "Buildpack hint: java_buildpack, nodejs_buildpack, python_buildpack, go_buildpack, etc.")
            String buildpack,
            @McpToolParam(description = "Memory limit in MB for staging (default: 1024)")
            Integer memoryMb,
            @McpToolParam(description = "Disk limit in MB for staging (default: 2048)")
            Integer diskMb) {

        if (sourceBundle == null || sourceBundle.isBlank()) {
            throw new IllegalArgumentException("sourceBundle must not be empty");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(sourceBundle);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sourceBundle is not valid base64: " + e.getMessage());
        }

        if (decoded.length == 0) {
            throw new IllegalArgumentException("sourceBundle must not be empty");
        }

        log.info("stage_code invoked: bundleSize={} bytes, buildpack={}, memoryMb={}, diskMb={}",
                decoded.length,
                buildpack != null ? buildpack : "auto-detect",
                memoryMb,
                diskMb);

        return stagingService.stage(decoded, buildpack, memoryMb, diskMb).block();
    }
}
