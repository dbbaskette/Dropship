package com.baskette.dropship.tool;

import com.baskette.dropship.model.AppResult;
import com.baskette.dropship.service.AppService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StopAppToolTest {

    @Mock
    private AppService appService;

    @Mock
    private McpSyncRequestContext requestContext;

    private StopAppTool tool;

    @BeforeEach
    void setUp() {
        tool = new StopAppTool(appService, true);
    }

    private void stubHeaders(Map<String, String> headers) {
        McpTransportContext transportContext = McpTransportContext.create(Map.copyOf(headers));
        when(requestContext.transportContext()).thenReturn(transportContext);
    }

    private Map<String, String> validHeaders() {
        return Map.of(
                "cf-apihost", "api.test.example.com",
                "cf-username", "testuser",
                "cf-password", "testpass",
                "cf-org", "test-org",
                "cf-space", "test-space"
        );
    }

    @Test
    void stopAppDelegatesToAppService() {
        stubHeaders(validHeaders());
        AppResult expected = new AppResult(
                "app-guid-1", "my-app", null, "route-guid-1",
                AppResult.State.STOPPED, null);

        when(appService.stopApplication(
                eq("app-guid-1"), eq("my-app"), eq("route-guid-1"),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        AppResult result = tool.stopApp(requestContext, "app-guid-1", "my-app", "route-guid-1");

        assertThat(result.appGuid()).isEqualTo("app-guid-1");
        assertThat(result.state()).isEqualTo(AppResult.State.STOPPED);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void stopAppWithNullRouteGuid() {
        stubHeaders(validHeaders());
        AppResult expected = new AppResult(
                "app-guid-1", "my-app", null, null,
                AppResult.State.STOPPED, null);

        when(appService.stopApplication(
                eq("app-guid-1"), eq("my-app"), eq(null),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        AppResult result = tool.stopApp(requestContext, "app-guid-1", "my-app", null);

        assertThat(result.state()).isEqualTo(AppResult.State.STOPPED);
    }

    @Test
    void rejectsNullAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.stopApp(requestContext, null, "my-app", "route-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsEmptyAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.stopApp(requestContext, "", "my-app", "route-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsBlankAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.stopApp(requestContext, "   ", "my-app", "route-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void throwsWhenMissingCredentials() {
        stubHeaders(Map.of());
        assertThatThrownBy(() -> tool.stopApp(requestContext, "app-guid-1", "my-app", "route-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");
        verifyNoInteractions(appService);
    }
}
