package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletRequest;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private ReactorCloudFoundryClient cfClient;

    @Mock
    private SpaceResolver spaceResolver;

    @Mock
    private ApplicationsV3 applicationsV3;

    @Captor
    private ArgumentCaptor<SetApplicationCurrentDropletRequest> dropletRequestCaptor;

    private DropshipProperties properties;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        properties = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");
        taskService = new TaskService(cfClient, properties, spaceResolver);
    }

    @Test
    void setCurrentDropletSetsDropletOnApp() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.just(SetApplicationCurrentDropletResponse.builder()
                        .data(Relationship.builder().id("droplet-guid-123").build())
                        .build()));

        StepVerifier.create(taskService.setCurrentDroplet("app-guid-456", "droplet-guid-123"))
                .verifyComplete();

        verify(applicationsV3).setCurrentDroplet(dropletRequestCaptor.capture());
        SetApplicationCurrentDropletRequest request = dropletRequestCaptor.getValue();
        assertThat(request.getApplicationId()).isEqualTo("app-guid-456");
        assertThat(request.getData().getId()).isEqualTo("droplet-guid-123");
    }

    @Test
    void setCurrentDropletPropagatesCfApiError() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("CF API error")));

        StepVerifier.create(taskService.setCurrentDroplet("app-guid-456", "droplet-guid-123"))
                .expectError(RuntimeException.class)
                .verify();
    }
}
