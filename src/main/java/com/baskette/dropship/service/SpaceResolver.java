package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import org.cloudfoundry.client.v3.organizations.ListOrganizationsRequest;
import org.cloudfoundry.client.v3.spaces.ListSpacesRequest;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SpaceResolver {

    private final DropshipProperties properties;

    public SpaceResolver(DropshipProperties properties) {
        this.properties = properties;
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
}
