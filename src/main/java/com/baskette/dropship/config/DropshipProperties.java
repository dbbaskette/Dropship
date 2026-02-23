package com.baskette.dropship.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dropship")
public record DropshipProperties(
        String sandboxOrg,
        String sandboxSpace,
        String cfApiUrl,
        int maxTaskMemoryMb,
        int maxTaskDiskMb,
        int maxTaskTimeoutSeconds,
        int defaultTaskMemoryMb,
        int defaultStagingMemoryMb,
        int defaultStagingDiskMb,
        String appNamePrefix
) {
    public DropshipProperties {
        if (maxTaskMemoryMb == 0) maxTaskMemoryMb = 2048;
        if (maxTaskDiskMb == 0) maxTaskDiskMb = 4096;
        if (maxTaskTimeoutSeconds == 0) maxTaskTimeoutSeconds = 900;
        if (defaultTaskMemoryMb == 0) defaultTaskMemoryMb = 512;
        if (defaultStagingMemoryMb == 0) defaultStagingMemoryMb = 1024;
        if (defaultStagingDiskMb == 0) defaultStagingDiskMb = 2048;
        if (appNamePrefix == null || appNamePrefix.isBlank()) appNamePrefix = "dropship-";
    }
}
