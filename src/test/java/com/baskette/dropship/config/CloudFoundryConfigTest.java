package com.baskette.dropship.config;

import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.tokenprovider.ClientCredentialsGrantTokenProvider;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class CloudFoundryConfigTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class ClientCredentialsMode {

        @Autowired
        private DefaultConnectionContext connectionContext;

        @Autowired
        private TokenProvider tokenProvider;

        @Test
        void connectionContextBeanIsCreated() {
            assertThat(connectionContext).isNotNull();
            assertThat(connectionContext.getApiHost()).isEqualTo("api.test.cf.example.com");
            assertThat(connectionContext.getSkipSslValidation()).hasValue(true);
        }

        @Test
        void clientCredentialsTokenProviderIsActive() {
            assertThat(tokenProvider).isInstanceOf(ClientCredentialsGrantTokenProvider.class);
        }

        @Test
        void tokenProviderIsNotPasswordGrant() {
            assertThat(tokenProvider).isNotInstanceOf(PasswordGrantTokenProvider.class);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "cf.client-id=",
            "cf.username=test-user",
            "cf.password=test-pass"
    })
    class PasswordGrantMode {

        @Autowired
        private TokenProvider tokenProvider;

        @Test
        void passwordGrantTokenProviderIsActive() {
            assertThat(tokenProvider).isInstanceOf(PasswordGrantTokenProvider.class);
        }

        @Test
        void tokenProviderIsNotClientCredentials() {
            assertThat(tokenProvider).isNotInstanceOf(ClientCredentialsGrantTokenProvider.class);
        }
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
