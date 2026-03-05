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
class StartAppToolTest {

    @Mock
    private AppService appService;

    @Mock
    private McpSyncRequestContext requestContext;

    private StartAppTool tool;

    @BeforeEach
    void setUp() {
        tool = new StartAppTool(appService, true);
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
    void startAppDelegatesToAppService() {
        stubHeaders(validHeaders());
        AppResult expected = new AppResult(
                "app-guid-1", "my-app",
                "https://my-app.apps.example.com", "route-guid-1",
                AppResult.State.STARTING, null);

        when(appService.startApplication(
                eq("app-guid-1"), eq("my-app"), eq("droplet-guid-1"),
                eq("test-org"), eq("test-space"),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        AppResult result = tool.startApp(requestContext, "app-guid-1", "my-app", "droplet-guid-1");

        assertThat(result.appGuid()).isEqualTo("app-guid-1");
        assertThat(result.appName()).isEqualTo("my-app");
        assertThat(result.routeUrl()).isEqualTo("https://my-app.apps.example.com");
        assertThat(result.routeGuid()).isEqualTo("route-guid-1");
        assertThat(result.state()).isEqualTo(AppResult.State.STARTING);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void rejectsNullAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.startApp(requestContext, null, "my-app", "droplet-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsEmptyAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.startApp(requestContext, "", "my-app", "droplet-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsNullAppName() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.startApp(requestContext, "app-guid-1", null, "droplet-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appName must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsEmptyAppName() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.startApp(requestContext, "app-guid-1", "", "droplet-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appName must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsNullDropletGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.startApp(requestContext, "app-guid-1", "my-app", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void rejectsEmptyDropletGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.startApp(requestContext, "app-guid-1", "my-app", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");
        verifyNoInteractions(appService);
    }

    @Test
    void throwsWhenMissingCredentials() {
        stubHeaders(Map.of());
        assertThatThrownBy(() -> tool.startApp(requestContext, "app-guid-1", "my-app", "droplet-guid-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");
        verifyNoInteractions(appService);
    }
}
