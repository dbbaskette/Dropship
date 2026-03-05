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
class GetAppStatusToolTest {

    @Mock
    private AppService appService;

    @Mock
    private McpSyncRequestContext requestContext;

    private GetAppStatusTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetAppStatusTool(appService, true);
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
    void returnsRunningStatus() {
        stubHeaders(validHeaders());
        AppResult expected = new AppResult(
                "app-guid-1", "my-app",
                "https://my-app.apps.example.com", "route-guid-1",
                AppResult.State.RUNNING, null);

        when(appService.getAppStatus(
                eq("app-guid-1"), eq("my-app"),
                eq("https://my-app.apps.example.com"), eq("route-guid-1"),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        AppResult result = tool.getAppStatus(requestContext,
                "app-guid-1", "my-app",
                "https://my-app.apps.example.com", "route-guid-1");

        assertThat(result.state()).isEqualTo(AppResult.State.RUNNING);
        assertThat(result.routeUrl()).isEqualTo("https://my-app.apps.example.com");
    }

    @Test
    void returnsStartingStatus() {
        stubHeaders(validHeaders());
        AppResult expected = new AppResult(
                "app-guid-1", "my-app", null, null,
                AppResult.State.STARTING, null);

        when(appService.getAppStatus(
                eq("app-guid-1"), eq("my-app"), eq(null), eq(null),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        AppResult result = tool.getAppStatus(requestContext,
                "app-guid-1", "my-app", null, null);

        assertThat(result.state()).isEqualTo(AppResult.State.STARTING);
    }

    @Test
    void returnsCrashedStatus() {
        stubHeaders(validHeaders());
        AppResult expected = new AppResult(
                "app-guid-1", null, null, null,
                AppResult.State.CRASHED, "OOM killed");

        when(appService.getAppStatus(
                eq("app-guid-1"), eq(null), eq(null), eq(null),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        AppResult result = tool.getAppStatus(requestContext,
                "app-guid-1", null, null, null);

        assertThat(result.state()).isEqualTo(AppResult.State.CRASHED);
        assertThat(result.errorMessage()).isEqualTo("OOM killed");
    }

    @Test
    void rejectsNullAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getAppStatus(requestContext, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsEmptyAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getAppStatus(requestContext, "", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsBlankAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getAppStatus(requestContext, "   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void throwsWhenMissingCredentials() {
        stubHeaders(Map.of());
        assertThatThrownBy(() -> tool.getAppStatus(requestContext,
                "app-guid-1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");
        verifyNoInteractions(appService);
    }
}
