package com.baskette.dropship.service;

import com.baskette.dropship.model.StagingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildTrackerTest {

    private BuildTracker buildTracker;

    @BeforeEach
    void setUp() {
        buildTracker = new BuildTracker();
    }

    @Test
    void submitReturnsBuildId() {
        StagingResult result = new StagingResult(
                "droplet-1", "app-1", "app", null, null, 100, true, null);
        String buildId = buildTracker.submit(() -> result);
        assertThat(buildId).isNotNull().isNotBlank();
    }

    @Test
    void getStatusReturnsInProgressWhileBuildRunning() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        String buildId = buildTracker.submit(() -> {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StagingResult("d", "a", "n", null, null, 0, true, null);
        });

        // Give the executor a moment to start
        Thread.sleep(50);

        StagingResult status = buildTracker.getStatus(buildId);
        assertThat(status.success()).isFalse();
        assertThat(status.errorMessage()).contains("still in progress");

        latch.countDown();
    }

    @Test
    void getStatusReturnsResultWhenDone() throws Exception {
        StagingResult expected = new StagingResult(
                "droplet-2", "app-2", "myapp", "java_buildpack",
                "logs", 2000, true, null);
        String buildId = buildTracker.submit(() -> expected);

        // Wait for completion
        Thread.sleep(200);

        StagingResult status = buildTracker.getStatus(buildId);
        assertThat(status.success()).isTrue();
        assertThat(status.dropletGuid()).isEqualTo("droplet-2");
        assertThat(status.appGuid()).isEqualTo("app-2");
    }

    @Test
    void getStatusReturnsErrorWhenBuildFails() throws Exception {
        String buildId = buildTracker.submit(() -> {
            throw new RuntimeException("Maven build failed");
        });

        // Wait for completion
        Thread.sleep(200);

        StagingResult status = buildTracker.getStatus(buildId);
        assertThat(status.success()).isFalse();
        assertThat(status.errorMessage()).contains("Maven build failed");
    }

    @Test
    void getStatusThrowsForUnknownBuildId() {
        assertThatThrownBy(() -> buildTracker.getStatus("nonexistent-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown build ID");
    }
}
