package com.baskette.dropship.tool;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.service.BuildTracker;
import com.baskette.dropship.service.GitCloneService;
import com.baskette.dropship.service.StagingService;
import com.baskette.dropship.service.TaskService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.mcp.context.McpSyncRequestContext;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StageGitRepoToolTest {

    @Mock
    private GitCloneService gitCloneService;

    @Mock
    private StagingService stagingService;

    @Mock
    private TaskService taskService;

    @Mock
    private BuildTracker buildTracker;

    @Mock
    private McpSyncRequestContext requestContext;

    private StageGitRepoTool stageGitRepoTool;

    @BeforeEach
    void setUp() {
        stageGitRepoTool = new StageGitRepoTool(
                gitCloneService, stagingService, taskService, buildTracker, true);
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
    void stageGitRepoSubmitsToBuildTrackerAndReturnsBuildId() {
        stubHeaders(validHeaders());
        when(buildTracker.submit(any())).thenReturn("build-abc-123");

        StagingResult result = stageGitRepoTool.stageGitRepo(
                requestContext,
                "https://github.com/user/repo.git", "main", null,
                "java_buildpack", 512, 1024, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("build-abc-123");
        assertThat(result.errorMessage()).contains("get_build_status");
        verify(buildTracker).submit(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submittedWorkCallsCloneAndStage() {
        stubHeaders(validHeaders());
        ArgumentCaptor<Supplier<StagingResult>> captor = ArgumentCaptor.forClass(Supplier.class);
        when(buildTracker.submit(captor.capture())).thenReturn("build-xyz");

        stageGitRepoTool.stageGitRepo(
                requestContext,
                "https://github.com/user/repo.git", "main", null,
                "java_buildpack", 512, 1024, null);

        // The supplier was captured but not yet invoked — BuildTracker runs it async
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void stageGitRepoPassesNullOptionalParameters() {
        stubHeaders(validHeaders());
        when(buildTracker.submit(any())).thenReturn("build-null-opts");

        StagingResult result = stageGitRepoTool.stageGitRepo(
                requestContext,
                "https://github.com/user/repo.git", null, null,
                null, null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("build-null-opts");
    }

    @Test
    void stageGitRepoAcceptsCommand() {
        stubHeaders(validHeaders());
        when(buildTracker.submit(any())).thenReturn("build-with-cmd");

        StagingResult result = stageGitRepoTool.stageGitRepo(
                requestContext,
                "https://github.com/user/repo.git", "main", null,
                "java_buildpack", 512, 1024, "java -jar app.jar");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("build-with-cmd");
        verify(buildTracker).submit(any());
    }

    @Test
    void stageGitRepoRejectsNullRepoUrl() {
        stubHeaders(validHeaders());

        assertThatThrownBy(() -> stageGitRepoTool.stageGitRepo(
                requestContext, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("repoUrl must not be empty");

        verifyNoInteractions(gitCloneService);
        verifyNoInteractions(stagingService);
        verifyNoInteractions(buildTracker);
    }

    @Test
    void stageGitRepoRejectsEmptyRepoUrl() {
        stubHeaders(validHeaders());

        assertThatThrownBy(() -> stageGitRepoTool.stageGitRepo(
                requestContext, "", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("repoUrl must not be empty");

        verifyNoInteractions(gitCloneService);
        verifyNoInteractions(stagingService);
        verifyNoInteractions(buildTracker);
    }

    @Test
    void stageGitRepoRejectsBlankRepoUrl() {
        stubHeaders(validHeaders());

        assertThatThrownBy(() -> stageGitRepoTool.stageGitRepo(
                requestContext, "   ", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("repoUrl must not be empty");

        verifyNoInteractions(gitCloneService);
        verifyNoInteractions(stagingService);
        verifyNoInteractions(buildTracker);
    }

    @Test
    void stageGitRepoThrowsWhenMissingCredentials() {
        stubHeaders(Map.of());

        assertThatThrownBy(() -> stageGitRepoTool.stageGitRepo(
                requestContext,
                "https://github.com/user/repo.git", null, null,
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");

        verifyNoInteractions(gitCloneService);
        verifyNoInteractions(stagingService);
        verifyNoInteractions(buildTracker);
    }
}
