package com.baskette.dropship.tool;

import com.baskette.dropship.config.CfClientFactory;
import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.service.StagingService;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StageCodeToolTest {

    @Mock
    private StagingService stagingService;

    @Mock
    private CfClientFactory cfClientFactory;

    @Mock
    private ReactorCloudFoundryClient cfClient;

    @Mock
    private DefaultCloudFoundryOperations cfOperations;

    private StageCodeTool stageCodeTool;

    @BeforeEach
    void setUp() {
        stageCodeTool = new StageCodeTool(stagingService, cfClientFactory);
    }

    private void stubClientFactory() {
        when(cfClientFactory.getClientForCurrentSession()).thenReturn(cfClient);
        when(cfClientFactory.getOperationsForCurrentSession()).thenReturn(cfOperations);
    }

    @Test
    void stageCodeDecodesBase64AndDelegatesToStagingService() {
        stubClientFactory();
        byte[] sourceBytes = "test source content".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                "droplet-guid-201", "app-guid-456", "dropship-testapp", "java_buildpack",
                "Staging completed successfully", 1500L, true, null);
        when(stagingService.stage(any(byte[].class), eq("java_buildpack"), eq(512), eq(1024),
                eq(cfClient), eq(cfOperations)))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(base64Source, "java_buildpack", 512, 1024);

        assertThat(result.success()).isTrue();
        assertThat(result.dropletGuid()).isEqualTo("droplet-guid-201");
        assertThat(result.buildpack()).isEqualTo("java_buildpack");

        verify(stagingService).stage(eq(sourceBytes), eq("java_buildpack"), eq(512), eq(1024),
                eq(cfClient), eq(cfOperations));
    }

    @Test
    void stageCodePassesNullOptionalParameters() {
        stubClientFactory();
        byte[] sourceBytes = "test source".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                "droplet-guid-201", "app-guid-456", "dropship-testapp", null,
                "Staging completed successfully", 1200L, true, null);
        when(stagingService.stage(any(byte[].class), isNull(), isNull(), isNull(),
                eq(cfClient), eq(cfOperations)))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(base64Source, null, null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.buildpack()).isNull();

        verify(stagingService).stage(eq(sourceBytes), isNull(), isNull(), isNull(),
                eq(cfClient), eq(cfOperations));
    }

    @Test
    void stageCodeRejectsNullSourceBundle() {
        stubClientFactory();
        assertThatThrownBy(() -> stageCodeTool.stageCode(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsEmptySourceBundle() {
        stubClientFactory();
        assertThatThrownBy(() -> stageCodeTool.stageCode("", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsBlankSourceBundle() {
        stubClientFactory();
        assertThatThrownBy(() -> stageCodeTool.stageCode("   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsInvalidBase64() {
        stubClientFactory();
        assertThatThrownBy(() -> stageCodeTool.stageCode("not-valid-base64!!!", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceBundle is not valid base64");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsBase64ThatDecodesToEmpty() {
        stubClientFactory();
        String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);

        assertThatThrownBy(() -> stageCodeTool.stageCode(emptyBase64, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeReturnsStagingFailureResult() {
        stubClientFactory();
        byte[] sourceBytes = "bad source".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                null, "app-guid-456", "dropship-testapp", "java_buildpack",
                "Buildpack compilation failed", 3000L, false,
                "Buildpack compilation failed");
        when(stagingService.stage(any(byte[].class), eq("java_buildpack"), isNull(), isNull(),
                eq(cfClient), eq(cfOperations)))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(base64Source, "java_buildpack", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.dropletGuid()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Buildpack compilation failed");
    }

    @Test
    void stageCodeThrowsWhenNoSessionCredentials() {
        when(cfClientFactory.getClientForCurrentSession())
                .thenThrow(new IllegalStateException(
                        "No CF credentials found for this session. Call connect_cf first."));

        assertThatThrownBy(() -> stageCodeTool.stageCode(
                Base64.getEncoder().encodeToString("test".getBytes()), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CF credentials found for this session");

        verifyNoInteractions(stagingService);
    }
}
