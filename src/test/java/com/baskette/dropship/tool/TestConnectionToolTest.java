package com.baskette.dropship.tool;

import com.baskette.dropship.model.ConnectionTestResult;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.mcp.context.McpSyncRequestContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestConnectionToolTest {

    @Mock
    private McpSyncRequestContext requestContext;

    private TestConnectionTool testConnectionTool;

    @BeforeEach
    void setUp() {
        testConnectionTool = new TestConnectionTool(true);
    }

    private void stubHeaders(Map<String, String> headers) {
        McpTransportContext transportContext = McpTransportContext.create(Map.copyOf(headers));
        when(requestContext.transportContext()).thenReturn(transportContext);
    }

    @Test
    void missingHeadersReturnsError() {
        stubHeaders(Map.of());

        ConnectionTestResult result = testConnectionTool.testConnection(requestContext);

        assertThat(result.success()).isFalse();
        assertThat(result.apiHost()).isNull();
        assertThat(result.username()).isNull();
        assertThat(result.spaceGuid()).isNull();
        assertThat(result.errorMessage()).contains("Missing required header");
    }

    @Test
    void missingPasswordReturnsError() {
        stubHeaders(Map.of(
                "cf-apihost", "api.test.example.com",
                "cf-username", "testuser",
                "cf-org", "test-org",
                "cf-space", "test-space"
        ));

        ConnectionTestResult result = testConnectionTool.testConnection(requestContext);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Missing required header: cf-password");
    }

    @Test
    void blankHeaderReturnsError() {
        stubHeaders(Map.of(
                "cf-apihost", "api.test.example.com",
                "cf-username", "",
                "cf-password", "testpass",
                "cf-org", "test-org",
                "cf-space", "test-space"
        ));

        ConnectionTestResult result = testConnectionTool.testConnection(requestContext);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Missing required header: cf-username");
    }
}
