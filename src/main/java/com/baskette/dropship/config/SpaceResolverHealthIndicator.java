package com.baskette.dropship.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class SpaceResolverHealthIndicator implements HealthIndicator {

    private final DropshipProperties properties;

    public SpaceResolverHealthIndicator(DropshipProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        String org = properties.sandboxOrg();
        String space = properties.sandboxSpace();

        if (org != null && !org.isBlank() && space != null && !space.isBlank()) {
            return Health.up()
                    .withDetail("sandboxOrg", org)
                    .withDetail("sandboxSpace", space)
                    .build();
        }
        return Health.up()
                .withDetail("mode", "per-user credentials")
                .build();
    }
}
