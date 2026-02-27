package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletRequest;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ReactorCloudFoundryClient cfClient;
    private final DropshipProperties properties;
    private final SpaceResolver spaceResolver;

    public TaskService(ReactorCloudFoundryClient cfClient,
                       DropshipProperties properties,
                       SpaceResolver spaceResolver) {
        this.cfClient = cfClient;
        this.properties = properties;
        this.spaceResolver = spaceResolver;
    }

    Mono<Void> setCurrentDroplet(String appGuid, String dropletGuid) {
        log.info("Setting current droplet: appGuid={}, dropletGuid={}", appGuid, dropletGuid);
        return cfClient.applicationsV3()
                .setCurrentDroplet(SetApplicationCurrentDropletRequest.builder()
                        .applicationId(appGuid)
                        .data(Relationship.builder()
                                .id(dropletGuid)
                                .build())
                        .build())
                .then();
    }
}
