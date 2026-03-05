package com.baskette.dropship.tool;

import com.baskette.dropship.model.TaskResult;
import com.baskette.dropship.service.TaskService;
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
class GetTaskStatusToolTest {

    @Mock
    private TaskService taskService;

    @Mock
    private McpSyncRequestContext requestContext;

    private GetTaskStatusTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetTaskStatusTool(taskService, true);
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
    void returnsRunningWhenTaskStillInProgress() {
        stubHeaders(validHeaders());
        TaskResult expected = new TaskResult(
                "task-guid-1", "app-guid-456", -1,
                TaskResult.State.RUNNING, 0, 512, "echo hello");

        when(taskService.getTaskStatus(eq("task-guid-1"), eq("app-guid-456"),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        TaskResult result = tool.getTaskStatus(requestContext, "task-guid-1", "app-guid-456");

        assertThat(result.state()).isEqualTo(TaskResult.State.RUNNING);
        assertThat(result.taskGuid()).isEqualTo("task-guid-1");
        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test
    void returnsSucceededWhenTaskCompletes() {
        stubHeaders(validHeaders());
        TaskResult expected = new TaskResult(
                "task-guid-1", "app-guid-456", 0,
                TaskResult.State.SUCCEEDED, 0, 512, "echo hello");

        when(taskService.getTaskStatus(eq("task-guid-1"), eq("app-guid-456"),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        TaskResult result = tool.getTaskStatus(requestContext, "task-guid-1", "app-guid-456");

        assertThat(result.state()).isEqualTo(TaskResult.State.SUCCEEDED);
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void returnsFailedWhenTaskFails() {
        stubHeaders(validHeaders());
        TaskResult expected = new TaskResult(
                "task-guid-1", "app-guid-456", 1,
                TaskResult.State.FAILED, 0, 512, "echo hello");

        when(taskService.getTaskStatus(eq("task-guid-1"), eq("app-guid-456"),
                any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        TaskResult result = tool.getTaskStatus(requestContext, "task-guid-1", "app-guid-456");

        assertThat(result.state()).isEqualTo(TaskResult.State.FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void rejectsNullTaskGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getTaskStatus(requestContext, null, "app-guid-456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");
        verifyNoInteractions(taskService);
    }

    @Test
    void rejectsEmptyTaskGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getTaskStatus(requestContext, "", "app-guid-456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");
        verifyNoInteractions(taskService);
    }

    @Test
    void rejectsBlankTaskGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getTaskStatus(requestContext, "   ", "app-guid-456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");
        verifyNoInteractions(taskService);
    }

    @Test
    void rejectsNullAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getTaskStatus(requestContext, "task-guid-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(taskService);
    }

    @Test
    void rejectsEmptyAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getTaskStatus(requestContext, "task-guid-1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(taskService);
    }

    @Test
    void rejectsBlankAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> tool.getTaskStatus(requestContext, "task-guid-1", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");
        verifyNoInteractions(taskService);
    }

    @Test
    void throwsWhenMissingCredentials() {
        stubHeaders(Map.of());
        assertThatThrownBy(() -> tool.getTaskStatus(requestContext, "task-guid-1", "app-guid-456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");
        verifyNoInteractions(taskService);
    }
}
