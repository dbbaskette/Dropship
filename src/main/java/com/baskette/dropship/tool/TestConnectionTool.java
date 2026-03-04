package com.baskette.dropship.tool;

import com.baskette.dropship.model.ConnectionTestResult;
import org.cloudfoundry.client.v3.organizations.ListOrganizationsRequest;
import org.cloudfoundry.client.v3.spaces.ListSpacesRequest;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;

@Service
public class TestConnectionTool {

    private static final Logger log = LoggerFactory.getLogger(TestConnectionTool.class);

    private final boolean skipSslValidation;

    public TestConnectionTool(@Value("${cf.skip-ssl-validation:false}") boolean skipSslValidation) {
        this.skipSslValidation = skipSslValidation;
    }

    @McpTool(
            name = "test_cf_connection",
            description = "Test the Cloud Foundry connection using the provided credentials. "
                    + "Validates API reachability, authentication, and org/space resolution. "
                    + "Use this before staging code to verify your CF configuration is correct."
    )
    public ConnectionTestResult testConnection(McpSyncRequestContext context) {
        String apiHost;
        String username;
        String password;
        String org;
        String space;

        try {
            apiHost = requireHeader(context, "X-CF-ApiHost");
            username = requireHeader(context, "X-CF-Username");
            password = requireHeader(context, "X-CF-Password");
            org = requireHeader(context, "X-CF-Org");
            space = requireHeader(context, "X-CF-Space");
        } catch (Exception e) {
            log.warn("test_cf_connection failed: {}", e.getMessage());
            return new ConnectionTestResult(false, null, null, null, null, null, e.getMessage());
        }

        String host = URI.create(apiHost.startsWith("http") ? apiHost : "https://" + apiHost).getHost();
        if (host == null) {
            host = apiHost;
        }

        log.info("test_cf_connection invoked: apiHost={}, username={}, org={}, space={}",
                host, username, org, space);

        try {
            DefaultConnectionContext connectionContext = DefaultConnectionContext.builder()
                    .apiHost(host)
                    .skipSslValidation(skipSslValidation)
                    .build();

            PasswordGrantTokenProvider tokenProvider = PasswordGrantTokenProvider.builder()
                    .username(username)
                    .password(password)
                    .build();

            ReactorCloudFoundryClient client = ReactorCloudFoundryClient.builder()
                    .connectionContext(connectionContext)
                    .tokenProvider(tokenProvider)
                    .build();

            // Resolve org
            var orgResponse = client.organizationsV3()
                    .list(ListOrganizationsRequest.builder().name(org).build())
                    .block();

            if (orgResponse == null || orgResponse.getResources().isEmpty()) {
                String msg = "Organization not found: " + org;
                log.warn("test_cf_connection failed: {}", msg);
                return new ConnectionTestResult(false, host, username, org, space, null, msg);
            }

            String orgGuid = orgResponse.getResources().get(0).getId();

            // Resolve space
            var spaceResponse = client.spacesV3()
                    .list(ListSpacesRequest.builder().name(space).organizationId(orgGuid).build())
                    .block();

            if (spaceResponse == null || spaceResponse.getResources().isEmpty()) {
                String msg = "Space not found: " + space + " in organization: " + org;
                log.warn("test_cf_connection failed: {}", msg);
                return new ConnectionTestResult(false, host, username, org, space, null, msg);
            }

            String spaceGuid = spaceResponse.getResources().get(0).getId();
            log.info("test_cf_connection succeeded: spaceGuid={}", spaceGuid);
            return new ConnectionTestResult(true, host, username, org, space, spaceGuid, null);

        } catch (Exception e) {
            log.warn("test_cf_connection failed: {}", e.getMessage());
            return new ConnectionTestResult(false, host, username, org, space, null, e.getMessage());
        }
    }

    private String requireHeader(McpSyncRequestContext context, String headerName) {
        Object value = context.transportContext().get(headerName);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required header: " + headerName);
        }
        return value.toString();
    }
}
