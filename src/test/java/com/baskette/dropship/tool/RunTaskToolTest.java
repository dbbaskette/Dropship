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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunTaskToolTest {

    @Mock
    private TaskService taskService;

    @Mock
    private McpSyncRequestContext requestContext;

    private RunTaskTool runTaskTool;

    @BeforeEach
    void setUp() {
        runTaskTool = new RunTaskTool(taskService, true);
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
    void runTaskDelegatesToLaunchTaskAndReturnsRunningResult() {
        stubHeaders(validHeaders());
        Map<String, String> env = Map.of("KEY1", "value1");
        TaskResult expected = new TaskResult(
                "task-guid-1", "app-guid-456", -1,
                TaskResult.State.RUNNING, 0, 0, "echo hello");

        when(taskService.launchTask(
                eq("app-guid-456"), eq("droplet-guid-123"), eq("echo hello"),
                eq(1024), eq(300), eq(env), any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        TaskResult result = runTaskTool.runTask(
                requestContext,
                "app-guid-456", "droplet-guid-123", "echo hello",
                1024, 300, env);

        assertThat(result.taskGuid()).isEqualTo("task-guid-1");
        assertThat(result.appGuid()).isEqualTo("app-guid-456");
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.state()).isEqualTo(TaskResult.State.RUNNING);
        assertThat(result.command()).isEqualTo("echo hello");
    }

    @Test
    void runTaskPassesNullOptionalParameters() {
        stubHeaders(validHeaders());
        TaskResult expected = new TaskResult(
                "task-guid-2", "app-guid-456", -1,
                TaskResult.State.RUNNING, 0, 0, "echo hello");

        when(taskService.launchTask(
                eq("app-guid-456"), eq("droplet-guid-123"), eq("echo hello"),
                isNull(), isNull(), isNull(), any(ReactorCloudFoundryClient.class)))
                .thenReturn(Mono.just(expected));

        TaskResult result = runTaskTool.runTask(
                requestContext,
                "app-guid-456", "droplet-guid-123", "echo hello",
                null, null, null);

        assertThat(result.state()).isEqualTo(TaskResult.State.RUNNING);
    }

    @Test
    void runTaskRejectsNullAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                null, "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsEmptyAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "", "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsBlankAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "   ", "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsNullDropletGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "app-guid-456", null, "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsEmptyDropletGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "app-guid-456", "", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsBlankDropletGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "app-guid-456", "   ", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsNullCommand() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "app-guid-456", "droplet-guid-123", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsEmptyCommand() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "app-guid-456", "droplet-guid-123", "", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsBlankCommand() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "app-guid-456", "droplet-guid-123", "   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskThrowsWhenMissingCredentials() {
        stubHeaders(Map.of());

        assertThatThrownBy(() -> runTaskTool.runTask(
                requestContext,
                "app-guid-456", "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");

        verifyNoInteractions(taskService);
    }
}
