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
import org.cloudfoundry.client.v3.applications.UpdateApplicationEnvironmentVariablesRequest;
import org.cloudfoundry.client.v3.builds.BuildState;
import org.cloudfoundry.client.v3.builds.CreateBuildRequest;
import org.cloudfoundry.client.v3.builds.GetBuildRequest;
import org.cloudfoundry.client.v3.builds.GetBuildResponse;
import org.cloudfoundry.client.v3.droplets.GetDropletRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.PackageRelationships;
import org.cloudfoundry.client.v3.packages.PackageState;
import org.cloudfoundry.client.v3.packages.PackageType;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.logcache.v1.EnvelopeType;
import org.cloudfoundry.logcache.v1.LogCacheClient;
import org.cloudfoundry.logcache.v1.ReadRequest;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StagingService {

    private static final Logger log = LoggerFactory.getLogger(StagingService.class);

    private static final Duration STAGING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration INITIAL_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(10);
    private static final Duration LOG_RETRIEVAL_TIMEOUT = Duration.ofSeconds(15);
    static final String JAVA_BUILDPACK_GIT_URL = "https://github.com/cloudfoundry/java-buildpack.git";

    private final DropshipProperties properties;
    private final SpaceResolver spaceResolver;

    public StagingService(DropshipProperties properties,
                          SpaceResolver spaceResolver) {
        this.properties = properties;
        this.spaceResolver = spaceResolver;
    }

    /**
     * Stage a source bundle in Cloud Foundry.
     * Creates an ephemeral app, uploads the source, triggers a build,
     * and polls until staging completes or times out.
     */
    public Mono<StagingResult> stage(byte[] sourceBundle, String buildpack,
                                     Integer memoryMb, Integer diskMb,
                                     String org, String space,
                                     ReactorCloudFoundryClient client,
                                     LogCacheClient logCacheClient) {
        String appName = properties.appNamePrefix()
                + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        log.info("Starting staging: app={}, buildpack={}, memory={}MB, disk={}MB",
                appName,
                buildpack != null ? buildpack : "auto-detect",
                memoryMb != null ? memoryMb : properties.defaultStagingMemoryMb(),
                diskMb != null ? diskMb : properties.defaultStagingDiskMb());

        return createApp(appName, org, space, buildpack, client)
                .flatMap(appGuid -> createAndUploadPackage(appGuid, sourceBundle, client)
                        .flatMap(packageGuid -> pollPackageReady(packageGuid, client))
                        .flatMap(packageGuid -> buildAndPollWithFallback(packageGuid, buildpack, client))
                        .flatMap(buildResponse -> retrieveStagingLogs(appGuid, logCacheClient)
                                .map(logs -> toStagingResult(buildResponse, appGuid, appName, buildpack, startTime, logs)))
                        .flatMap(result -> result.success()
                                ? retrieveDetectedCommand(result.dropletGuid(), client)
                                        .map(result::withDetectedCommand)
                                : Mono.just(result))
                        .onErrorResume(error -> retrieveStagingLogs(appGuid, logCacheClient)
                                .map(logs -> toErrorResult(appGuid, appName, buildpack, startTime, error, logs))))
                .timeout(STAGING_TIMEOUT)
                .onErrorResume(error -> {
                    log.error("Staging failed for app={}: {}", appName, error.getMessage());
                    return Mono.just(new StagingResult(
                            null, null, appName, buildpack,
                            "(staging logs unavailable)",
                            System.currentTimeMillis() - startTime,
                            false, error.getMessage()));
                });
    }

    Mono<String> createApp(String appName, String org, String space,
                            String buildpack, ReactorCloudFoundryClient client) {
        return spaceResolver.resolveSpace(client, org, space)
                .flatMap(spaceGuid -> client.applicationsV3()
                        .create(CreateApplicationRequest.builder()
                                .name(appName)
                                .relationships(ApplicationRelationships.builder()
                                        .space(ToOneRelationship.builder()
                                                .data(Relationship.builder()
                                                        .id(spaceGuid)
                                                        .build())
                                                .build())
                                        .build())
                                .build()))
                .doOnSuccess(response ->
                        log.info("Created app: name={}, guid={}", appName, response.getId()))
                .map(response -> response.getId())
                .flatMap(appGuid -> {
                    if (isJavaBuildpack(buildpack)) {
                        log.info("Setting JBP_CONFIG_OPEN_JDK_JRE for Java 21 on app {}", appGuid);
                        return client.applicationsV3()
                                .updateEnvironmentVariables(UpdateApplicationEnvironmentVariablesRequest.builder()
                                        .applicationId(appGuid)
                                        .vars(Map.of("JBP_CONFIG_OPEN_JDK_JRE",
                                                "{ jre: { version: 21.+ } }"))
                                        .build())
                                .thenReturn(appGuid);
                    }
                    return Mono.just(appGuid);
                });
    }

    Mono<String> createAndUploadPackage(String appGuid, byte[] sourceBundle,
                                         ReactorCloudFoundryClient client) {
        return client.packages()
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
                            .flatMap(tempPath -> client.packages()
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

    Mono<String> pollPackageReady(String packageGuid, ReactorCloudFoundryClient client) {
        return Mono.defer(() -> client.packages()
                .get(GetPackageRequest.builder()
                        .packageId(packageGuid)
                        .build()))
                .flatMap(response -> {
                    PackageState state = response.getState();
                    log.debug("Package {} state: {}", packageGuid, state);

                    if (state == PackageState.READY) {
                        log.info("Package ready: guid={}", packageGuid);
                        return Mono.just(packageGuid);
                    }
                    if (state == PackageState.FAILED || state == PackageState.EXPIRED) {
                        return Mono.<String>error(new RuntimeException(
                                "Package " + packageGuid + " reached terminal state: " + state));
                    }
                    return Mono.<String>error(new PackageNotReadyException(
                            "Package " + packageGuid + " state: " + state));
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, INITIAL_POLL_INTERVAL)
                        .maxBackoff(MAX_POLL_INTERVAL)
                        .filter(PackageNotReadyException.class::isInstance));
    }

    Mono<GetBuildResponse> buildAndPollWithFallback(String packageGuid, String buildpack,
                                                       ReactorCloudFoundryClient client) {
        return createBuild(packageGuid, buildpack, client)
                .flatMap(buildGuid -> pollBuild(buildGuid, client))
                .flatMap(response -> {
                    if (response.getState() == BuildState.FAILED
                            && response.getError() != null
                            && response.getError().contains("BuildpackCompileFailed")
                            && isJavaBuildpack(buildpack)) {
                        log.info("Build with '{}' failed (BuildpackCompileFailed), retrying with git buildpack: {}",
                                buildpack, JAVA_BUILDPACK_GIT_URL);
                        return createBuild(packageGuid, JAVA_BUILDPACK_GIT_URL, client)
                                .flatMap(retryGuid -> pollBuild(retryGuid, client));
                    }
                    return Mono.just(response);
                });
    }

    private boolean isJavaBuildpack(String buildpack) {
        return buildpack != null && buildpack.contains("java_buildpack");
    }

    Mono<String> createBuild(String packageGuid, String buildpack,
                              ReactorCloudFoundryClient client) {
        return doCreateBuild(packageGuid, buildpack, client)
                .onErrorResume(error -> {
                    if (buildpack != null && !buildpack.endsWith("_offline")
                            && error.getMessage() != null
                            && error.getMessage().contains("must be an existing admin buildpack")) {
                        String offlineBuildpack = buildpack + "_offline";
                        log.info("Buildpack '{}' not found, retrying with '{}'",
                                buildpack, offlineBuildpack);
                        return doCreateBuild(packageGuid, offlineBuildpack, client);
                    }
                    return Mono.error(error);
                });
    }

    private Mono<String> doCreateBuild(String packageGuid, String buildpack,
                                        ReactorCloudFoundryClient client) {
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

        return client.builds()
                .create(builder.build())
                .doOnSuccess(response ->
                        log.info("Created build: guid={}, state={}",
                                response.getId(), response.getState()))
                .map(response -> response.getId());
    }

    Mono<GetBuildResponse> pollBuild(String buildGuid, ReactorCloudFoundryClient client) {
        return Mono.defer(() -> client.builds()
                .get(GetBuildRequest.builder()
                        .buildId(buildGuid)
                        .build()))
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

    private Mono<String> retrieveStagingLogs(String appGuid, LogCacheClient logCacheClient) {
        return logCacheClient.read(ReadRequest.builder()
                        .sourceId(appGuid)
                        .envelopeType(EnvelopeType.LOG)
                        .descending(false)
                        .limit(200)
                        .build())
                .map(response -> {
                    if (response.getEnvelopes() == null
                            || response.getEnvelopes().getBatch() == null) {
                        return "(no staging logs)";
                    }
                    return response.getEnvelopes().getBatch().stream()
                            .filter(env -> env.getLog() != null)
                            .map(env -> env.getLog().getPayloadAsText())
                            .collect(Collectors.joining("\n"));
                })
                .timeout(LOG_RETRIEVAL_TIMEOUT)
                .onErrorReturn("(staging logs unavailable)");
    }

    private Mono<String> retrieveDetectedCommand(String dropletGuid,
                                                    ReactorCloudFoundryClient client) {
        return client.droplets()
                .get(GetDropletRequest.builder().dropletId(dropletGuid).build())
                .map(response -> {
                    Map<String, String> processTypes = response.getProcessTypes();
                    if (processTypes == null) {
                        return "(no detected command)";
                    }
                    // Prefer the 'task' process type, fall back to 'web'
                    String cmd = processTypes.getOrDefault("task",
                            processTypes.getOrDefault("web", "(no detected command)"));
                    log.info("Detected start command from droplet: {}",
                            cmd.length() > 100 ? cmd.substring(0, 100) + "..." : cmd);
                    return cmd;
                })
                .timeout(Duration.ofSeconds(10))
                .onErrorReturn("(could not retrieve detected command)");
    }

    private StagingResult toStagingResult(GetBuildResponse buildResponse,
                                          String appGuid, String appName, String buildpack,
                                          long startTime, String stagingLogs) {
        long duration = System.currentTimeMillis() - startTime;

        if (buildResponse.getState() == BuildState.STAGED) {
            String dropletGuid = buildResponse.getDroplet() != null
                    ? buildResponse.getDroplet().getId() : null;
            log.info("Staging succeeded: appGuid={}, dropletGuid={}, duration={}ms",
                    appGuid, dropletGuid, duration);
            return new StagingResult(
                    dropletGuid, appGuid, appName, buildpack,
                    stagingLogs, duration, true, null);
        }

        String error = buildResponse.getError() != null
                ? buildResponse.getError() : "Unknown staging error";
        log.warn("Staging failed: appGuid={}, error={}", appGuid, error);
        return new StagingResult(null, appGuid, appName, buildpack, stagingLogs, duration, false, error);
    }

    private StagingResult toErrorResult(String appGuid, String appName, String buildpack,
                                        long startTime, Throwable error, String stagingLogs) {
        long duration = System.currentTimeMillis() - startTime;
        log.error("Staging error: appGuid={}, error={}", appGuid, error.getMessage());
        return new StagingResult(
                null, appGuid, appName, buildpack,
                stagingLogs, duration, false, error.getMessage());
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

    static class PackageNotReadyException extends RuntimeException {
        PackageNotReadyException(String message) {
            super(message);
        }
    }
}
