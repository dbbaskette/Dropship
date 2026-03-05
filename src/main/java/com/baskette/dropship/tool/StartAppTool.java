package com.baskette.dropship.tool;

import com.baskette.dropship.model.AppResult;
import com.baskette.dropship.service.AppService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StartAppTool {

    private static final Logger log = LoggerFactory.getLogger(StartAppTool.class);

    private final AppService appService;
    private final boolean skipSslValidation;

    public StartAppTool(AppService appService,
                        @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        this.appService = appService;
        this.skipSslValidation = skipSslValidation;
    }

    @McpTool(
            name = "start_app",
            description = "Start a staged application as a long-running web process with an HTTP route. "
                    + "Use after stage_code or stage_git_repo to run the app as a web server. "
                    + "Returns immediately with STARTING state and a route URL. "
                    + "Poll get_app_status(appGuid) until RUNNING, then hit the URL."
    )
    public AppResult startApp(
            McpSyncRequestContext context,
            @McpToolParam(description = "App GUID from prior staging result")
            String appGuid,
            @McpToolParam(description = "App name from prior staging result (used as route hostname)")
            String appName,
            @McpToolParam(description = "Droplet GUID from prior staging result")
            String dropletGuid) {

        String org = CfCredentialHelper.requireHeader(context, CfCredentialHelper.KEY_ORG);
        String space = CfCredentialHelper.requireHeader(context, CfCredentialHelper.KEY_SPACE);
        var client = CfCredentialHelper.buildClient(context, skipSslValidation);

        if (appGuid == null || appGuid.isBlank()) {
            throw new IllegalArgumentException("appGuid must not be empty");
        }
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName must not be empty");
        }
        if (dropletGuid == null || dropletGuid.isBlank()) {
            throw new IllegalArgumentException("dropletGuid must not be empty");
        }

        log.info("start_app invoked: appGuid={}, appName={}, dropletGuid={}", appGuid, appName, dropletGuid);

        return appService.startApplication(appGuid, appName, dropletGuid, org, space, client).block();
    }
}
