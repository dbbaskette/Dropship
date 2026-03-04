package com.baskette.dropship.config;

import org.cloudfoundry.client.v2.info.GetInfoRequest;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(ReactorCloudFoundryClient.class)
public class CloudFoundryHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(CloudFoundryHealthCheck.class);

    private final ReactorCloudFoundryClient cloudFoundryClient;
    private final DefaultCloudFoundryOperations cloudFoundryOperations;
    private final DropshipProperties properties;

    public CloudFoundryHealthCheck(ReactorCloudFoundryClient cloudFoundryClient,
                                   DefaultCloudFoundryOperations cloudFoundryOperations,
                                   DropshipProperties properties) {
        this.cloudFoundryClient = cloudFoundryClient;
        this.cloudFoundryOperations = cloudFoundryOperations;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyConnectivity() {
        try {
            cloudFoundryClient.info()
                    .get(GetInfoRequest.builder().build())
                    .block();

            cloudFoundryOperations.organizations()
                    .list()
                    .blockFirst();

            log.info("Dropship connected to CF: {}, org: {}, space: {}",
                    properties.cfApiUrl(), properties.sandboxOrg(), properties.sandboxSpace());
        } catch (Exception e) {
            log.warn("Unable to connect to Cloud Foundry at {}: {}. Continuing without CF connectivity.",
                    properties.cfApiUrl(), e.getMessage());
        }
    }
}
