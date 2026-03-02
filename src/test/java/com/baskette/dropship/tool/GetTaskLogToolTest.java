package com.baskette.dropship.tool;

import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskLogs.LogEntry;
import com.baskette.dropship.service.LogService;
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

    private GetTaskLogsTool getTaskLogsTool;

    @BeforeEach
    void setUp() {
        getTaskLogsTool = new GetTaskLogsTool(logService);
    }

    @Test
    void getTaskLogsDelegatesToLogService() {
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
                eq("task-guid-1"), eq("my-app"), eq(100), eq("stdout")))
                .thenReturn(Mono.just(expected));

        TaskLogs result = getTaskLogsTool.getTaskLogs(
                "task-guid-1", "my-app", 100, "stdout");

        assertThat(result.taskGuid()).isEqualTo("task-guid-1");
        assertThat(result.entries()).hasSize(2);
        assertThat(result.totalLines()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();

        verify(logService).getTaskLogs(
                eq("task-guid-1"), eq("my-app"), eq(100), eq("stdout"));
    }

    @Test
    void rejectsNullTaskGuid() {
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                null, "my-app", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsEmptyTaskGuid() {
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                "", "my-app", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsNullAppName() {
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                "task-guid-1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appName must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsEmptyAppName() {
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                "task-guid-1", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appName must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void passesNullOptionalParams() {
        TaskLogs expected = new TaskLogs(
                "task-guid-2",
                List.of(new LogEntry(Instant.ofEpochMilli(5000), "stdout", "output line")),
                1,
                false
        );

        when(logService.getTaskLogs(
                eq("task-guid-2"), eq("my-app"), isNull(), isNull()))
                .thenReturn(Mono.just(expected));

        TaskLogs result = getTaskLogsTool.getTaskLogs(
                "task-guid-2", "my-app", null, null);

        assertThat(result.taskGuid()).isEqualTo("task-guid-2");
        assertThat(result.entries()).hasSize(1);

        verify(logService).getTaskLogs(
                eq("task-guid-2"), eq("my-app"), isNull(), isNull());
    }
}
