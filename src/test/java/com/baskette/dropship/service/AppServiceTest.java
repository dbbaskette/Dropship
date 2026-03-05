package com.baskette.dropship.service;

import com.baskette.dropship.model.AppResult;
import org.cloudfoundry.client.v3.BuildpackData;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.LifecycleType;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.ToOneRelationship;
import org.cloudfoundry.client.v3.applications.ApplicationState;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.applications.GetApplicationProcessStatisticsRequest;
import org.cloudfoundry.client.v3.applications.GetApplicationProcessStatisticsResponse;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletRequest;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletResponse;
import org.cloudfoundry.client.v3.applications.StartApplicationRequest;
import org.cloudfoundry.client.v3.applications.StartApplicationResponse;
import org.cloudfoundry.client.v3.applications.StopApplicationRequest;
import org.cloudfoundry.client.v3.applications.StopApplicationResponse;
import org.cloudfoundry.client.v3.domains.DomainRelationships;
import org.cloudfoundry.client.v3.domains.DomainResource;
import org.cloudfoundry.client.v3.domains.DomainsV3;
import org.cloudfoundry.client.v3.domains.ListDomainsRequest;
import org.cloudfoundry.client.v3.domains.ListDomainsResponse;
import org.cloudfoundry.client.v3.processes.ProcessState;
import org.cloudfoundry.client.v3.processes.ProcessStatisticsResource;
import org.cloudfoundry.client.v3.routes.CreateRouteRequest;
import org.cloudfoundry.client.v3.routes.CreateRouteResponse;
import org.cloudfoundry.client.v3.routes.DeleteRouteRequest;
import org.cloudfoundry.client.v3.routes.InsertRouteDestinationsRequest;
import org.cloudfoundry.client.v3.routes.InsertRouteDestinationsResponse;
import org.cloudfoundry.client.v3.routes.RouteRelationships;
import org.cloudfoundry.client.v3.routes.RoutesV3;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppServiceTest {

    private static final Lifecycle BUILDPACK_LIFECYCLE = Lifecycle.builder()
            .type(LifecycleType.BUILDPACK)
            .data(BuildpackData.builder().build())
            .build();

    private static final DomainRelationships EMPTY_DOMAIN_RELATIONSHIPS =
            DomainRelationships.builder()
                    .organization(ToOneRelationship.builder().build())
                    .build();

    @Mock
    private ReactorCloudFoundryClient cfClient;

    @Mock
    private SpaceResolver spaceResolver;

    @Mock
    private ApplicationsV3 applicationsV3;

    @Mock
    private DomainsV3 domainsV3;

    @Mock
    private RoutesV3 routesV3;

    private AppService appService;

    @BeforeEach
    void setUp() {
        appService = new AppService(spaceResolver);
    }

    private void stubClientApis() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(cfClient.domainsV3()).thenReturn(domainsV3);
        when(cfClient.routesV3()).thenReturn(routesV3);
    }

    private SetApplicationCurrentDropletResponse stubDropletResponse() {
        return SetApplicationCurrentDropletResponse.builder()
                .data(Relationship.builder().id("droplet-guid-1").build())
                .build();
    }

    private DomainResource buildDomainResource(String id, String name, boolean internal) {
        return DomainResource.builder()
                .id(id)
                .name(name)
                .isInternal(internal)
                .relationships(EMPTY_DOMAIN_RELATIONSHIPS)
                .createdAt("2024-01-01T00:00:00Z")
                .build();
    }

    private RouteRelationships emptyRouteRelationships() {
        return RouteRelationships.builder()
                .domain(ToOneRelationship.builder().build())
                .space(ToOneRelationship.builder().build())
                .build();
    }

    private CreateRouteResponse buildRouteResponse(String id, String host) {
        return CreateRouteResponse.builder()
                .id(id)
                .host(host)
                .path("")
                .url(host + ".apps.example.com")
                .relationships(emptyRouteRelationships())
                .createdAt("2024-01-01T00:00:00Z")
                .build();
    }

    private StartApplicationResponse buildStartResponse(String id, String name) {
        return StartApplicationResponse.builder()
                .id(id)
                .name(name)
                .state(ApplicationState.STARTED)
                .lifecycle(BUILDPACK_LIFECYCLE)
                .createdAt("2024-01-01T00:00:00Z")
                .build();
    }

    private StopApplicationResponse buildStopResponse(String id, String name) {
        return StopApplicationResponse.builder()
                .id(id)
                .name(name)
                .state(ApplicationState.STOPPED)
                .lifecycle(BUILDPACK_LIFECYCLE)
                .createdAt("2024-01-01T00:00:00Z")
                .build();
    }

    @Test
    void startApplicationCreatesRouteAndStartsApp() {
        stubClientApis();

        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.just(stubDropletResponse()));

        when(spaceResolver.resolveSpace(cfClient, "test-org", "test-space"))
                .thenReturn(Mono.just("space-guid-1"));

        when(domainsV3.list(any(ListDomainsRequest.class)))
                .thenReturn(Mono.just(ListDomainsResponse.builder()
                        .resource(buildDomainResource("domain-guid-1", "apps.example.com", false))
                        .build()));

        when(routesV3.create(any(CreateRouteRequest.class)))
                .thenReturn(Mono.just(buildRouteResponse("route-guid-1", "my-app")));

        when(routesV3.insertDestinations(any(InsertRouteDestinationsRequest.class)))
                .thenReturn(Mono.just(InsertRouteDestinationsResponse.builder().build()));

        when(applicationsV3.start(any(StartApplicationRequest.class)))
                .thenReturn(Mono.just(buildStartResponse("app-guid-1", "my-app")));

        StepVerifier.create(appService.startApplication(
                        "app-guid-1", "my-app", "droplet-guid-1",
                        "test-org", "test-space", cfClient))
                .assertNext(result -> {
                    assertThat(result.appGuid()).isEqualTo("app-guid-1");
                    assertThat(result.appName()).isEqualTo("my-app");
                    assertThat(result.routeUrl()).isEqualTo("https://my-app.apps.example.com");
                    assertThat(result.routeGuid()).isEqualTo("route-guid-1");
                    assertThat(result.state()).isEqualTo(AppResult.State.STARTING);
                    assertThat(result.errorMessage()).isNull();
                })
                .verifyComplete();

        verify(applicationsV3).setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class));
        verify(routesV3).create(any(CreateRouteRequest.class));
        verify(routesV3).insertDestinations(any(InsertRouteDestinationsRequest.class));
        verify(applicationsV3).start(any(StartApplicationRequest.class));
    }

    @Test
    void startApplicationReturnsErrorOnFailure() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("Droplet not found")));

        StepVerifier.create(appService.startApplication(
                        "app-guid-1", "my-app", "bad-droplet",
                        "test-org", "test-space", cfClient))
                .assertNext(result -> {
                    assertThat(result.state()).isEqualTo(AppResult.State.CRASHED);
                    assertThat(result.errorMessage()).contains("Droplet not found");
                })
                .verifyComplete();
    }

    @Test
    void getAppStatusReturnsRunning() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.getProcessStatistics(any(GetApplicationProcessStatisticsRequest.class)))
                .thenReturn(Mono.just(GetApplicationProcessStatisticsResponse.builder()
                        .resource(ProcessStatisticsResource.builder()
                                .state(ProcessState.RUNNING)
                                .type("web")
                                .uptime(0L)
                                .index(0)
                                .host("10.0.0.1")
                                .fileDescriptorQuota(16384L)
                                .build())
                        .build()));

        StepVerifier.create(appService.getAppStatus(
                        "app-guid-1", "my-app",
                        "https://my-app.apps.example.com", "route-guid-1", cfClient))
                .assertNext(result -> {
                    assertThat(result.state()).isEqualTo(AppResult.State.RUNNING);
                    assertThat(result.routeUrl()).isEqualTo("https://my-app.apps.example.com");
                })
                .verifyComplete();
    }

    @Test
    void getAppStatusReturnsStarting() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.getProcessStatistics(any(GetApplicationProcessStatisticsRequest.class)))
                .thenReturn(Mono.just(GetApplicationProcessStatisticsResponse.builder()
                        .resource(ProcessStatisticsResource.builder()
                                .state(ProcessState.STARTING)
                                .type("web")
                                .uptime(0L)
                                .index(0)
                                .host("10.0.0.1")
                                .fileDescriptorQuota(16384L)
                                .build())
                        .build()));

        StepVerifier.create(appService.getAppStatus(
                        "app-guid-1", "my-app", null, null, cfClient))
                .assertNext(result ->
                        assertThat(result.state()).isEqualTo(AppResult.State.STARTING))
                .verifyComplete();
    }

    @Test
    void getAppStatusReturnsCrashed() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.getProcessStatistics(any(GetApplicationProcessStatisticsRequest.class)))
                .thenReturn(Mono.just(GetApplicationProcessStatisticsResponse.builder()
                        .resource(ProcessStatisticsResource.builder()
                                .state(ProcessState.CRASHED)
                                .type("web")
                                .uptime(0L)
                                .index(0)
                                .host("10.0.0.1")
                                .fileDescriptorQuota(16384L)
                                .details("OOM killed")
                                .build())
                        .build()));

        StepVerifier.create(appService.getAppStatus(
                        "app-guid-1", null, null, null, cfClient))
                .assertNext(result -> {
                    assertThat(result.state()).isEqualTo(AppResult.State.CRASHED);
                    assertThat(result.errorMessage()).isEqualTo("OOM killed");
                })
                .verifyComplete();
    }

    @Test
    void getAppStatusReturnsStartingWhenNoInstances() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.getProcessStatistics(any(GetApplicationProcessStatisticsRequest.class)))
                .thenReturn(Mono.just(GetApplicationProcessStatisticsResponse.builder()
                        .resources(List.of())
                        .build()));

        StepVerifier.create(appService.getAppStatus(
                        "app-guid-1", null, null, null, cfClient))
                .assertNext(result ->
                        assertThat(result.state()).isEqualTo(AppResult.State.STARTING))
                .verifyComplete();
    }

    @Test
    void getAppStatusReturnsStoppedForDown() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.getProcessStatistics(any(GetApplicationProcessStatisticsRequest.class)))
                .thenReturn(Mono.just(GetApplicationProcessStatisticsResponse.builder()
                        .resource(ProcessStatisticsResource.builder()
                                .state(ProcessState.DOWN)
                                .type("web")
                                .uptime(0L)
                                .index(0)
                                .host("10.0.0.1")
                                .fileDescriptorQuota(16384L)
                                .build())
                        .build()));

        StepVerifier.create(appService.getAppStatus(
                        "app-guid-1", null, null, null, cfClient))
                .assertNext(result ->
                        assertThat(result.state()).isEqualTo(AppResult.State.STOPPED))
                .verifyComplete();
    }

    @Test
    void stopApplicationStopsAndDeletesRoute() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(cfClient.routesV3()).thenReturn(routesV3);

        when(applicationsV3.stop(any(StopApplicationRequest.class)))
                .thenReturn(Mono.just(buildStopResponse("app-guid-1", "my-app")));

        when(routesV3.delete(any(DeleteRouteRequest.class)))
                .thenReturn(Mono.just("job-guid-1"));

        StepVerifier.create(appService.stopApplication(
                        "app-guid-1", "my-app", "route-guid-1", cfClient))
                .assertNext(result -> {
                    assertThat(result.appGuid()).isEqualTo("app-guid-1");
                    assertThat(result.state()).isEqualTo(AppResult.State.STOPPED);
                    assertThat(result.errorMessage()).isNull();
                })
                .verifyComplete();

        verify(applicationsV3).stop(any(StopApplicationRequest.class));
        verify(routesV3).delete(any(DeleteRouteRequest.class));
    }

    @Test
    void stopApplicationSkipsRouteDeleteWhenNoRouteGuid() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.stop(any(StopApplicationRequest.class)))
                .thenReturn(Mono.just(buildStopResponse("app-guid-1", "my-app")));

        StepVerifier.create(appService.stopApplication(
                        "app-guid-1", "my-app", null, cfClient))
                .assertNext(result ->
                        assertThat(result.state()).isEqualTo(AppResult.State.STOPPED))
                .verifyComplete();

        verify(applicationsV3).stop(any(StopApplicationRequest.class));
    }

    @Test
    void stopApplicationReturnsErrorOnFailure() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);

        when(applicationsV3.stop(any(StopApplicationRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("App not found")));

        StepVerifier.create(appService.stopApplication(
                        "app-guid-1", "my-app", null, cfClient))
                .assertNext(result -> {
                    assertThat(result.state()).isEqualTo(AppResult.State.CRASHED);
                    assertThat(result.errorMessage()).contains("App not found");
                })
                .verifyComplete();
    }

    @Test
    void findSharedDomainSkipsInternalDomains() {
        when(cfClient.domainsV3()).thenReturn(domainsV3);

        when(domainsV3.list(any(ListDomainsRequest.class)))
                .thenReturn(Mono.just(ListDomainsResponse.builder()
                        .resource(buildDomainResource("internal-domain-guid", "apps.internal", true))
                        .resource(buildDomainResource("external-domain-guid", "apps.example.com", false))
                        .build()));

        StepVerifier.create(appService.findSharedDomain(cfClient))
                .assertNext(domainInfo -> {
                    assertThat(domainInfo.domainGuid()).isEqualTo("external-domain-guid");
                    assertThat(domainInfo.domainName()).isEqualTo("apps.example.com");
                })
                .verifyComplete();
    }

    @Test
    void findSharedDomainErrorsWhenNoneAvailable() {
        when(cfClient.domainsV3()).thenReturn(domainsV3);

        when(domainsV3.list(any(ListDomainsRequest.class)))
                .thenReturn(Mono.just(ListDomainsResponse.builder().build()));

        StepVerifier.create(appService.findSharedDomain(cfClient))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void mapProcessStateCoversAllValues() {
        assertThat(AppService.mapProcessState(ProcessState.RUNNING))
                .isEqualTo(AppResult.State.RUNNING);
        assertThat(AppService.mapProcessState(ProcessState.STARTING))
                .isEqualTo(AppResult.State.STARTING);
        assertThat(AppService.mapProcessState(ProcessState.CRASHED))
                .isEqualTo(AppResult.State.CRASHED);
        assertThat(AppService.mapProcessState(ProcessState.DOWN))
                .isEqualTo(AppResult.State.STOPPED);
    }
}
