package com.baskette.dropship.service;

import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskLogs.LogEntry;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationLog;
import org.cloudfoundry.operations.applications.ApplicationLogType;
import org.cloudfoundry.operations.applications.ApplicationLogsRequest;
import org.cloudfoundry.operations.applications.Applications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogServiceTest {

    @Mock
    private DefaultCloudFoundryOperations cfOperations;

    @Mock
    private Applications applications;

    private LogService logService;

    @BeforeEach
    void setUp() {
        logService = new LogService();
    }

    private ApplicationLog buildLog(String message, ApplicationLogType logType, long timestampNanos) {
        return ApplicationLog.builder()
                .message(message)
                .logType(logType)
                .timestamp(timestampNanos)
                .sourceType("APP")
                .sourceId("0")
                .instanceId("0")
                .build();
    }

    private void stubLogs(ApplicationLog... logs) {
        when(cfOperations.applications()).thenReturn(applications);
        when(applications.logs(any(ApplicationLogsRequest.class))).thenReturn(Flux.just(logs));
    }

    private void stubEmptyLogs() {
        when(cfOperations.applications()).thenReturn(applications);
        when(applications.logs(any(ApplicationLogsRequest.class))).thenReturn(Flux.empty());
    }

    @Test
    void happyPathReturnsOrderedLogEntries() {
        long ts1 = 1_000_000_000L;
        long ts2 = 2_000_000_000L;
        long ts3 = 3_000_000_000L;

        stubLogs(
                buildLog("third", ApplicationLogType.OUT, ts3),
                buildLog("first", ApplicationLogType.ERR, ts1),
                buildLog("second", ApplicationLogType.OUT, ts2)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "my-app", null, null, cfOperations))
                .assertNext(taskLogs -> {
                    assertThat(taskLogs.taskGuid()).isEqualTo("task-guid-1");
                    assertThat(taskLogs.truncated()).isFalse();
                    assertThat(taskLogs.totalLines()).isEqualTo(3);

                    List<LogEntry> entries = taskLogs.entries();
                    assertThat(entries).hasSize(3);

                    assertThat(entries.get(0).message()).isEqualTo("first");
                    assertThat(entries.get(0).source()).isEqualTo("stderr");
                    assertThat(entries.get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1000));

                    assertThat(entries.get(1).message()).isEqualTo("second");
                    assertThat(entries.get(1).source()).isEqualTo("stdout");
                    assertThat(entries.get(1).timestamp()).isEqualTo(Instant.ofEpochMilli(2000));

                    assertThat(entries.get(2).message()).isEqualTo("third");
                    assertThat(entries.get(2).source()).isEqualTo("stdout");
                    assertThat(entries.get(2).timestamp()).isEqualTo(Instant.ofEpochMilli(3000));
                })
                .verifyComplete();
    }

    @Test
    void emptyLogsReturnsEmptyList() {
        stubEmptyLogs();

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "my-app", null, null, cfOperations))
                .assertNext(taskLogs -> {
                    assertThat(taskLogs.taskGuid()).isEqualTo("task-guid-1");
                    assertThat(taskLogs.entries()).isEmpty();
                    assertThat(taskLogs.totalLines()).isEqualTo(0);
                    assertThat(taskLogs.truncated()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void truncationWhenExceedsMaxLines() {
        ApplicationLog[] logs = new ApplicationLog[10];
        for (int i = 0; i < 10; i++) {
            logs[i] = buildLog("line-" + i, ApplicationLogType.OUT, (i + 1) * 1_000_000_000L);
        }
        stubLogs(logs);

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "my-app", 5, null, cfOperations))
                .assertNext(taskLogs -> {
                    assertThat(taskLogs.entries()).hasSize(5);
                    assertThat(taskLogs.truncated()).isTrue();
                    assertThat(taskLogs.totalLines()).isEqualTo(10);

                    assertThat(taskLogs.entries().get(0).message()).isEqualTo("line-0");
                    assertThat(taskLogs.entries().get(4).message()).isEqualTo("line-4");
                })
                .verifyComplete();
    }

    @Test
    void filterStdoutOnly() {
        stubLogs(
                buildLog("out-1", ApplicationLogType.OUT, 1_000_000_000L),
                buildLog("err-1", ApplicationLogType.ERR, 2_000_000_000L),
                buildLog("out-2", ApplicationLogType.OUT, 3_000_000_000L)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "my-app", null, "stdout", cfOperations))
                .assertNext(taskLogs -> {
                    assertThat(taskLogs.entries()).hasSize(2);
                    assertThat(taskLogs.entries()).allSatisfy(entry ->
                            assertThat(entry.source()).isEqualTo("stdout"));
                    assertThat(taskLogs.entries().get(0).message()).isEqualTo("out-1");
                    assertThat(taskLogs.entries().get(1).message()).isEqualTo("out-2");
                })
                .verifyComplete();
    }

    @Test
    void filterStderrOnly() {
        stubLogs(
                buildLog("out-1", ApplicationLogType.OUT, 1_000_000_000L),
                buildLog("err-1", ApplicationLogType.ERR, 2_000_000_000L),
                buildLog("out-2", ApplicationLogType.OUT, 3_000_000_000L),
                buildLog("err-2", ApplicationLogType.ERR, 4_000_000_000L)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "my-app", null, "stderr", cfOperations))
                .assertNext(taskLogs -> {
                    assertThat(taskLogs.entries()).hasSize(2);
                    assertThat(taskLogs.entries()).allSatisfy(entry ->
                            assertThat(entry.source()).isEqualTo("stderr"));
                    assertThat(taskLogs.entries().get(0).message()).isEqualTo("err-1");
                    assertThat(taskLogs.entries().get(1).message()).isEqualTo("err-2");
                })
                .verifyComplete();
    }

    @Test
    void defaultSourceReturnsAll() {
        stubLogs(
                buildLog("out-msg", ApplicationLogType.OUT, 1_000_000_000L),
                buildLog("err-msg", ApplicationLogType.ERR, 2_000_000_000L)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "my-app", null, null, cfOperations))
                .assertNext(taskLogs -> {
                    assertThat(taskLogs.entries()).hasSize(2);
                    assertThat(taskLogs.entries().get(0).source()).isEqualTo("stdout");
                    assertThat(taskLogs.entries().get(0).message()).isEqualTo("out-msg");
                    assertThat(taskLogs.entries().get(1).source()).isEqualTo("stderr");
                    assertThat(taskLogs.entries().get(1).message()).isEqualTo("err-msg");
                })
                .verifyComplete();
    }
}
