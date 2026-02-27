package com.baskette.dropship.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void succeededResultSerializesToJson() throws Exception {
        TaskResult result = new TaskResult(
                "task-guid-123",
                "app-guid-456",
                0,
                TaskResult.State.SUCCEEDED,
                4500L,
                512,
                "rake db:migrate"
        );

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"taskGuid\":\"task-guid-123\"");
        assertThat(json).contains("\"appGuid\":\"app-guid-456\"");
        assertThat(json).contains("\"exitCode\":0");
        assertThat(json).contains("\"state\":\"SUCCEEDED\"");
        assertThat(json).contains("\"durationMs\":4500");
        assertThat(json).contains("\"memoryMb\":512");
        assertThat(json).contains("\"command\":\"rake db:migrate\"");
    }

    @Test
    void failedResultSerializesToJson() throws Exception {
        TaskResult result = new TaskResult(
                "task-guid-789",
                "app-guid-012",
                1,
                TaskResult.State.FAILED,
                1200L,
                1024,
                "bin/run-tests"
        );

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"taskGuid\":\"task-guid-789\"");
        assertThat(json).contains("\"appGuid\":\"app-guid-012\"");
        assertThat(json).contains("\"exitCode\":1");
        assertThat(json).contains("\"state\":\"FAILED\"");
        assertThat(json).contains("\"durationMs\":1200");
        assertThat(json).contains("\"memoryMb\":1024");
        assertThat(json).contains("\"command\":\"bin/run-tests\"");
    }

    @Test
    void deserializesFromJson() throws Exception {
        String json = """
                {
                  "taskGuid": "task-abc",
                  "appGuid": "app-def",
                  "exitCode": 0,
                  "state": "SUCCEEDED",
                  "durationMs": 3000,
                  "memoryMb": 256,
                  "command": "python manage.py migrate"
                }
                """;

        TaskResult result = objectMapper.readValue(json, TaskResult.class);

        assertThat(result.taskGuid()).isEqualTo("task-abc");
        assertThat(result.appGuid()).isEqualTo("app-def");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state()).isEqualTo(TaskResult.State.SUCCEEDED);
        assertThat(result.durationMs()).isEqualTo(3000L);
        assertThat(result.memoryMb()).isEqualTo(256);
        assertThat(result.command()).isEqualTo("python manage.py migrate");
    }

    @Test
    void roundTripSerializationPreservesData() throws Exception {
        TaskResult original = new TaskResult(
                "task-round-trip",
                "app-round-trip",
                0,
                TaskResult.State.SUCCEEDED,
                7800L,
                2048,
                "bundle exec rails server"
        );

        String json = objectMapper.writeValueAsString(original);
        TaskResult deserialized = objectMapper.readValue(json, TaskResult.class);

        assertThat(deserialized).isEqualTo(original);
    }
}
