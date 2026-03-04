package com.baskette.dropship.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
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

    private static final List<String> CF_HEADERS = List.of(
            "X-CF-ApiHost", "X-CF-Username", "X-CF-Password", "X-CF-Org", "X-CF-Space"
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
                            context.put(header, values.get(0));
                        }
                    }
                    return McpTransportContext.create(context);
                })
                .build();
    }
}
