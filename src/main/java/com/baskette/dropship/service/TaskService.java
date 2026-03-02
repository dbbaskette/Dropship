package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import com.baskette.dropship.model.TaskResult;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.TaskState;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
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

    static final Duration INITIAL_POLL_INTERVAL = Duration.ofMillis(500);
    static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(10);

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

    public Mono<TaskResult> runTask(String appGuid, String dropletGuid, String command,
                                     Integer memoryMb, Integer timeoutSeconds,
                                     Map<String, String> environment) {
        long startTime = System.currentTimeMillis();

        log.info("Running task: appGuid={}, dropletGuid={}, command={}",
                appGuid, dropletGuid, command);

        return setCurrentDroplet(appGuid, dropletGuid)
                .then(createTask(appGuid, command, memoryMb, null, timeoutSeconds, environment))
                .flatMap(this::pollTask)
                .map(taskResponse -> toTaskResult(taskResponse, appGuid, command, startTime))
                .timeout(Duration.ofSeconds(properties.maxTaskTimeoutSeconds()))
                .onErrorResume(error -> {
                    log.error("Task failed for appGuid={}: {}", appGuid, error.getMessage());
                    long duration = System.currentTimeMillis() - startTime;
                    return Mono.just(new TaskResult(
                            null, appGuid, 1, TaskResult.State.FAILED,
                            duration, 0, command));
                });
    }

    Mono<GetTaskResponse> pollTask(String taskGuid) {
        return Mono.defer(() -> cfClient.tasks()
                .get(GetTaskRequest.builder()
                        .taskId(taskGuid)
                        .build()))
                .flatMap(response -> {
                    TaskState state = response.getState();
                    log.debug("Task {} state: {}", taskGuid, state);

                    if (state == TaskState.SUCCEEDED || state == TaskState.FAILED) {
                        return Mono.just(response);
                    }
                    return Mono.<GetTaskResponse>error(
                            new TaskInProgressException(
                                    "Task " + taskGuid + " still running"));
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, INITIAL_POLL_INTERVAL)
                        .maxBackoff(MAX_POLL_INTERVAL)
                        .filter(TaskInProgressException.class::isInstance));
    }

    private TaskResult toTaskResult(GetTaskResponse response, String appGuid,
                                     String command, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        TaskResult.State state = response.getState() == TaskState.SUCCEEDED
                ? TaskResult.State.SUCCEEDED : TaskResult.State.FAILED;
        int memoryMb = response.getMemoryInMb() != null ? response.getMemoryInMb() : 0;

        log.info("Task completed: guid={}, state={}, duration={}ms",
                response.getId(), state, duration);

        return new TaskResult(
                response.getId(), appGuid,
                state == TaskResult.State.SUCCEEDED ? 0 : 1,
                state, duration, memoryMb, command);
    }

    static class TaskInProgressException extends RuntimeException {
        TaskInProgressException(String message) {
            super(message);
        }
    }
}
