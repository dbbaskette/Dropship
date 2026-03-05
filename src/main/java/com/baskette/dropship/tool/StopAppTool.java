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
public class StopAppTool {

    private static final Logger log = LoggerFactory.getLogger(StopAppTool.class);

    private final AppService appService;
    private final boolean skipSslValidation;

    public StopAppTool(AppService appService,
                       @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        this.appService = appService;
        this.skipSslValidation = skipSslValidation;
    }

    @McpTool(
            name = "stop_app",
            description = "Stop a running application and clean up its route. "
                    + "Call this when you are done testing the app to release resources."
    )
    public AppResult stopApp(
            McpSyncRequestContext context,
            @McpToolParam(description = "App GUID from prior staging result")
            String appGuid,
            @McpToolParam(description = "App name (passed through for response context)")
            String appName,
            @McpToolParam(description = "Route GUID from prior start_app result (for route cleanup)")
            String routeGuid) {

        var client = CfCredentialHelper.buildClient(context, skipSslValidation);

        if (appGuid == null || appGuid.isBlank()) {
            throw new IllegalArgumentException("appGuid must not be empty");
        }

        log.info("stop_app invoked: appGuid={}, routeGuid={}", appGuid, routeGuid);

        return appService.stopApplication(appGuid, appName, routeGuid, client).block();
    }
}
