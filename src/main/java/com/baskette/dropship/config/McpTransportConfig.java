package com.baskette.dropship.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Overrides the default MCP transport provider to extract X-CF-* headers
 * from HTTP requests into the McpTransportContext, making per-user
 * CF credentials available to MCP tools.
 */
@Configuration
public class McpTransportConfig {

    private static final Logger log = LoggerFactory.getLogger(McpTransportConfig.class);

    // Canonical keys used in the transport context (lowercase for case-insensitive lookup)
    private static final String KEY_API_HOST = "cf-apihost";
    private static final String KEY_USERNAME = "cf-username";
    private static final String KEY_PASSWORD = "cf-password";
    private static final String KEY_ORG = "cf-org";
    private static final String KEY_SPACE = "cf-space";

    // HTTP header names to search for.
    // Nexus env var convention: CF_APIHOST → X-Cf_apihost (underscore preserved)
    // Also support hyphenated form for curl/direct clients: X-CF-APIHOST
    private static final List<String> CF_HEADERS = List.of(
            "X-Cf_apihost", "X-Cf_username", "X-Cf_password", "X-Cf_org", "X-Cf_space",
            "X-CF-APIHOST", "X-CF-USERNAME", "X-CF-PASSWORD", "X-CF-ORG", "X-CF-SPACE"
    );

    // Map HTTP header (lowercased) → canonical context key
    private static final Map<String, String> HEADER_TO_KEY = Map.ofEntries(
            Map.entry("x-cf_apihost", KEY_API_HOST),
            Map.entry("x-cf_username", KEY_USERNAME),
            Map.entry("x-cf_password", KEY_PASSWORD),
            Map.entry("x-cf_org", KEY_ORG),
            Map.entry("x-cf_space", KEY_SPACE),
            Map.entry("x-cf-apihost", KEY_API_HOST),
            Map.entry("x-cf-username", KEY_USERNAME),
            Map.entry("x-cf-password", KEY_PASSWORD),
            Map.entry("x-cf-org", KEY_ORG),
            Map.entry("x-cf-space", KEY_SPACE)
    );

    @Bean
    public WebFluxStreamableServerTransportProvider webFluxStreamableServerTransportProvider(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
            McpServerStreamableHttpProperties serverProperties) {

        return WebFluxStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(serverProperties.getMcpEndpoint())
                .keepAliveInterval(serverProperties.getKeepAliveInterval())
                .disallowDelete(serverProperties.isDisallowDelete())
                .contextExtractor(request -> {
                    Map<String, Object> context = new HashMap<>();
                    for (String header : CF_HEADERS) {
                        List<String> values = request.headers().header(header);
                        if (!values.isEmpty()) {
                            String key = HEADER_TO_KEY.get(header.toLowerCase());
                            context.put(key, values.get(0));
                        }
                    }
                    return McpTransportContext.create(context);
                })
                .build();
    }
}
