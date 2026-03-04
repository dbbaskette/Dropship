package com.baskette.dropship.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceResolverHealthIndicatorTest {

    @Test
    void healthReturnsUpWhenConfigPresent() {
        DropshipProperties properties = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");

        SpaceResolverHealthIndicator indicator = new SpaceResolverHealthIndicator(properties);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("sandboxOrg", "test-org");
        assertThat(health.getDetails()).containsEntry("sandboxSpace", "test-space");
    }

    @Test
    void healthReturnsDownWhenOrgBlank() {
        DropshipProperties properties = new DropshipProperties(
                "", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");

        SpaceResolverHealthIndicator indicator = new SpaceResolverHealthIndicator(properties);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "sandboxOrg or sandboxSpace not configured");
    }

    @Test
    void healthReturnsDownWhenSpaceBlank() {
        DropshipProperties properties = new DropshipProperties(
                "test-org", "", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");

        SpaceResolverHealthIndicator indicator = new SpaceResolverHealthIndicator(properties);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "sandboxOrg or sandboxSpace not configured");
    }
}
