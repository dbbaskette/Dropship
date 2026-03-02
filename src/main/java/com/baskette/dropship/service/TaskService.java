package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import com.baskette.dropship.model.TaskResult;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.TaskState;
import org.cloudfoundry.client.CloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

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

    Mono<String> createTask(String appGuid, String command,
                             Integer memoryMb, Integer diskMb,
                             Integer timeoutSeconds,
                             Map<String, String> environment) {
        int effectiveMemory = Math.min(
                memoryMb != null ? memoryMb : properties.defaultTaskMemoryMb(),
                properties.maxTaskMemoryMb());
        int effectiveDisk = Math.min(
                diskMb != null ? diskMb : properties.defaultStagingDiskMb(),
                properties.maxTaskDiskMb());
        int effectiveTimeout = Math.min(
                timeoutSeconds != null ? timeoutSeconds : 300,
                properties.maxTaskTimeoutSeconds());

        log.info("Creating task: appGuid={}, command={}, memory={}MB, disk={}MB, timeout={}s",
                appGuid, command, effectiveMemory, effectiveDisk, effectiveTimeout);

        return cfClient.tasks()
                .create(CreateTaskRequest.builder()
                        .applicationId(appGuid)
                        .command(command)
                        .memoryInMb(effectiveMemory)
                        .diskInMb(effectiveDisk)
                        .build())
                .doOnSuccess(response ->
                        log.info("Created task: guid={}, state={}",
                                response.getId(), response.getState()))
                .map(response -> response.getId());
    }
}
