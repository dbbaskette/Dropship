package com.baskette.dropship.tool;

import com.baskette.dropship.config.CfClientFactory;
import com.baskette.dropship.model.TaskResult;
import com.baskette.dropship.service.TaskService;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private CfClientFactory cfClientFactory;

    @Mock
    private ReactorCloudFoundryClient cfClient;

    private RunTaskTool runTaskTool;

    @BeforeEach
    void setUp() {
        runTaskTool = new RunTaskTool(taskService, cfClientFactory);
    }

    private void stubClientFactory() {
        when(cfClientFactory.getClientForCurrentSession()).thenReturn(cfClient);
    }

    @Test
    void runTaskDelegatesToTaskServiceAndReturnsResult() {
        stubClientFactory();
        Map<String, String> env = Map.of("KEY1", "value1");
        TaskResult expected = new TaskResult(
                "task-guid-1", "app-guid-456", 0,
                TaskResult.State.SUCCEEDED, 1500L, 512, "echo hello");

        when(taskService.runTask(
                eq("app-guid-456"), eq("droplet-guid-123"), eq("echo hello"),
                eq(1024), eq(300), eq(env), eq(cfClient)))
                .thenReturn(Mono.just(expected));

        TaskResult result = runTaskTool.runTask(
                "app-guid-456", "droplet-guid-123", "echo hello",
                1024, 300, env);

        assertThat(result.taskGuid()).isEqualTo("task-guid-1");
        assertThat(result.appGuid()).isEqualTo("app-guid-456");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state()).isEqualTo(TaskResult.State.SUCCEEDED);
        assertThat(result.command()).isEqualTo("echo hello");

        verify(taskService).runTask(
                eq("app-guid-456"), eq("droplet-guid-123"), eq("echo hello"),
                eq(1024), eq(300), eq(env), eq(cfClient));
    }

    @Test
    void runTaskPassesNullOptionalParameters() {
        stubClientFactory();
        TaskResult expected = new TaskResult(
                "task-guid-2", "app-guid-456", 0,
                TaskResult.State.SUCCEEDED, 1200L, 512, "echo hello");

        when(taskService.runTask(
                eq("app-guid-456"), eq("droplet-guid-123"), eq("echo hello"),
                isNull(), isNull(), isNull(), eq(cfClient)))
                .thenReturn(Mono.just(expected));

        TaskResult result = runTaskTool.runTask(
                "app-guid-456", "droplet-guid-123", "echo hello",
                null, null, null);

        assertThat(result.state()).isEqualTo(TaskResult.State.SUCCEEDED);

        verify(taskService).runTask(
                eq("app-guid-456"), eq("droplet-guid-123"), eq("echo hello"),
                isNull(), isNull(), isNull(), eq(cfClient));
    }

    @Test
    void runTaskRejectsNullAppGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                null, "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsEmptyAppGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "", "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsBlankAppGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "   ", "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsNullDropletGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "app-guid-456", null, "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsEmptyDropletGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "app-guid-456", "", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsBlankDropletGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "app-guid-456", "   ", "echo hello", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dropletGuid must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsNullCommand() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "app-guid-456", "droplet-guid-123", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsEmptyCommand() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "app-guid-456", "droplet-guid-123", "", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskRejectsBlankCommand() {
        stubClientFactory();
        assertThatThrownBy(() -> runTaskTool.runTask(
                "app-guid-456", "droplet-guid-123", "   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be empty");

        verifyNoInteractions(taskService);
    }

    @Test
    void runTaskThrowsWhenNoSessionCredentials() {
        when(cfClientFactory.getClientForCurrentSession())
                .thenThrow(new IllegalStateException(
                        "No CF credentials found for this session. Call connect_cf first."));

        assertThatThrownBy(() -> runTaskTool.runTask(
                "app-guid-456", "droplet-guid-123", "echo hello", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CF credentials found for this session");

        verifyNoInteractions(taskService);
    }
}
