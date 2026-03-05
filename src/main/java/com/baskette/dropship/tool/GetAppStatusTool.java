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
public class GetAppStatusTool {

    private static final Logger log = LoggerFactory.getLogger(GetAppStatusTool.class);

    private final AppService appService;
    private final boolean skipSslValidation;

    public GetAppStatusTool(AppService appService,
                            @Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        this.appService = appService;
        this.skipSslValidation = skipSslValidation;
    }

    @McpTool(
            name = "get_app_status",
            description = "Poll the status of a running application. Returns STARTING, RUNNING, CRASHED, or STOPPED. "
                    + "Call this after start_app to check if the app is ready to receive HTTP traffic."
    )
    public AppResult getAppStatus(
            McpSyncRequestContext context,
            @McpToolParam(description = "App GUID from prior staging result")
            String appGuid,
            @McpToolParam(description = "App name (passed through for response context)")
            String appName,
            @McpToolParam(description = "Route URL (passed through for response context)")
            String routeUrl,
            @McpToolParam(description = "Route GUID (passed through for response context)")
            String routeGuid) {

        var client = CfCredentialHelper.buildClient(context, skipSslValidation);

        if (appGuid == null || appGuid.isBlank()) {
            throw new IllegalArgumentException("appGuid must not be empty");
        }

        log.info("get_app_status invoked: appGuid={}", appGuid);

        return appService.getAppStatus(appGuid, appName, routeUrl, routeGuid, client).block();
    }
}
