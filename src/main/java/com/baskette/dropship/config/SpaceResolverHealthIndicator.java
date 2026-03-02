package com.baskette.dropship.config;

import com.baskette.dropship.service.SpaceResolver;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class SpaceResolverHealthIndicator implements HealthIndicator {

    private final SpaceResolver spaceResolver;

    public SpaceResolverHealthIndicator(SpaceResolver spaceResolver) {
        this.spaceResolver = spaceResolver;
    }

    @Override
    public Health health() {
        try {
            String spaceGuid = spaceResolver.getSpaceGuid();
            return Health.up().withDetail("spaceGuid", spaceGuid).build();
        } catch (IllegalStateException e) {
            return Health.down().withDetail("reason", "Space GUID not resolved").build();
        }
    }
}
