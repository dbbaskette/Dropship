package com.baskette.dropship.tool;

import org.cloudfoundry.logcache.v1.LogCacheClient;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.logcache.v1.ReactorLogCacheClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.springaicommunity.mcp.context.McpSyncRequestContext;

import java.net.URI;

/**
 * Extracts per-user CF credentials from MCP transport context headers
 * and builds CF client objects. Shared across all MCP tools.
 */
final class CfCredentialHelper {

    static final String KEY_API_HOST = "cf-apihost";
    static final String KEY_USERNAME = "cf-username";
    static final String KEY_PASSWORD = "cf-password";
    static final String KEY_ORG = "cf-org";
    static final String KEY_SPACE = "cf-space";

    private CfCredentialHelper() {}

    /**
     * Holds the CF connection components needed by tools.
     */
    record CfClients(ReactorCloudFoundryClient client,
                     LogCacheClient logCacheClient,
                     DefaultConnectionContext connectionContext,
                     PasswordGrantTokenProvider tokenProvider) {}

    static String requireHeader(McpSyncRequestContext context, String headerName) {
        Object value = context.transportContext().get(headerName);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required header: " + headerName);
        }
        return value.toString();
    }

    static String extractHost(String apiHost) {
        String host = URI.create(apiHost.startsWith("http") ? apiHost : "https://" + apiHost).getHost();
        return host != null ? host : apiHost;
    }

    /**
     * Build all CF connection components from MCP headers.
     * Includes CF API client and Log Cache client (REST-based log retrieval).
     */
    static CfClients buildClients(McpSyncRequestContext context, boolean skipSslValidation) {
        String apiHost = requireHeader(context, KEY_API_HOST);
        String username = requireHeader(context, KEY_USERNAME);
        String password = requireHeader(context, KEY_PASSWORD);

        String host = extractHost(apiHost);

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

        LogCacheClient logCacheClient = ReactorLogCacheClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();

        return new CfClients(client, logCacheClient, connectionContext, tokenProvider);
    }

    /**
     * Build just the CF API client. Use for tools that don't need log retrieval.
     */
    static ReactorCloudFoundryClient buildClient(McpSyncRequestContext context,
                                                  boolean skipSslValidation) {
        return buildClients(context, skipSslValidation).client();
    }

    /**
     * Build high-level operations (for app management, not log retrieval).
     */
    static DefaultCloudFoundryOperations buildOperations(CfClients clients,
                                                          String org, String space) {
        return DefaultCloudFoundryOperations.builder()
                .cloudFoundryClient(clients.client())
                .organization(org)
                .space(space)
                .build();
    }
}
