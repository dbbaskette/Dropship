package com.baskette.dropship.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskLogsTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void constructionWithValidValues() {
        Instant now = Instant.parse("2025-01-15T10:30:00Z");
        TaskLogs.LogEntry entry = new TaskLogs.LogEntry(now, "stdout", "Starting task...");
        TaskLogs logs = new TaskLogs("task-guid-123", List.of(entry), 1, false);

        assertThat(logs.taskGuid()).isEqualTo("task-guid-123");
        assertThat(logs.entries()).hasSize(1);
        assertThat(logs.totalLines()).isEqualTo(1);
        assertThat(logs.truncated()).isFalse();
    }

    @Test
    void fieldsAreAccessible() {
        Instant ts1 = Instant.parse("2025-01-15T10:30:00Z");
        Instant ts2 = Instant.parse("2025-01-15T10:30:01Z");
        TaskLogs.LogEntry stdout = new TaskLogs.LogEntry(ts1, "stdout", "Hello world");
        TaskLogs.LogEntry stderr = new TaskLogs.LogEntry(ts2, "stderr", "Warning: deprecated");
        TaskLogs logs = new TaskLogs("task-guid-456", List.of(stdout, stderr), 250, true);

        assertThat(logs.taskGuid()).isEqualTo("task-guid-456");
        assertThat(logs.entries()).containsExactly(stdout, stderr);
        assertThat(logs.entries().get(0).source()).isEqualTo("stdout");
        assertThat(logs.entries().get(0).message()).isEqualTo("Hello world");
        assertThat(logs.entries().get(1).source()).isEqualTo("stderr");
        assertThat(logs.entries().get(1).message()).isEqualTo("Warning: deprecated");
        assertThat(logs.totalLines()).isEqualTo(250);
        assertThat(logs.truncated()).isTrue();
    }

    @Test
    void emptyEntriesList() {
        TaskLogs logs = new TaskLogs("task-guid-empty", List.of(), 0, false);

        assertThat(logs.taskGuid()).isEqualTo("task-guid-empty");
        assertThat(logs.entries()).isEmpty();
        assertThat(logs.totalLines()).isEqualTo(0);
        assertThat(logs.truncated()).isFalse();
    }

    @Test
    void truncatedFlag() {
        TaskLogs notTruncated = new TaskLogs("task-1", List.of(), 50, false);
        TaskLogs truncated = new TaskLogs("task-2", List.of(), 10000, true);

        assertThat(notTruncated.truncated()).isFalse();
        assertThat(truncated.truncated()).isTrue();
    }

    @Test
    void logEntryConstruction() {
        Instant timestamp = Instant.parse("2025-06-01T12:00:00Z");
        TaskLogs.LogEntry entry = new TaskLogs.LogEntry(timestamp, "stdout", "Process exited with code 0");

        assertThat(entry.timestamp()).isEqualTo(timestamp);
        assertThat(entry.source()).isEqualTo("stdout");
        assertThat(entry.message()).isEqualTo("Process exited with code 0");
    }

    @Test
    void serializesToJson() throws Exception {
        Instant ts = Instant.parse("2025-01-15T10:30:00Z");
        TaskLogs.LogEntry entry = new TaskLogs.LogEntry(ts, "stdout", "Running migrations");
        TaskLogs logs = new TaskLogs("task-guid-ser", List.of(entry), 1, false);

        String json = objectMapper.writeValueAsString(logs);

        assertThat(json).contains("\"taskGuid\":\"task-guid-ser\"");
        assertThat(json).contains("\"totalLines\":1");
        assertThat(json).contains("\"truncated\":false");
        assertThat(json).contains("\"source\":\"stdout\"");
        assertThat(json).contains("\"message\":\"Running migrations\"");
    }

    @Test
    void deserializesFromJson() throws Exception {
        String json = """
                {
                  "taskGuid": "task-abc",
                  "entries": [
                    {
                      "timestamp": "2025-01-15T10:30:00Z",
                      "source": "stderr",
                      "message": "Error: connection refused"
                    }
                  ],
                  "totalLines": 500,
                  "truncated": true
                }
                """;

        TaskLogs logs = objectMapper.readValue(json, TaskLogs.class);

        assertThat(logs.taskGuid()).isEqualTo("task-abc");
        assertThat(logs.entries()).hasSize(1);
        assertThat(logs.entries().get(0).timestamp()).isEqualTo(Instant.parse("2025-01-15T10:30:00Z"));
        assertThat(logs.entries().get(0).source()).isEqualTo("stderr");
        assertThat(logs.entries().get(0).message()).isEqualTo("Error: connection refused");
        assertThat(logs.totalLines()).isEqualTo(500);
        assertThat(logs.truncated()).isTrue();
    }

    @Test
    void roundTripSerializationPreservesData() throws Exception {
        Instant ts1 = Instant.parse("2025-03-10T08:00:00Z");
        Instant ts2 = Instant.parse("2025-03-10T08:00:01Z");
        TaskLogs original = new TaskLogs(
                "task-round-trip",
                List.of(
                        new TaskLogs.LogEntry(ts1, "stdout", "Step 1 complete"),
                        new TaskLogs.LogEntry(ts2, "stderr", "Warning: low memory")
                ),
                2,
                false
        );

        String json = objectMapper.writeValueAsString(original);
        TaskLogs deserialized = objectMapper.readValue(json, TaskLogs.class);

        assertThat(deserialized).isEqualTo(original);
    }
}
