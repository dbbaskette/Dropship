package com.baskette.dropship.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StagingResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successResultSerializesToJson() throws Exception {
        StagingResult result = new StagingResult(
                "droplet-guid-123",
                "app-guid-456",
                "dropship-abc12345",
                "java_buildpack",
                "-----> Downloading Java Buildpack\n-----> Compiling...",
                4500L,
                true,
                null
        );

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"dropletGuid\":\"droplet-guid-123\"");
        assertThat(json).contains("\"appGuid\":\"app-guid-456\"");
        assertThat(json).contains("\"appName\":\"dropship-abc12345\"");
        assertThat(json).contains("\"buildpack\":\"java_buildpack\"");
        assertThat(json).contains("\"stagingLogs\":");
        assertThat(json).contains("\"durationMs\":4500");
        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("\"errorMessage\":null");
    }

    @Test
    void failureResultSerializesToJson() throws Exception {
        StagingResult result = new StagingResult(
                null,
                "app-guid-789",
                "dropship-xyz98765",
                null,
                "-----> Staging failed",
                1200L,
                false,
                "Buildpack compilation step failed"
        );

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"dropletGuid\":null");
        assertThat(json).contains("\"appGuid\":\"app-guid-789\"");
        assertThat(json).contains("\"appName\":\"dropship-xyz98765\"");
        assertThat(json).contains("\"success\":false");
        assertThat(json).contains("\"errorMessage\":\"Buildpack compilation step failed\"");
    }

    @Test
    void deserializesFromJson() throws Exception {
        String json = """
                {
                  "dropletGuid": "droplet-abc",
                  "appGuid": "app-def",
                  "appName": "dropship-testapp",
                  "buildpack": "nodejs_buildpack",
                  "stagingLogs": "Staging complete",
                  "durationMs": 3000,
                  "success": true,
                  "errorMessage": null
                }
                """;

        StagingResult result = objectMapper.readValue(json, StagingResult.class);

        assertThat(result.dropletGuid()).isEqualTo("droplet-abc");
        assertThat(result.appGuid()).isEqualTo("app-def");
        assertThat(result.appName()).isEqualTo("dropship-testapp");
        assertThat(result.buildpack()).isEqualTo("nodejs_buildpack");
        assertThat(result.stagingLogs()).isEqualTo("Staging complete");
        assertThat(result.durationMs()).isEqualTo(3000L);
        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void roundTripSerializationPreservesData() throws Exception {
        StagingResult original = new StagingResult(
                "droplet-round-trip",
                "app-round-trip",
                "dropship-roundtrip",
                "python_buildpack",
                "-----> Installing python 3.11\n-----> Done",
                7800L,
                true,
                null
        );

        String json = objectMapper.writeValueAsString(original);
        StagingResult deserialized = objectMapper.readValue(json, StagingResult.class);

        assertThat(deserialized).isEqualTo(original);
    }
}
