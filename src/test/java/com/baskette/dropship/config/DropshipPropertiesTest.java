package com.baskette.dropship.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DropshipPropertiesTest {

    @Autowired
    private DropshipProperties properties;

    @Test
    void propertiesArePopulatedFromYaml() {
        assertThat(properties.sandboxOrg()).isEqualTo("test-org");
        assertThat(properties.sandboxSpace()).isEqualTo("test-space");
        assertThat(properties.cfApiUrl()).isEqualTo("https://api.test.cf.example.com");
    }

    @Test
    void defaultsBindCorrectly() {
        assertThat(properties.maxTaskMemoryMb()).isEqualTo(2048);
        assertThat(properties.maxTaskDiskMb()).isEqualTo(4096);
        assertThat(properties.maxTaskTimeoutSeconds()).isEqualTo(900);
        assertThat(properties.defaultTaskMemoryMb()).isEqualTo(512);
        assertThat(properties.defaultStagingMemoryMb()).isEqualTo(1024);
        assertThat(properties.defaultStagingDiskMb()).isEqualTo(2048);
        assertThat(properties.appNamePrefix()).isEqualTo("dropship-");
    }
}
