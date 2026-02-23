package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import com.baskette.dropship.model.StagingResult;
import org.cloudfoundry.client.v3.BuildpackData;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.LifecycleType;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.ToOneRelationship;
import org.cloudfoundry.client.v3.applications.ApplicationRelationships;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.builds.BuildState;
import org.cloudfoundry.client.v3.builds.CreateBuildRequest;
import org.cloudfoundry.client.v3.builds.GetBuildRequest;
import org.cloudfoundry.client.v3.builds.GetBuildResponse;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.PackageRelationships;
import org.cloudfoundry.client.v3.packages.PackageType;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

@Service
public class StagingService {

    private static final Logger log = LoggerFactory.getLogger(StagingService.class);

    private static final Duration STAGING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration INITIAL_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(10);

    private final ReactorCloudFoundryClient cfClient;
    private final DropshipProperties properties;
    private final SpaceResolver spaceResolver;

    public StagingService(ReactorCloudFoundryClient cfClient,
                          DropshipProperties properties,
                          SpaceResolver spaceResolver) {
        this.cfClient = cfClient;
        this.properties = properties;
        this.spaceResolver = spaceResolver;
    }

    /**
     * Stage a source bundle in Cloud Foundry.
     * Creates an ephemeral app, uploads the source, triggers a build,
     * and polls until staging completes or times out.
     */
    public Mono<StagingResult> stage(byte[] sourceBundle, String buildpack,
                                     Integer memoryMb, Integer diskMb) {
        String appName = properties.appNamePrefix()
                + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        log.info("Starting staging: app={}, buildpack={}, memory={}MB, disk={}MB",
                appName,
                buildpack != null ? buildpack : "auto-detect",
                memoryMb != null ? memoryMb : properties.defaultStagingMemoryMb(),
                diskMb != null ? diskMb : properties.defaultStagingDiskMb());

        return createApp(appName)
                .flatMap(appGuid -> createAndUploadPackage(appGuid, sourceBundle)
                        .flatMap(packageGuid -> createBuild(packageGuid, buildpack))
                        .flatMap(this::pollBuild)
                        .map(buildResponse -> toStagingResult(
                                buildResponse, appGuid, buildpack, startTime))
                        .onErrorResume(error -> Mono.just(
                                toErrorResult(appGuid, buildpack, startTime, error))))
                .timeout(STAGING_TIMEOUT)
                .onErrorResume(error -> {
                    log.error("Staging failed for app={}: {}", appName, error.getMessage());
                    return Mono.just(new StagingResult(
                            null, null, buildpack,
                            "Staging timed out or failed: " + error.getMessage(),
                            System.currentTimeMillis() - startTime,
                            false, error.getMessage()));
                });
    }

    Mono<String> createApp(String appName) {
        return cfClient.applicationsV3()
                .create(CreateApplicationRequest.builder()
                        .name(appName)
                        .relationships(ApplicationRelationships.builder()
                                .space(ToOneRelationship.builder()
                                        .data(Relationship.builder()
                                                .id(spaceResolver.getSpaceGuid())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .doOnSuccess(response ->
                        log.info("Created app: name={}, guid={}", appName, response.getId()))
                .map(response -> response.getId());
    }

    Mono<String> createAndUploadPackage(String appGuid, byte[] sourceBundle) {
        return cfClient.packages()
                .create(CreatePackageRequest.builder()
                        .type(PackageType.BITS)
                        .relationships(PackageRelationships.builder()
                                .application(ToOneRelationship.builder()
                                        .data(Relationship.builder()
                                                .id(appGuid)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .doOnSuccess(response ->
                        log.info("Created package: guid={}", response.getId()))
                .flatMap(createResponse -> {
                    String packageGuid = createResponse.getId();
                    return writeTempFile(sourceBundle)
                            .flatMap(tempPath -> cfClient.packages()
                                    .upload(UploadPackageRequest.builder()
                                            .packageId(packageGuid)
                                            .bits(tempPath)
                                            .build())
                                    .doOnSuccess(r ->
                                            log.info("Uploaded package: guid={}", packageGuid))
                                    .doFinally(signal -> deleteTempFile(tempPath)))
                            .thenReturn(packageGuid);
                });
    }

    Mono<String> createBuild(String packageGuid, String buildpack) {
        CreateBuildRequest.Builder builder = CreateBuildRequest.builder()
                .getPackage(Relationship.builder()
                        .id(packageGuid)
                        .build());

        if (buildpack != null) {
            builder.lifecycle(Lifecycle.builder()
                    .type(LifecycleType.BUILDPACK)
                    .data(BuildpackData.builder()
                            .buildpack(buildpack)
                            .build())
                    .build());
        }

        return cfClient.builds()
                .create(builder.build())
                .doOnSuccess(response ->
                        log.info("Created build: guid={}, state={}",
                                response.getId(), response.getState()))
                .map(response -> response.getId());
    }

    Mono<GetBuildResponse> pollBuild(String buildGuid) {
        return cfClient.builds()
                .get(GetBuildRequest.builder()
                        .buildId(buildGuid)
                        .build())
                .flatMap(response -> {
                    BuildState state = response.getState();
                    log.debug("Build {} state: {}", buildGuid, state);

                    if (state == BuildState.STAGED || state == BuildState.FAILED) {
                        return Mono.just(response);
                    }
                    return Mono.<GetBuildResponse>error(
                            new StagingInProgressException(
                                    "Build " + buildGuid + " still staging"));
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, INITIAL_POLL_INTERVAL)
                        .maxBackoff(MAX_POLL_INTERVAL)
                        .filter(StagingInProgressException.class::isInstance));
    }

    private StagingResult toStagingResult(GetBuildResponse buildResponse,
                                          String appGuid, String buildpack,
                                          long startTime) {
        long duration = System.currentTimeMillis() - startTime;

        if (buildResponse.getState() == BuildState.STAGED) {
            String dropletGuid = buildResponse.getDroplet() != null
                    ? buildResponse.getDroplet().getId() : null;
            log.info("Staging succeeded: appGuid={}, dropletGuid={}, duration={}ms",
                    appGuid, dropletGuid, duration);
            return new StagingResult(
                    dropletGuid, appGuid, buildpack,
                    "Staging completed successfully", duration, true, null);
        }

        String error = buildResponse.getError() != null
                ? buildResponse.getError() : "Unknown staging error";
        log.warn("Staging failed: appGuid={}, error={}", appGuid, error);
        return new StagingResult(null, appGuid, buildpack, error, duration, false, error);
    }

    private StagingResult toErrorResult(String appGuid, String buildpack,
                                        long startTime, Throwable error) {
        long duration = System.currentTimeMillis() - startTime;
        log.error("Staging error: appGuid={}, error={}", appGuid, error.getMessage());
        return new StagingResult(
                null, appGuid, buildpack,
                error.getMessage(), duration, false, error.getMessage());
    }

    private Mono<Path> writeTempFile(byte[] data) {
        return Mono.fromCallable(() -> {
            Path tempFile = Files.createTempFile("dropship-upload-", ".zip");
            Files.write(tempFile, data);
            return tempFile;
        });
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", path, e);
        }
    }

    static class StagingInProgressException extends RuntimeException {
        StagingInProgressException(String message) {
            super(message);
        }
    }
}
