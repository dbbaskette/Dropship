package com.baskette.dropship.tool;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.service.StagingService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.cloudfoundry.logcache.v1.LogCacheClient;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StageCodeToolTest {

    @Mock
    private StagingService stagingService;

    @Mock
    private McpSyncRequestContext requestContext;

    private StageCodeTool stageCodeTool;

    @BeforeEach
    void setUp() {
        stageCodeTool = new StageCodeTool(stagingService, true);
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
    void stageCodeDecodesBase64AndDelegatesToStagingService() {
        stubHeaders(validHeaders());
        byte[] sourceBytes = "test source content".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                "droplet-guid-201", "app-guid-456", "dropship-testapp", "java_buildpack",
                "Staging completed successfully", 1500L, true, null);
        when(stagingService.stage(eq(sourceBytes), eq("java_buildpack"), eq(512), eq(1024),
                eq("test-org"), eq("test-space"),
                any(ReactorCloudFoundryClient.class), any(org.cloudfoundry.logcache.v1.LogCacheClient.class)))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(requestContext, base64Source, "java_buildpack", 512, 1024);

        assertThat(result.success()).isTrue();
        assertThat(result.dropletGuid()).isEqualTo("droplet-guid-201");
        assertThat(result.buildpack()).isEqualTo("java_buildpack");
    }

    @Test
    void stageCodePassesNullOptionalParameters() {
        stubHeaders(validHeaders());
        byte[] sourceBytes = "test source".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                "droplet-guid-201", "app-guid-456", "dropship-testapp", null,
                "Staging completed successfully", 1200L, true, null);
        when(stagingService.stage(eq(sourceBytes), isNull(), isNull(), isNull(),
                eq("test-org"), eq("test-space"),
                any(ReactorCloudFoundryClient.class), any(org.cloudfoundry.logcache.v1.LogCacheClient.class)))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(requestContext, base64Source, null, null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.buildpack()).isNull();
    }

    @Test
    void stageCodeRejectsNullSourceBundle() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> stageCodeTool.stageCode(requestContext, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsEmptySourceBundle() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> stageCodeTool.stageCode(requestContext, "", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsBlankSourceBundle() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> stageCodeTool.stageCode(requestContext, "   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsInvalidBase64() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> stageCodeTool.stageCode(requestContext, "not-valid-base64!!!", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceBundle is not valid base64");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsBase64ThatDecodesToEmpty() {
        stubHeaders(validHeaders());
        String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);

        assertThatThrownBy(() -> stageCodeTool.stageCode(requestContext, emptyBase64, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeReturnsStagingFailureResult() {
        stubHeaders(validHeaders());
        byte[] sourceBytes = "bad source".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                null, "app-guid-456", "dropship-testapp", "java_buildpack",
                "Buildpack compilation failed", 3000L, false,
                "Buildpack compilation failed");
        when(stagingService.stage(any(byte[].class), eq("java_buildpack"), isNull(), isNull(),
                eq("test-org"), eq("test-space"),
                any(ReactorCloudFoundryClient.class), any(org.cloudfoundry.logcache.v1.LogCacheClient.class)))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(requestContext, base64Source, "java_buildpack", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.dropletGuid()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Buildpack compilation failed");
    }

    @Test
    void stageCodeThrowsWhenMissingCredentials() {
        stubHeaders(Map.of());

        assertThatThrownBy(() -> stageCodeTool.stageCode(
                requestContext,
                Base64.getEncoder().encodeToString("test".getBytes()), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");

        verifyNoInteractions(stagingService);
    }
}
