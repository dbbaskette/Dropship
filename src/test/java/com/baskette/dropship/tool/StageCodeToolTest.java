package com.baskette.dropship.tool;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.service.StagingService;
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

    private StageCodeTool stageCodeTool;

    @BeforeEach
    void setUp() {
        stageCodeTool = new StageCodeTool(stagingService);
    }

    @Test
    void stageCodeDecodesBase64AndDelegatesToStagingService() {
        byte[] sourceBytes = "test source content".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                "droplet-guid-201", "app-guid-456", "dropship-testapp", "java_buildpack",
                "Staging completed successfully", 1500L, true, null);
        when(stagingService.stage(any(byte[].class), eq("java_buildpack"), eq(512), eq(1024)))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(base64Source, "java_buildpack", 512, 1024);

        assertThat(result.success()).isTrue();
        assertThat(result.dropletGuid()).isEqualTo("droplet-guid-201");
        assertThat(result.buildpack()).isEqualTo("java_buildpack");

        verify(stagingService).stage(eq(sourceBytes), eq("java_buildpack"), eq(512), eq(1024));
    }

    @Test
    void stageCodePassesNullOptionalParameters() {
        byte[] sourceBytes = "test source".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                "droplet-guid-201", "app-guid-456", "dropship-testapp", null,
                "Staging completed successfully", 1200L, true, null);
        when(stagingService.stage(any(byte[].class), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(base64Source, null, null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.buildpack()).isNull();

        verify(stagingService).stage(eq(sourceBytes), isNull(), isNull(), isNull());
    }

    @Test
    void stageCodeRejectsNullSourceBundle() {
        assertThatThrownBy(() -> stageCodeTool.stageCode(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsEmptySourceBundle() {
        assertThatThrownBy(() -> stageCodeTool.stageCode("", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsBlankSourceBundle() {
        assertThatThrownBy(() -> stageCodeTool.stageCode("   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsInvalidBase64() {
        assertThatThrownBy(() -> stageCodeTool.stageCode("not-valid-base64!!!", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceBundle is not valid base64");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeRejectsBase64ThatDecodesToEmpty() {
        String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);

        assertThatThrownBy(() -> stageCodeTool.stageCode(emptyBase64, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceBundle must not be empty");

        verifyNoInteractions(stagingService);
    }

    @Test
    void stageCodeReturnsStagingFailureResult() {
        byte[] sourceBytes = "bad source".getBytes();
        String base64Source = Base64.getEncoder().encodeToString(sourceBytes);

        StagingResult expected = new StagingResult(
                null, "app-guid-456", "dropship-testapp", "java_buildpack",
                "Buildpack compilation failed", 3000L, false,
                "Buildpack compilation failed");
        when(stagingService.stage(any(byte[].class), eq("java_buildpack"), isNull(), isNull()))
                .thenReturn(Mono.just(expected));

        StagingResult result = stageCodeTool.stageCode(base64Source, "java_buildpack", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.dropletGuid()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Buildpack compilation failed");
    }
}
