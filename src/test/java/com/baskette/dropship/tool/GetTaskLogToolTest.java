package com.baskette.dropship.tool;

import com.baskette.dropship.config.CfClientFactory;
import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskLogs.LogEntry;
import com.baskette.dropship.service.LogService;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetTaskLogToolTest {

    @Mock
    private LogService logService;

    @Mock
    private CfClientFactory cfClientFactory;

    @Mock
    private DefaultCloudFoundryOperations cfOperations;

    private GetTaskLogsTool getTaskLogsTool;

    @BeforeEach
    void setUp() {
        getTaskLogsTool = new GetTaskLogsTool(logService, cfClientFactory);
    }

    private void stubClientFactory() {
        when(cfClientFactory.getOperationsForCurrentSession()).thenReturn(cfOperations);
    }

    @Test
    void getTaskLogsDelegatesToLogService() {
        stubClientFactory();
        TaskLogs expected = new TaskLogs(
                "task-guid-1",
                List.of(
                        new LogEntry(Instant.ofEpochMilli(1000), "stdout", "hello"),
                        new LogEntry(Instant.ofEpochMilli(2000), "stderr", "error msg")
                ),
                2,
                false
        );

        when(logService.getTaskLogs(
                eq("task-guid-1"), eq("my-app"), eq(100), eq("stdout"), eq(cfOperations)))
                .thenReturn(Mono.just(expected));

        TaskLogs result = getTaskLogsTool.getTaskLogs(
                "task-guid-1", "my-app", 100, "stdout");

        assertThat(result.taskGuid()).isEqualTo("task-guid-1");
        assertThat(result.entries()).hasSize(2);
        assertThat(result.totalLines()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();

        verify(logService).getTaskLogs(
                eq("task-guid-1"), eq("my-app"), eq(100), eq("stdout"), eq(cfOperations));
    }

    @Test
    void rejectsNullTaskGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                null, "my-app", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsEmptyTaskGuid() {
        stubClientFactory();
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                "", "my-app", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsNullAppName() {
        stubClientFactory();
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                "task-guid-1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appName must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsEmptyAppName() {
        stubClientFactory();
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                "task-guid-1", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appName must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void passesNullOptionalParams() {
        stubClientFactory();
        TaskLogs expected = new TaskLogs(
                "task-guid-2",
                List.of(new LogEntry(Instant.ofEpochMilli(5000), "stdout", "output line")),
                1,
                false
        );

        when(logService.getTaskLogs(
                eq("task-guid-2"), eq("my-app"), isNull(), isNull(), eq(cfOperations)))
                .thenReturn(Mono.just(expected));

        TaskLogs result = getTaskLogsTool.getTaskLogs(
                "task-guid-2", "my-app", null, null);

        assertThat(result.taskGuid()).isEqualTo("task-guid-2");
        assertThat(result.entries()).hasSize(1);

        verify(logService).getTaskLogs(
                eq("task-guid-2"), eq("my-app"), isNull(), isNull(), eq(cfOperations));
    }

    @Test
    void getTaskLogsThrowsWhenNoSessionCredentials() {
        when(cfClientFactory.getOperationsForCurrentSession())
                .thenThrow(new IllegalStateException(
                        "No CF credentials found for this session. Call connect_cf first."));

        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                "task-guid-1", "my-app", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CF credentials found for this session");

        verifyNoInteractions(logService);
    }
}
