package com.baskette.dropship.config;

import org.cloudfoundry.client.v2.info.GetInfoRequest;
import org.cloudfoundry.client.v2.info.Info;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.organizations.Organizations;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudFoundryHealthCheckTest {

    @Mock
    private ReactorCloudFoundryClient cloudFoundryClient;

    @Mock
    private DefaultCloudFoundryOperations cloudFoundryOperations;

    @Mock
    private Info info;

    @Mock
    private Organizations organizations;

    private DropshipProperties properties;
    private CloudFoundryHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        properties = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");
        healthCheck = new CloudFoundryHealthCheck(cloudFoundryClient, cloudFoundryOperations, properties);
    }

    @Test
    void verifyConnectivitySucceeds() {
        when(cloudFoundryClient.info()).thenReturn(info);
        when(info.get(any(GetInfoRequest.class))).thenReturn(Mono.empty());
        when(cloudFoundryOperations.organizations()).thenReturn(organizations);
        when(organizations.list()).thenReturn(Flux.empty());

        assertThatCode(() -> healthCheck.verifyConnectivity()).doesNotThrowAnyException();

        verify(cloudFoundryClient).info();
        verify(cloudFoundryOperations).organizations();
    }

    @Test
    void verifyConnectivityHandlesApiUnreachable() {
        when(cloudFoundryClient.info()).thenReturn(info);
        when(info.get(any(GetInfoRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        assertThatCode(() -> healthCheck.verifyConnectivity()).doesNotThrowAnyException();
    }

    @Test
    void verifyConnectivityHandlesAuthFailure() {
        when(cloudFoundryClient.info()).thenReturn(info);
        when(info.get(any(GetInfoRequest.class))).thenReturn(Mono.empty());
        when(cloudFoundryOperations.organizations()).thenReturn(organizations);
        when(organizations.list())
                .thenReturn(Flux.error(new RuntimeException("401 Unauthorized")));

        assertThatCode(() -> healthCheck.verifyConnectivity()).doesNotThrowAnyException();
    }
}
