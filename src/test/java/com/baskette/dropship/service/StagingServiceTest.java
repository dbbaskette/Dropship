package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import com.baskette.dropship.model.StagingResult;
import org.cloudfoundry.client.v3.BuildpackData;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.LifecycleType;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.ApplicationState;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.CreateApplicationResponse;
import org.cloudfoundry.client.v3.applications.UpdateApplicationEnvironmentVariablesRequest;
import org.cloudfoundry.client.v3.applications.UpdateApplicationEnvironmentVariablesResponse;
import org.cloudfoundry.client.v3.builds.BuildState;
import org.cloudfoundry.client.v3.builds.Builds;
import org.cloudfoundry.client.v3.builds.CreateBuildRequest;
import org.cloudfoundry.client.v3.builds.CreateBuildResponse;
import org.cloudfoundry.client.v3.builds.CreatedBy;
import org.cloudfoundry.client.v3.builds.Droplet;
import org.cloudfoundry.client.v3.builds.GetBuildRequest;
import org.cloudfoundry.client.v3.builds.GetBuildResponse;
import org.cloudfoundry.client.v3.packages.BitsData;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageResponse;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageResponse;
import org.cloudfoundry.client.v3.packages.PackageState;
import org.cloudfoundry.client.v3.packages.PackageType;
import org.cloudfoundry.client.v3.packages.Packages;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.packages.UploadPackageResponse;
import org.cloudfoundry.logcache.v1.EnvelopeBatch;
import org.cloudfoundry.logcache.v1.Log;
import org.cloudfoundry.logcache.v1.LogCacheClient;
import org.cloudfoundry.logcache.v1.LogType;
import org.cloudfoundry.logcache.v1.ReadRequest;
import org.cloudfoundry.logcache.v1.ReadResponse;
import org.cloudfoundry.logcache.v1.Envelope;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Base64;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StagingServiceTest {

    private static final Lifecycle BUILDPACK_LIFECYCLE = Lifecycle.builder()
            .type(LifecycleType.BUILDPACK)
            .data(BuildpackData.builder().build())
            .build();

    private static final CreatedBy TEST_CREATED_BY = CreatedBy.builder()
            .id("user-guid")
            .name("test-user")
            .email("test@example.com")
            .build();

    @Mock
    private ReactorCloudFoundryClient cfClient;

    @Mock
    private SpaceResolver spaceResolver;

    @Mock
    private LogCacheClient logCacheClient;

    @Mock
    private ApplicationsV3 applicationsV3;

    @Mock
    private Packages packages;

    @Mock
    private Builds builds;

    @Mock
    private org.cloudfoundry.client.v3.droplets.Droplets droplets;

    @Captor
    private ArgumentCaptor<CreateApplicationRequest> appRequestCaptor;

    @Captor
    private ArgumentCaptor<CreatePackageRequest> packageRequestCaptor;

    @Captor
    private ArgumentCaptor<CreateBuildRequest> buildRequestCaptor;

    private DropshipProperties properties;
    private StagingService stagingService;

    @BeforeEach
    void setUp() {
        properties = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");
        stagingService = new StagingService(properties, spaceResolver);
    }

    /** Stub package get to return READY state immediately. */
    private void stubPackageReady(String packageGuid) {
        when(packages.get(any(GetPackageRequest.class)))
                .thenReturn(Mono.just(GetPackageResponse.builder()
                        .id(packageGuid)
                        .type(PackageType.BITS)
                        .state(PackageState.READY)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
    }

    /** Stub log retrieval via Log Cache to return the given messages (or empty). */
    private void stubStagingLogs(String... messages) {
        EnvelopeBatch.Builder batchBuilder = EnvelopeBatch.builder();
        for (int i = 0; i < messages.length; i++) {
            batchBuilder.batch(Envelope.builder()
                    .timestamp((long) (i + 1) * 1_000_000_000L)
                    .sourceId("app-guid")
                    .log(Log.builder()
                            .payload(Base64.getEncoder().encodeToString(messages[i].getBytes()))
                            .type(LogType.OUT)
                            .build())
                    .build());
        }
        when(logCacheClient.read(any(ReadRequest.class)))
                .thenReturn(Mono.just(ReadResponse.builder()
                        .envelopes(batchBuilder.build())
                        .build()));
    }

    private void stubDropletCommand(String command) {
        when(cfClient.droplets()).thenReturn(droplets);
        when(droplets.get(any(org.cloudfoundry.client.v3.droplets.GetDropletRequest.class)))
                .thenReturn(Mono.just(org.cloudfoundry.client.v3.droplets.GetDropletResponse.builder()
                        .id("droplet-guid-201")
                        .state(org.cloudfoundry.client.v3.droplets.DropletState.STAGED)
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .executionMetadata("")
                        .processTypes(java.util.Map.of("task", command, "web", command))
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
    }

    private void stubEnvVarUpdate() {
        when(applicationsV3.updateEnvironmentVariables(any(UpdateApplicationEnvironmentVariablesRequest.class)))
                .thenReturn(Mono.just(UpdateApplicationEnvironmentVariablesResponse.builder().build()));
    }

    @Test
    void createAppSetsNameAndSpaceRelationship() {
        when(spaceResolver.resolveSpace(cfClient, "test-org", "test-space")).thenReturn(Mono.just("space-guid-123"));
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.create(any(CreateApplicationRequest.class)))
                .thenReturn(Mono.just(CreateApplicationResponse.builder()
                        .id("app-guid-456")
                        .name("dropship-abc12345")
                        .state(ApplicationState.STOPPED)
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .updatedAt("2024-01-01T00:00:00Z")
                        .build()));

        StepVerifier.create(stagingService.createApp("dropship-abc12345", "test-org", "test-space", null, cfClient))
                .expectNext("app-guid-456")
                .verifyComplete();

        verify(applicationsV3).create(appRequestCaptor.capture());
        CreateApplicationRequest request = appRequestCaptor.getValue();
        assertThat(request.getName()).isEqualTo("dropship-abc12345");
        assertThat(request.getRelationships().getSpace().getData().getId())
                .isEqualTo("space-guid-123");
    }

    @Test
    void createAndUploadPackageSetsTypeAndAppRelationship() {
        when(cfClient.packages()).thenReturn(packages);
        when(packages.create(any(CreatePackageRequest.class)))
                .thenReturn(Mono.just(CreatePackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.AWAITING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(packages.upload(any(UploadPackageRequest.class)))
                .thenReturn(Mono.just(UploadPackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.PROCESSING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        byte[] sourceBundle = "test source content".getBytes();

        StepVerifier.create(stagingService.createAndUploadPackage("app-guid-456", sourceBundle, cfClient))
                .expectNext("package-guid-789")
                .verifyComplete();

        verify(packages).create(packageRequestCaptor.capture());
        CreatePackageRequest request = packageRequestCaptor.getValue();
        assertThat(request.getType().toString()).isEqualTo("bits");
        assertThat(request.getRelationships().getApplication().getData().getId())
                .isEqualTo("app-guid-456");
    }

    @Test
    void createBuildWithBuildpackSetsLifecycle() {
        when(cfClient.builds()).thenReturn(builds);
        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        StepVerifier.create(stagingService.createBuild("package-guid-789", "java_buildpack", cfClient))
                .expectNext("build-guid-101")
                .verifyComplete();

        verify(builds).create(buildRequestCaptor.capture());
        CreateBuildRequest request = buildRequestCaptor.getValue();
        assertThat(request.getPackage().getId()).isEqualTo("package-guid-789");
        assertThat(request.getLifecycle()).isNotNull();
        assertThat(request.getLifecycle().getType().getValue()).isEqualTo("buildpack");
    }

    @Test
    void createBuildFallsBackToOfflineBuildpack() {
        when(cfClient.builds()).thenReturn(builds);

        // First call with java_buildpack fails
        when(builds.create(argThat(req ->
                req != null && req.getLifecycle() != null
                        && req.getLifecycle().getData() instanceof BuildpackData
                        && ((BuildpackData) req.getLifecycle().getData()).getBuildpacks().contains("java_buildpack"))))
                .thenReturn(Mono.error(new RuntimeException(
                        "CF-UnprocessableEntity(10008): Buildpack \"java_buildpack\" must be an existing admin buildpack or a valid git URI")));

        // Second call with java_buildpack_offline succeeds
        when(builds.create(argThat(req ->
                req != null && req.getLifecycle() != null
                        && req.getLifecycle().getData() instanceof BuildpackData
                        && ((BuildpackData) req.getLifecycle().getData()).getBuildpacks().contains("java_buildpack_offline"))))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-fallback")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        StepVerifier.create(stagingService.createBuild("package-guid-789", "java_buildpack", cfClient))
                .expectNext("build-guid-fallback")
                .verifyComplete();

        verify(builds, times(2)).create(any(CreateBuildRequest.class));
    }

    @Test
    void createBuildDoesNotFallbackWhenAlreadyOffline() {
        when(cfClient.builds()).thenReturn(builds);
        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.error(new RuntimeException(
                        "CF-UnprocessableEntity(10008): Buildpack \"java_buildpack_offline\" must be an existing admin buildpack or a valid git URI")));

        StepVerifier.create(stagingService.createBuild("package-guid-789", "java_buildpack_offline", cfClient))
                .expectError(RuntimeException.class)
                .verify();

        verify(builds, times(1)).create(any(CreateBuildRequest.class));
    }

    @Test
    void buildAndPollFallsBackToGitBuildpackOnCompileFailure() {
        when(cfClient.builds()).thenReturn(builds);

        // First createBuild succeeds with java_buildpack_offline (via createBuild fallback)
        when(builds.create(argThat(req ->
                req != null && req.getLifecycle() != null
                        && req.getLifecycle().getData() instanceof BuildpackData
                        && ((BuildpackData) req.getLifecycle().getData()).getBuildpacks().contains("java_buildpack"))))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-first")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("pkg-1").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        // Retry with git URL succeeds
        when(builds.create(argThat(req ->
                req != null && req.getLifecycle() != null
                        && req.getLifecycle().getData() instanceof BuildpackData
                        && ((BuildpackData) req.getLifecycle().getData()).getBuildpacks()
                                .contains(StagingService.JAVA_BUILDPACK_GIT_URL))))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-git")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("pkg-1").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        // First build fails with BuildpackCompileFailed, second succeeds
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-first")
                        .state(BuildState.FAILED)
                        .error("BuildpackCompileFailed - App staging failed in the buildpack compile phase")
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("pkg-1").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-git")
                        .state(BuildState.STAGED)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("pkg-1").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .droplet(Droplet.builder().id("droplet-git").build())
                        .build()));

        StepVerifier.create(stagingService.buildAndPollWithFallback("pkg-1", "java_buildpack", cfClient))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(BuildState.STAGED);
                    assertThat(response.getDroplet().getId()).isEqualTo("droplet-git");
                })
                .verifyComplete();

        verify(builds, times(2)).create(any(CreateBuildRequest.class));
    }

    @Test
    void buildAndPollDoesNotFallbackForNonJavaBuildpack() {
        when(cfClient.builds()).thenReturn(builds);

        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-node")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("pkg-1").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-node")
                        .state(BuildState.FAILED)
                        .error("BuildpackCompileFailed - App staging failed")
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("pkg-1").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        StepVerifier.create(stagingService.buildAndPollWithFallback("pkg-1", "nodejs_buildpack", cfClient))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(BuildState.FAILED);
                })
                .verifyComplete();

        // Only one create call — no fallback for non-java
        verify(builds, times(1)).create(any(CreateBuildRequest.class));
    }

    @Test
    void createBuildWithoutBuildpackOmitsLifecycle() {
        when(cfClient.builds()).thenReturn(builds);
        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-102")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        StepVerifier.create(stagingService.createBuild("package-guid-789", null, cfClient))
                .expectNext("build-guid-102")
                .verifyComplete();

        verify(builds).create(buildRequestCaptor.capture());
        assertThat(buildRequestCaptor.getValue().getLifecycle()).isNull();
    }

    @Test
    void pollBuildReturnsWhenStaged() {
        when(cfClient.builds()).thenReturn(builds);
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGED)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .droplet(Droplet.builder()
                                .id("droplet-guid-201")
                                .build())
                        .build()));

        StepVerifier.create(stagingService.pollBuild("build-guid-101", cfClient))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(BuildState.STAGED);
                    assertThat(response.getDroplet().getId()).isEqualTo("droplet-guid-201");
                })
                .verifyComplete();
    }

    @Test
    void pollBuildReturnsWhenFailed() {
        when(cfClient.builds()).thenReturn(builds);
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.FAILED)
                        .error("Buildpack compilation failed")
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        StepVerifier.create(stagingService.pollBuild("build-guid-101", cfClient))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(BuildState.FAILED);
                    assertThat(response.getError()).isEqualTo("Buildpack compilation failed");
                })
                .verifyComplete();
    }

    @Test
    void pollBuildRetriesWhileStaging() {
        when(cfClient.builds()).thenReturn(builds);
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGED)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .droplet(Droplet.builder()
                                .id("droplet-guid-201")
                                .build())
                        .build()));

        StepVerifier.create(stagingService.pollBuild("build-guid-101", cfClient))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(BuildState.STAGED);
                    assertThat(response.getDroplet().getId()).isEqualTo("droplet-guid-201");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void stageReturnsSuccessResult() {
        when(spaceResolver.resolveSpace(cfClient, "test-org", "test-space")).thenReturn(Mono.just("space-guid-123"));
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        stubEnvVarUpdate();
        when(applicationsV3.create(any(CreateApplicationRequest.class)))
                .thenReturn(Mono.just(CreateApplicationResponse.builder()
                        .id("app-guid-456")
                        .name("dropship-abc12345")
                        .state(ApplicationState.STOPPED)
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .updatedAt("2024-01-01T00:00:00Z")
                        .build()));

        when(cfClient.packages()).thenReturn(packages);
        when(packages.create(any(CreatePackageRequest.class)))
                .thenReturn(Mono.just(CreatePackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.AWAITING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(packages.upload(any(UploadPackageRequest.class)))
                .thenReturn(Mono.just(UploadPackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.PROCESSING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        stubPackageReady("package-guid-789");

        when(cfClient.builds()).thenReturn(builds);
        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGED)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .droplet(Droplet.builder()
                                .id("droplet-guid-201")
                                .build())
                        .build()));

        stubStagingLogs("-----> Downloading JDK", "-----> Build succeeded");
        stubDropletCommand("java -cp . org.springframework.boot.loader.launch.JarLauncher");

        StepVerifier.create(stagingService.stage(
                        "test source".getBytes(), "java_buildpack", 512, 1024,
                        "test-org", "test-space", cfClient, logCacheClient))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.dropletGuid()).isEqualTo("droplet-guid-201");
                    assertThat(result.appGuid()).isEqualTo("app-guid-456");
                    assertThat(result.appName()).isNotNull().startsWith("dropship-");
                    assertThat(result.buildpack()).isEqualTo("java_buildpack");
                    assertThat(result.stagingLogs()).contains("Downloading JDK");
                    assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
                    assertThat(result.errorMessage()).isNull();
                    assertThat(result.detectedCommand()).contains("JarLauncher");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void stageReturnsFailureResultOnBuildFailure() {
        when(spaceResolver.resolveSpace(cfClient, "test-org", "test-space")).thenReturn(Mono.just("space-guid-123"));
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.create(any(CreateApplicationRequest.class)))
                .thenReturn(Mono.just(CreateApplicationResponse.builder()
                        .id("app-guid-456")
                        .name("dropship-abc12345")
                        .state(ApplicationState.STOPPED)
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .updatedAt("2024-01-01T00:00:00Z")
                        .build()));

        when(cfClient.packages()).thenReturn(packages);
        when(packages.create(any(CreatePackageRequest.class)))
                .thenReturn(Mono.just(CreatePackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.AWAITING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(packages.upload(any(UploadPackageRequest.class)))
                .thenReturn(Mono.just(UploadPackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.PROCESSING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        stubPackageReady("package-guid-789");

        when(cfClient.builds()).thenReturn(builds);
        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.FAILED)
                        .error("Buildpack compilation failed")
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        stubStagingLogs("-----> Compilation error: missing dependency");

        StepVerifier.create(stagingService.stage(
                        "test source".getBytes(), null, null, null,
                        "test-org", "test-space", cfClient, logCacheClient))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.dropletGuid()).isNull();
                    assertThat(result.appGuid()).isEqualTo("app-guid-456");
                    assertThat(result.appName()).isNotNull().startsWith("dropship-");
                    assertThat(result.errorMessage()).isEqualTo("Buildpack compilation failed");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void stageReturnsErrorResultOnAppCreationFailure() {
        when(spaceResolver.resolveSpace(cfClient, "test-org", "test-space")).thenReturn(Mono.just("space-guid-123"));
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.create(any(CreateApplicationRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("CF API error")));

        StepVerifier.create(stagingService.stage(
                        "test source".getBytes(), "java_buildpack", 512, 1024,
                        "test-org", "test-space", cfClient, logCacheClient))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.errorMessage()).contains("CF API error");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void stageUsesAutoDetectWhenBuildpackIsNull() {
        when(spaceResolver.resolveSpace(cfClient, "test-org", "test-space")).thenReturn(Mono.just("space-guid-123"));
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.create(any(CreateApplicationRequest.class)))
                .thenReturn(Mono.just(CreateApplicationResponse.builder()
                        .id("app-guid-456")
                        .name("dropship-abc12345")
                        .state(ApplicationState.STOPPED)
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .updatedAt("2024-01-01T00:00:00Z")
                        .build()));

        when(cfClient.packages()).thenReturn(packages);
        when(packages.create(any(CreatePackageRequest.class)))
                .thenReturn(Mono.just(CreatePackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.AWAITING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(packages.upload(any(UploadPackageRequest.class)))
                .thenReturn(Mono.just(UploadPackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.PROCESSING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        stubPackageReady("package-guid-789");

        when(cfClient.builds()).thenReturn(builds);
        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGED)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .droplet(Droplet.builder()
                                .id("droplet-guid-201")
                                .build())
                        .build()));

        stubStagingLogs();
        stubDropletCommand("./start.sh");

        StepVerifier.create(stagingService.stage(
                        "test source".getBytes(), null, null, null,
                        "test-org", "test-space", cfClient, logCacheClient))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.buildpack()).isNull();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        verify(builds).create(buildRequestCaptor.capture());
        assertThat(buildRequestCaptor.getValue().getLifecycle()).isNull();
    }

    @Test
    void stageReturnsTimeoutErrorWhenBuildNeverCompletes() {
        when(spaceResolver.resolveSpace(cfClient, "test-org", "test-space")).thenReturn(Mono.just("space-guid-123"));
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        stubEnvVarUpdate();
        when(applicationsV3.create(any(CreateApplicationRequest.class)))
                .thenReturn(Mono.just(CreateApplicationResponse.builder()
                        .id("app-guid-456")
                        .name("dropship-abc12345")
                        .state(ApplicationState.STOPPED)
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .updatedAt("2024-01-01T00:00:00Z")
                        .build()));

        when(cfClient.packages()).thenReturn(packages);
        when(packages.create(any(CreatePackageRequest.class)))
                .thenReturn(Mono.just(CreatePackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.AWAITING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(packages.upload(any(UploadPackageRequest.class)))
                .thenReturn(Mono.just(UploadPackageResponse.builder()
                        .id("package-guid-789")
                        .type(PackageType.BITS)
                        .state(PackageState.PROCESSING_UPLOAD)
                        .data(BitsData.builder().build())
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        stubPackageReady("package-guid-789");

        when(cfClient.builds()).thenReturn(builds);
        when(builds.create(any(CreateBuildRequest.class)))
                .thenReturn(Mono.just(CreateBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));
        when(builds.get(any(GetBuildRequest.class)))
                .thenReturn(Mono.just(GetBuildResponse.builder()
                        .id("build-guid-101")
                        .state(BuildState.STAGING)
                        .createdBy(TEST_CREATED_BY)
                        .inputPackage(Relationship.builder().id("package-guid-789").build())
                        .lifecycle(BUILDPACK_LIFECYCLE)
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()));

        StepVerifier.withVirtualTime(() -> stagingService.stage(
                        "test source".getBytes(), "java_buildpack", 512, 1024,
                        "test-org", "test-space", cfClient, logCacheClient))
                .thenAwait(Duration.ofMinutes(6))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.errorMessage()).containsAnyOf("timed out", "Timeout", "did not observe", "within");
                })
                .verifyComplete();
    }
}
