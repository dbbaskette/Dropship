package com.baskette.dropship.service;

import com.baskette.dropship.model.AppResult;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.ToOneRelationship;
import org.cloudfoundry.client.v3.applications.GetApplicationProcessStatisticsRequest;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletRequest;
import org.cloudfoundry.client.v3.applications.StartApplicationRequest;
import org.cloudfoundry.client.v3.applications.StopApplicationRequest;
import org.cloudfoundry.client.v3.domains.ListDomainsRequest;
import org.cloudfoundry.client.v3.processes.ProcessState;
import org.cloudfoundry.client.v3.routes.Application;
import org.cloudfoundry.client.v3.routes.CreateRouteRequest;
import org.cloudfoundry.client.v3.routes.DeleteRouteRequest;
import org.cloudfoundry.client.v3.routes.Destination;
import org.cloudfoundry.client.v3.routes.InsertRouteDestinationsRequest;
import org.cloudfoundry.client.v3.routes.RouteRelationships;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AppService {

    private static final Logger log = LoggerFactory.getLogger(AppService.class);

    private final SpaceResolver spaceResolver;

    public AppService(SpaceResolver spaceResolver) {
        this.spaceResolver = spaceResolver;
    }

    public Mono<AppResult> startApplication(String appGuid, String appName, String dropletGuid,
                                             String org, String space,
                                             ReactorCloudFoundryClient client) {
        log.info("Starting app: appGuid={}, appName={}, dropletGuid={}", appGuid, appName, dropletGuid);

        return setCurrentDroplet(appGuid, dropletGuid, client)
                .then(Mono.defer(() -> spaceResolver.resolveSpace(client, org, space)))
                .flatMap(spaceGuid -> findSharedDomain(client)
                        .flatMap(domainInfo -> createRoute(appName, domainInfo.domainGuid(),
                                domainInfo.domainName(), spaceGuid, client)
                                .flatMap(routeInfo -> mapRouteToApp(routeInfo.routeGuid(), appGuid, client)
                                        .then(startApp(appGuid, client))
                                        .thenReturn(new AppResult(
                                                appGuid, appName, routeInfo.routeUrl(),
                                                routeInfo.routeGuid(),
                                                AppResult.State.STARTING, null)))))
                .doOnSuccess(result -> log.info("App start initiated: appGuid={}, routeUrl={}",
                        appGuid, result.routeUrl()))
                .onErrorResume(error -> {
                    log.error("Failed to start app: appGuid={}, error={}", appGuid, error.getMessage());
                    return Mono.just(new AppResult(
                            appGuid, appName, null, null,
                            AppResult.State.CRASHED, error.getMessage()));
                });
    }

    public Mono<AppResult> getAppStatus(String appGuid, String appName,
                                         String routeUrl, String routeGuid,
                                         ReactorCloudFoundryClient client) {
        return client.applicationsV3()
                .getProcessStatistics(GetApplicationProcessStatisticsRequest.builder()
                        .applicationId(appGuid)
                        .type("web")
                        .build())
                .map(response -> {
                    if (response.getResources() == null || response.getResources().isEmpty()) {
                        return new AppResult(appGuid, appName, routeUrl, routeGuid,
                                AppResult.State.STARTING, null);
                    }
                    ProcessState processState = response.getResources().get(0).getState();
                    AppResult.State state = mapProcessState(processState);
                    String details = response.getResources().get(0).getDetails();
                    return new AppResult(appGuid, appName, routeUrl, routeGuid, state, details);
                })
                .onErrorResume(error -> Mono.just(new AppResult(
                        appGuid, appName, routeUrl, routeGuid,
                        AppResult.State.CRASHED, error.getMessage())));
    }

    public Mono<AppResult> stopApplication(String appGuid, String appName,
                                            String routeGuid,
                                            ReactorCloudFoundryClient client) {
        log.info("Stopping app: appGuid={}, routeGuid={}", appGuid, routeGuid);

        Mono<Void> stopMono = client.applicationsV3()
                .stop(StopApplicationRequest.builder()
                        .applicationId(appGuid)
                        .build())
                .then();

        Mono<Void> deleteRouteMono = (routeGuid != null && !routeGuid.isBlank())
                ? client.routesV3()
                        .delete(DeleteRouteRequest.builder()
                                .routeId(routeGuid)
                                .build())
                        .then()
                : Mono.empty();

        return stopMono
                .then(deleteRouteMono)
                .thenReturn(new AppResult(appGuid, appName, null, routeGuid,
                        AppResult.State.STOPPED, null))
                .doOnSuccess(result -> log.info("App stopped: appGuid={}", appGuid))
                .onErrorResume(error -> {
                    log.error("Failed to stop app: appGuid={}, error={}", appGuid, error.getMessage());
                    return Mono.just(new AppResult(
                            appGuid, appName, null, routeGuid,
                            AppResult.State.CRASHED, error.getMessage()));
                });
    }

    Mono<Void> setCurrentDroplet(String appGuid, String dropletGuid,
                                  ReactorCloudFoundryClient client) {
        return client.applicationsV3()
                .setCurrentDroplet(SetApplicationCurrentDropletRequest.builder()
                        .applicationId(appGuid)
                        .data(Relationship.builder()
                                .id(dropletGuid)
                                .build())
                        .build())
                .then();
    }

    Mono<DomainInfo> findSharedDomain(ReactorCloudFoundryClient client) {
        return client.domainsV3()
                .list(ListDomainsRequest.builder().build())
                .flatMap(response -> {
                    if (response.getResources() == null || response.getResources().isEmpty()) {
                        return Mono.error(new IllegalStateException("No domains found"));
                    }
                    var domain = response.getResources().stream()
                            .filter(d -> !d.isInternal())
                            .findFirst()
                            .orElse(response.getResources().get(0));
                    return Mono.just(new DomainInfo(domain.getId(), domain.getName()));
                });
    }

    Mono<RouteInfo> createRoute(String appName, String domainGuid,
                                 String domainName, String spaceGuid,
                                 ReactorCloudFoundryClient client) {
        return client.routesV3()
                .create(CreateRouteRequest.builder()
                        .host(appName)
                        .relationships(RouteRelationships.builder()
                                .domain(ToOneRelationship.builder()
                                        .data(Relationship.builder()
                                                .id(domainGuid)
                                                .build())
                                        .build())
                                .space(ToOneRelationship.builder()
                                        .data(Relationship.builder()
                                                .id(spaceGuid)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .map(response -> {
                    String routeUrl = "https://" + appName + "." + domainName;
                    log.info("Created route: routeGuid={}, url={}", response.getId(), routeUrl);
                    return new RouteInfo(response.getId(), routeUrl);
                });
    }

    Mono<Void> mapRouteToApp(String routeGuid, String appGuid,
                              ReactorCloudFoundryClient client) {
        return client.routesV3()
                .insertDestinations(InsertRouteDestinationsRequest.builder()
                        .routeId(routeGuid)
                        .destination(Destination.builder()
                                .application(Application.builder()
                                        .applicationId(appGuid)
                                        .build())
                                .build())
                        .build())
                .then();
    }

    Mono<Void> startApp(String appGuid, ReactorCloudFoundryClient client) {
        return client.applicationsV3()
                .start(StartApplicationRequest.builder()
                        .applicationId(appGuid)
                        .build())
                .then();
    }

    static AppResult.State mapProcessState(ProcessState processState) {
        return switch (processState) {
            case RUNNING -> AppResult.State.RUNNING;
            case STARTING -> AppResult.State.STARTING;
            case CRASHED -> AppResult.State.CRASHED;
            case DOWN -> AppResult.State.STOPPED;
        };
    }

    record DomainInfo(String domainGuid, String domainName) {}
    record RouteInfo(String routeGuid, String routeUrl) {}
}
