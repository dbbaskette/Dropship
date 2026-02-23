package com.baskette.dropship.config;

import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.tokenprovider.ClientCredentialsGrantTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CloudFoundryConfigTest {

    @Autowired
    private DefaultConnectionContext connectionContext;

    @Autowired
    private ClientCredentialsGrantTokenProvider tokenProvider;

    @Autowired
    private ReactorCloudFoundryClient cloudFoundryClient;

    @Autowired
    private DefaultCloudFoundryOperations cloudFoundryOperations;

    @Test
    void connectionContextBeanIsCreated() {
        assertThat(connectionContext).isNotNull();
        assertThat(connectionContext.getApiHost()).isEqualTo("api.test.cf.example.com");
        assertThat(connectionContext.getSkipSslValidation()).hasValue(true);
    }

    @Test
    void tokenProviderBeanIsCreated() {
        assertThat(tokenProvider).isNotNull();
    }

    @Test
    void cloudFoundryClientBeanIsCreated() {
        assertThat(cloudFoundryClient).isNotNull();
    }

    @Test
    void cloudFoundryOperationsBeanIsCreated() {
        assertThat(cloudFoundryOperations).isNotNull();
    }

    @Test
    void extractHostParsesFullApiUrl() {
        CloudFoundryConfig config = new CloudFoundryConfig();
        assertThat(config.extractHost("https://api.sys.example.com")).isEqualTo("api.sys.example.com");
    }

    @Test
    void extractHostHandlesUrlWithPort() {
        CloudFoundryConfig config = new CloudFoundryConfig();
        assertThat(config.extractHost("https://api.sys.example.com:443")).isEqualTo("api.sys.example.com");
    }

    @Test
    void extractHostHandlesUrlWithPath() {
        CloudFoundryConfig config = new CloudFoundryConfig();
        assertThat(config.extractHost("https://api.sys.example.com/v2")).isEqualTo("api.sys.example.com");
    }
}
