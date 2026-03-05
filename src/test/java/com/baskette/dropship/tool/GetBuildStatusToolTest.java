package com.baskette.dropship.tool;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.service.BuildTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetBuildStatusToolTest {

    @Mock
    private BuildTracker buildTracker;

    private GetBuildStatusTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetBuildStatusTool(buildTracker);
    }

    @Test
    void returnsCompletedResult() {
        StagingResult expected = new StagingResult(
                "droplet-1", "app-1", "myapp", "java_buildpack",
                "logs", 5000, true, null);
        when(buildTracker.getStatus("build-123")).thenReturn(expected);

        StagingResult result = tool.getBuildStatus("build-123");

        assertThat(result.success()).isTrue();
        assertThat(result.dropletGuid()).isEqualTo("droplet-1");
    }

    @Test
    void returnsInProgressResult() {
        StagingResult inProgress = new StagingResult(
                null, null, null, null, null, 0, false,
                "Build build-456 is still in progress");
        when(buildTracker.getStatus("build-456")).thenReturn(inProgress);

        StagingResult result = tool.getBuildStatus("build-456");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("still in progress");
    }

    @Test
    void rejectsNullBuildId() {
        assertThatThrownBy(() -> tool.getBuildStatus(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("buildId must not be empty");
        verifyNoInteractions(buildTracker);
    }

    @Test
    void rejectsEmptyBuildId() {
        assertThatThrownBy(() -> tool.getBuildStatus(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("buildId must not be empty");
        verifyNoInteractions(buildTracker);
    }

    @Test
    void rejectsBlankBuildId() {
        assertThatThrownBy(() -> tool.getBuildStatus("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("buildId must not be empty");
        verifyNoInteractions(buildTracker);
    }

    @Test
    void propagatesUnknownBuildIdError() {
        when(buildTracker.getStatus("unknown")).thenThrow(
                new IllegalArgumentException("Unknown build ID: unknown"));

        assertThatThrownBy(() -> tool.getBuildStatus("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown build ID");
    }
}
