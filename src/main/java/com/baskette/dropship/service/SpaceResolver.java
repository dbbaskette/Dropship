package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import org.cloudfoundry.client.v3.organizations.ListOrganizationsRequest;
import org.cloudfoundry.client.v3.spaces.ListSpacesRequest;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SpaceResolver {

    private static final Logger log = LoggerFactory.getLogger(SpaceResolver.class);

    private final ReactorCloudFoundryClient cloudFoundryClient;
    private final DropshipProperties properties;
    private volatile String spaceGuid;

    public SpaceResolver(ReactorCloudFoundryClient cloudFoundryClient,
                         DropshipProperties properties) {
        this.cloudFoundryClient = cloudFoundryClient;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resolve() {
        String orgName = properties.sandboxOrg();
        String spaceName = properties.sandboxSpace();

        log.info("Resolving space GUID for org={}, space={}", orgName, spaceName);

        try {
            var orgResponse = cloudFoundryClient.organizationsV3()
                    .list(ListOrganizationsRequest.builder()
                            .name(orgName)
                            .build())
                    .block();

            if (orgResponse == null || orgResponse.getResources().isEmpty()) {
                throw new IllegalStateException(
                        "Organization not found: " + orgName);
            }

            String orgGuid = orgResponse.getResources().get(0).getId();

            var spaceResponse = cloudFoundryClient.spacesV3()
                    .list(ListSpacesRequest.builder()
                            .name(spaceName)
                            .organizationId(orgGuid)
                            .build())
                    .block();

            if (spaceResponse == null || spaceResponse.getResources().isEmpty()) {
                throw new IllegalStateException(
                        "Space not found: " + spaceName + " in organization: " + orgName);
            }

            spaceGuid = spaceResponse.getResources().get(0).getId();

            log.info("Resolved space GUID: {} for org={}, space={}", spaceGuid, orgName, spaceName);
        } catch (Exception e) {
            log.warn("Unable to resolve space GUID for org={}, space={}: {}. "
                            + "Space GUID was not resolved at startup. "
                            + "Calls to getSpaceGuid() will fail until the service is restarted.",
                    orgName, spaceName, e.getMessage());
        }
    }

    /**
     * Resolve the sandbox space GUID using a per-user CF client.
     * This method is reactive and non-blocking, suitable for use within
     * reactive chains in service methods.
     */
    public Mono<String> resolveSpace(ReactorCloudFoundryClient client) {
        String orgName = properties.sandboxOrg();
        String spaceName = properties.sandboxSpace();

        return client.organizationsV3()
                .list(ListOrganizationsRequest.builder()
                        .name(orgName)
                        .build())
                .flatMap(orgResponse -> {
                    if (orgResponse.getResources().isEmpty()) {
                        return Mono.error(new IllegalStateException(
                                "Organization not found: " + orgName));
                    }
                    String orgGuid = orgResponse.getResources().get(0).getId();
                    return client.spacesV3()
                            .list(ListSpacesRequest.builder()
                                    .name(spaceName)
                                    .organizationId(orgGuid)
                                    .build());
                })
                .flatMap(spaceResponse -> {
                    if (spaceResponse.getResources().isEmpty()) {
                        return Mono.<String>error(new IllegalStateException(
                                "Space not found: " + spaceName + " in organization: " + orgName));
                    }
                    return Mono.just(spaceResponse.getResources().get(0).getId());
                });
    }

    public String getSpaceGuid() {
        if (spaceGuid == null) {
            throw new IllegalStateException("Space GUID has not been resolved yet");
        }
        return spaceGuid;
    }
}
