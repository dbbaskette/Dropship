package com.baskette.dropship.tool;

import com.baskette.dropship.config.CfClientFactory;
import com.baskette.dropship.config.DropshipProperties;
import com.baskette.dropship.model.ConnectionTestResult;
import com.baskette.dropship.service.SpaceResolver;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.springaicommunity.mcp.annotation.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TestConnectionTool {

    private static final Logger log = LoggerFactory.getLogger(TestConnectionTool.class);

    private final CfClientFactory cfClientFactory;
    private final SpaceResolver spaceResolver;
    private final DropshipProperties properties;

    public TestConnectionTool(CfClientFactory cfClientFactory,
                              SpaceResolver spaceResolver,
                              DropshipProperties properties) {
        this.cfClientFactory = cfClientFactory;
        this.spaceResolver = spaceResolver;
        this.properties = properties;
    }

    @McpTool(
            name = "test_cf_connection",
            description = "Test the Cloud Foundry connection using the current session credentials. "
                    + "Validates API reachability, authentication, and org/space resolution. "
                    + "Use this before staging code to verify your CF configuration is correct."
    )
    public ConnectionTestResult testConnection() {
        String apiHost = extractHost(properties.cfApiUrl());
        String org = properties.sandboxOrg();
        String space = properties.sandboxSpace();

        log.info("test_cf_connection invoked: apiHost={}, org={}, space={}", apiHost, org, space);

        ReactorCloudFoundryClient client;
        try {
            client = cfClientFactory.getClientForCurrentSession();
        } catch (Exception e) {
            log.warn("test_cf_connection failed: no session client available", e);
            return new ConnectionTestResult(false, apiHost, org, space, null, e.getMessage());
        }

        try {
            String spaceGuid = spaceResolver.resolveSpace(client).block();
            log.info("test_cf_connection succeeded: spaceGuid={}", spaceGuid);
            return new ConnectionTestResult(true, apiHost, org, space, spaceGuid, null);
        } catch (Exception e) {
            log.warn("test_cf_connection failed: {}", e.getMessage());
            return new ConnectionTestResult(false, apiHost, org, space, null, e.getMessage());
        }
    }

    private String extractHost(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        return java.net.URI.create(apiUrl).getHost();
    }
}
