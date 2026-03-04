package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import org.cloudfoundry.client.v3.Metadata;
import org.cloudfoundry.client.v3.organizations.ListOrganizationsRequest;
import org.cloudfoundry.client.v3.organizations.ListOrganizationsResponse;
import org.cloudfoundry.client.v3.organizations.OrganizationResource;
import org.cloudfoundry.client.v3.organizations.OrganizationsV3;
import org.cloudfoundry.client.v3.spaces.ListSpacesRequest;
import org.cloudfoundry.client.v3.spaces.ListSpacesResponse;
import org.cloudfoundry.client.v3.spaces.SpaceResource;
import org.cloudfoundry.client.v3.spaces.SpacesV3;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceResolverTest {

    @Mock
    private ReactorCloudFoundryClient cloudFoundryClient;

    @Mock
    private OrganizationsV3 organizationsV3;

    @Mock
    private SpacesV3 spacesV3;

    @Captor
    private ArgumentCaptor<ListOrganizationsRequest> orgRequestCaptor;

    @Captor
    private ArgumentCaptor<ListSpacesRequest> spaceRequestCaptor;

    private DropshipProperties properties;
    private SpaceResolver spaceResolver;

    @BeforeEach
    void setUp() {
        properties = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");
        spaceResolver = new SpaceResolver(properties);
    }

    @Test
    void resolvesSpaceGuidSuccessfully() {
        when(cloudFoundryClient.organizationsV3()).thenReturn(organizationsV3);
        when(organizationsV3.list(any(ListOrganizationsRequest.class)))
                .thenReturn(Mono.just(ListOrganizationsResponse.builder()
                        .resource(OrganizationResource.builder()
                                .id("org-guid-123")
                                .name("test-org")
                                .createdAt("2024-01-01T00:00:00Z")
                                .metadata(Metadata.builder().build())
                                .build())
                        .build()));

        when(cloudFoundryClient.spacesV3()).thenReturn(spacesV3);
        when(spacesV3.list(any(ListSpacesRequest.class)))
                .thenReturn(Mono.just(ListSpacesResponse.builder()
                        .resource(SpaceResource.builder()
                                .id("space-guid-456")
                                .name("test-space")
                                .createdAt("2024-01-01T00:00:00Z")
                                .build())
                        .build()));

        StepVerifier.create(spaceResolver.resolveSpace(cloudFoundryClient))
                .expectNext("space-guid-456")
                .verifyComplete();

        verify(organizationsV3).list(orgRequestCaptor.capture());
        assertThat(orgRequestCaptor.getValue().getNames()).containsExactly("test-org");

        verify(spacesV3).list(spaceRequestCaptor.capture());
        assertThat(spaceRequestCaptor.getValue().getOrganizationIds()).containsExactly("org-guid-123");
        assertThat(spaceRequestCaptor.getValue().getNames()).containsExactly("test-space");
    }

    @Test
    void errorsWhenOrgNotFound() {
        when(cloudFoundryClient.organizationsV3()).thenReturn(organizationsV3);
        when(organizationsV3.list(any(ListOrganizationsRequest.class)))
                .thenReturn(Mono.just(ListOrganizationsResponse.builder()
                        .resources(Collections.emptyList())
                        .build()));

        StepVerifier.create(spaceResolver.resolveSpace(cloudFoundryClient))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(IllegalStateException.class);
                    assertThat(ex.getMessage()).contains("Organization not found");
                })
                .verify();
    }

    @Test
    void errorsWhenSpaceNotFound() {
        when(cloudFoundryClient.organizationsV3()).thenReturn(organizationsV3);
        when(organizationsV3.list(any(ListOrganizationsRequest.class)))
                .thenReturn(Mono.just(ListOrganizationsResponse.builder()
                        .resource(OrganizationResource.builder()
                                .id("org-guid-123")
                                .name("test-org")
                                .createdAt("2024-01-01T00:00:00Z")
                                .metadata(Metadata.builder().build())
                                .build())
                        .build()));

        when(cloudFoundryClient.spacesV3()).thenReturn(spacesV3);
        when(spacesV3.list(any(ListSpacesRequest.class)))
                .thenReturn(Mono.just(ListSpacesResponse.builder()
                        .resources(Collections.emptyList())
                        .build()));

        StepVerifier.create(spaceResolver.resolveSpace(cloudFoundryClient))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(IllegalStateException.class);
                    assertThat(ex.getMessage()).contains("Space not found");
                })
                .verify();
    }

    @Test
    void propagatesCfError() {
        when(cloudFoundryClient.organizationsV3()).thenReturn(organizationsV3);
        when(organizationsV3.list(any(ListOrganizationsRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        StepVerifier.create(spaceResolver.resolveSpace(cloudFoundryClient))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(RuntimeException.class);
                    assertThat(ex.getMessage()).isEqualTo("Connection refused");
                })
                .verify();
    }
}
