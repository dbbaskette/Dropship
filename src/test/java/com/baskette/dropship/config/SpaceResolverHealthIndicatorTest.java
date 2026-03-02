package com.baskette.dropship.config;

import com.baskette.dropship.service.SpaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceResolverHealthIndicatorTest {

    @Mock
    private SpaceResolver spaceResolver;

    @Test
    void healthReturnsUpWhenSpaceGuidResolved() {
        when(spaceResolver.getSpaceGuid()).thenReturn("test-space-guid-123");

        SpaceResolverHealthIndicator indicator = new SpaceResolverHealthIndicator(spaceResolver);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("spaceGuid", "test-space-guid-123");
    }

    @Test
    void healthReturnsDownWhenSpaceGuidNotResolved() {
        when(spaceResolver.getSpaceGuid()).thenThrow(new IllegalStateException("Space GUID has not been resolved yet"));

        SpaceResolverHealthIndicator indicator = new SpaceResolverHealthIndicator(spaceResolver);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "Space GUID not resolved");
    }
}
