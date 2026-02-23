package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import org.cloudfoundry.client.v3.organizations.ListOrganizationsRequest;
import org.cloudfoundry.client.v3.organizations.OrganizationResource;
import org.cloudfoundry.client.v3.spaces.ListSpacesRequest;
import org.cloudfoundry.client.v3.spaces.SpaceResource;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

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
            List<OrganizationResource> orgs = cloudFoundryClient.organizationsV3()
                    .list(ListOrganizationsRequest.builder()
                            .name(orgName)
                            .build())
                    .block()
                    .getResources();

            if (orgs.isEmpty()) {
                throw new IllegalStateException(
                        "Organization not found: " + orgName);
            }

            String orgGuid = orgs.get(0).getId();

            List<SpaceResource> spaces = cloudFoundryClient.spacesV3()
                    .list(ListSpacesRequest.builder()
                            .name(spaceName)
                            .organizationId(orgGuid)
                            .build())
                    .block()
                    .getResources();

            if (spaces.isEmpty()) {
                throw new IllegalStateException(
                        "Space not found: " + spaceName + " in organization: " + orgName);
            }

            spaceGuid = spaces.get(0).getId();

            log.info("Resolved space GUID: {} for org={}, space={}", spaceGuid, orgName, spaceName);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unable to resolve space GUID for org={}, space={}: {}. "
                            + "Resolution will be retried on first access.",
                    orgName, spaceName, e.getMessage());
        }
    }

    public String getSpaceGuid() {
        if (spaceGuid == null) {
            throw new IllegalStateException("Space GUID has not been resolved yet");
        }
        return spaceGuid;
    }
}
