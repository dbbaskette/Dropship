package com.baskette.dropship.tool;

import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskLogs.LogEntry;
import com.baskette.dropship.service.LogService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.cloudfoundry.logcache.v1.LogCacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetTaskLogToolTest {

    @Mock
    private LogService logService;

    @Mock
    private McpSyncRequestContext requestContext;

    private GetTaskLogsTool getTaskLogsTool;

    @BeforeEach
    void setUp() {
        getTaskLogsTool = new GetTaskLogsTool(logService, true);
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
    void getTaskLogsDelegatesToLogService() {
        stubHeaders(validHeaders());
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
                eq("task-guid-1"), eq("app-guid-1"), eq(100), eq("stdout"),
                any(LogCacheClient.class)))
                .thenReturn(Mono.just(expected));

        TaskLogs result = getTaskLogsTool.getTaskLogs(
                requestContext,
                "task-guid-1", "app-guid-1", 100, "stdout");

        assertThat(result.taskGuid()).isEqualTo("task-guid-1");
        assertThat(result.entries()).hasSize(2);
        assertThat(result.totalLines()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void rejectsNullTaskGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                requestContext,
                null, "app-guid-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsEmptyTaskGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                requestContext,
                "", "app-guid-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsNullAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                requestContext,
                "task-guid-1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void rejectsEmptyAppGuid() {
        stubHeaders(validHeaders());
        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                requestContext,
                "task-guid-1", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("appGuid must not be empty");

        verifyNoInteractions(logService);
    }

    @Test
    void passesNullOptionalParams() {
        stubHeaders(validHeaders());
        TaskLogs expected = new TaskLogs(
                "task-guid-2",
                List.of(new LogEntry(Instant.ofEpochMilli(5000), "stdout", "output line")),
                1,
                false
        );

        when(logService.getTaskLogs(
                eq("task-guid-2"), eq("app-guid-2"), isNull(), isNull(),
                any(LogCacheClient.class)))
                .thenReturn(Mono.just(expected));

        TaskLogs result = getTaskLogsTool.getTaskLogs(
                requestContext,
                "task-guid-2", "app-guid-2", null, null);

        assertThat(result.taskGuid()).isEqualTo("task-guid-2");
        assertThat(result.entries()).hasSize(1);
    }

    @Test
    void getTaskLogsThrowsWhenMissingCredentials() {
        stubHeaders(Map.of());

        assertThatThrownBy(() -> getTaskLogsTool.getTaskLogs(
                requestContext,
                "task-guid-1", "app-guid-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required header");

        verifyNoInteractions(logService);
    }
}
