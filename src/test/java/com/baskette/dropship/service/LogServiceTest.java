package com.baskette.dropship.service;

import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskLogs.LogEntry;
import org.cloudfoundry.logcache.v1.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogServiceTest {

    @Mock
    private LogCacheClient logCacheClient;

    private LogService logService;

    @BeforeEach
    void setUp() {
        logService = new LogService();
    }

    private Envelope buildEnvelope(String message, LogType logType, long timestampNanos) {
        String base64Payload = Base64.getEncoder().encodeToString(message.getBytes());
        Log log = Log.builder()
                .payload(base64Payload)
                .type(logType)
                .build();
        return Envelope.builder()
                .log(log)
                .timestamp(timestampNanos)
                .build();
    }

    private void stubEnvelopes(Envelope... envelopes) {
        EnvelopeBatch batch = EnvelopeBatch.builder()
                .batch(envelopes)
                .build();
        ReadResponse response = ReadResponse.builder()
                .envelopes(batch)
                .build();
        when(logCacheClient.read(any(ReadRequest.class))).thenReturn(Mono.just(response));
    }

    private void stubEmptyEnvelopes() {
        EnvelopeBatch batch = EnvelopeBatch.builder()
                .build();
        ReadResponse response = ReadResponse.builder()
                .envelopes(batch)
                .build();
        when(logCacheClient.read(any(ReadRequest.class))).thenReturn(Mono.just(response));
    }

    @Test
    void happyPathReturnsOrderedLogEntries() {
        long ts1 = 1_000_000_000L;
        long ts2 = 2_000_000_000L;
        long ts3 = 3_000_000_000L;

        stubEnvelopes(
                buildEnvelope("third", LogType.OUT, ts3),
                buildEnvelope("first", LogType.ERR, ts1),
                buildEnvelope("second", LogType.OUT, ts2)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "app-guid-1", null, null, logCacheClient))
                .assertNext(taskLogs -> {
                    assertThat(taskLogs.taskGuid()).isEqualTo("task-guid-1");
                    assertThat(taskLogs.truncated()).isFalse();
                    assertThat(taskLogs.totalLines()).isEqualTo(3);

                    List<LogEntry> entries = taskLogs.entries();
                    assertThat(entries).hasSize(3);

                    assertThat(entries.get(0).message()).isEqualTo("first");
                    assertThat(entries.get(0).source()).isEqualTo("stderr");

                    assertThat(entries.get(1).message()).isEqualTo("second");
                    assertThat(entries.get(1).source()).isEqualTo("stdout");

                    assertThat(entries.get(2).message()).isEqualTo("third");
                    assertThat(entries.get(2).source()).isEqualTo("stdout");
                })
                .verifyComplete();
    }

    @Test
    void emptyLogsReturnsEmptyList() {
        stubEmptyEnvelopes();

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "app-guid-1", null, null, logCacheClient))
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
        Envelope[] envelopes = new Envelope[10];
        for (int i = 0; i < 10; i++) {
            envelopes[i] = buildEnvelope("line-" + i, LogType.OUT, (i + 1) * 1_000_000_000L);
        }
        stubEnvelopes(envelopes);

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "app-guid-1", 5, null, logCacheClient))
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
        stubEnvelopes(
                buildEnvelope("out-1", LogType.OUT, 1_000_000_000L),
                buildEnvelope("err-1", LogType.ERR, 2_000_000_000L),
                buildEnvelope("out-2", LogType.OUT, 3_000_000_000L)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "app-guid-1", null, "stdout", logCacheClient))
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
        stubEnvelopes(
                buildEnvelope("out-1", LogType.OUT, 1_000_000_000L),
                buildEnvelope("err-1", LogType.ERR, 2_000_000_000L),
                buildEnvelope("out-2", LogType.OUT, 3_000_000_000L),
                buildEnvelope("err-2", LogType.ERR, 4_000_000_000L)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "app-guid-1", null, "stderr", logCacheClient))
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
        stubEnvelopes(
                buildEnvelope("out-msg", LogType.OUT, 1_000_000_000L),
                buildEnvelope("err-msg", LogType.ERR, 2_000_000_000L)
        );

        StepVerifier.create(logService.getTaskLogs("task-guid-1", "app-guid-1", null, null, logCacheClient))
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
