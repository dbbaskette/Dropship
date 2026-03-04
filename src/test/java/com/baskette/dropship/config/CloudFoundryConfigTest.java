package com.baskette.dropship.config;

import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.tokenprovider.ClientCredentialsGrantTokenProvider;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
        void clientCredentialsTokenProviderIsActive() {
            assertThat(tokenProvider).isInstanceOf(ClientCredentialsGrantTokenProvider.class);
        }

        @Test
        void tokenProviderIsNotPasswordGrant() {
            assertThat(tokenProvider).isNotInstanceOf(PasswordGrantTokenProvider.class);
        }

        @Test
        void cloudFoundryClientBeanIsCreated() {
            assertThat(cloudFoundryClient).isNotNull();
        }

        @Test
        void cloudFoundryOperationsBeanIsCreated() {
            assertThat(cloudFoundryOperations).isNotNull();
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
        private DefaultConnectionContext connectionContext;

        @Autowired
        private TokenProvider tokenProvider;

        @Autowired
        private ReactorCloudFoundryClient cloudFoundryClient;

        @Autowired
        private DefaultCloudFoundryOperations cloudFoundryOperations;

        @Test
        void connectionContextBeanIsCreated() {
            assertThat(connectionContext).isNotNull();
        }

        @Test
        void passwordGrantTokenProviderIsActive() {
            assertThat(tokenProvider).isInstanceOf(PasswordGrantTokenProvider.class);
        }

        @Test
        void tokenProviderIsNotClientCredentials() {
            assertThat(tokenProvider).isNotInstanceOf(ClientCredentialsGrantTokenProvider.class);
        }

        @Test
        void cloudFoundryClientBeanIsCreated() {
            assertThat(cloudFoundryClient).isNotNull();
        }

        @Test
        void cloudFoundryOperationsBeanIsCreated() {
            assertThat(cloudFoundryOperations).isNotNull();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "cf.client-id=",
            "cf.client-secret=",
            "cf.username=",
            "cf.password="
    })
    class NoCredentialsMode {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private DefaultConnectionContext connectionContext;

        @Test
        void applicationStartsWithoutCredentials() {
            assertThat(applicationContext).isNotNull();
        }

        @Test
        void connectionContextBeanStillExists() {
            assertThat(connectionContext).isNotNull();
        }

        @Test
        void noTokenProviderBeanExists() {
            assertThat(applicationContext.getBeanNamesForType(TokenProvider.class)).isEmpty();
        }

        @Test
        void noCloudFoundryClientBeanExists() {
            assertThat(applicationContext.getBeanNamesForType(ReactorCloudFoundryClient.class)).isEmpty();
        }

        @Test
        void noCloudFoundryOperationsBeanExists() {
            assertThat(applicationContext.getBeanNamesForType(DefaultCloudFoundryOperations.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "cf.client-id=test-client-id",
            "cf.client-secret=test-client-secret",
            "cf.username=test-user",
            "cf.password=test-pass"
    })
    class BothCredentialsMode {

        @Autowired
        private DefaultConnectionContext connectionContext;

        @Autowired
        private TokenProvider tokenProvider;

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void connectionContextBeanIsCreated() {
            assertThat(connectionContext).isNotNull();
        }

        @Test
        void clientCredentialsTakesPrecedence() {
            assertThat(tokenProvider).isInstanceOf(ClientCredentialsGrantTokenProvider.class);
        }

        @Test
        void passwordGrantBeanIsNotCreated() {
            assertThat(applicationContext.getBeanNamesForType(PasswordGrantTokenProvider.class)).isEmpty();
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
