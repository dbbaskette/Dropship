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
import org.cloudfoundry.client.v3.packages.PackageState;
import org.cloudfoundry.client.v3.packages.PackageType;
import org.cloudfoundry.client.v3.packages.Packages;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.packages.UploadPackageResponse;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationLog;
import org.cloudfoundry.operations.applications.ApplicationLogType;
import org.cloudfoundry.operations.applications.ApplicationLogsRequest;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private DefaultCloudFoundryOperations cfOperations;

    @Mock
    private Applications applicationsOps;

    @Mock
    private ApplicationsV3 applicationsV3;

    @Mock
    private Packages packages;

    @Mock
    private Builds builds;

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
        stagingService = new StagingService(cfClient, properties, spaceResolver, cfOperations);
    }

    /** Stub log retrieval to return the given messages (or empty flux). */
    private void stubStagingLogs(String... messages) {
        when(cfOperations.applications()).thenReturn(applicationsOps);
        if (messages.length == 0) {
            when(applicationsOps.logs(any(ApplicationLogsRequest.class))).thenReturn(Flux.empty());
        } else {
            ApplicationLog[] logs = new ApplicationLog[messages.length];
            for (int i = 0; i < messages.length; i++) {
                logs[i] = ApplicationLog.builder()
                        .message(messages[i])
                        .logType(ApplicationLogType.OUT)
                        .timestamp((long) (i + 1) * 1_000_000_000L)
                        .sourceType("STG")
                        .sourceId("0")
                        .instanceId("0")
                        .build();
            }
            when(applicationsOps.logs(any(ApplicationLogsRequest.class))).thenReturn(Flux.just(logs));
        }
    }

    @Test
    void createAppSetsNameAndSpaceRelationship() {
        when(spaceResolver.getSpaceGuid()).thenReturn("space-guid-123");
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

        StepVerifier.create(stagingService.createApp("dropship-abc12345"))
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

        StepVerifier.create(stagingService.createAndUploadPackage("app-guid-456", sourceBundle))
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

        StepVerifier.create(stagingService.createBuild("package-guid-789", "java_buildpack"))
                .expectNext("build-guid-101")
                .verifyComplete();

        verify(builds).create(buildRequestCaptor.capture());
        CreateBuildRequest request = buildRequestCaptor.getValue();
        assertThat(request.getPackage().getId()).isEqualTo("package-guid-789");
        assertThat(request.getLifecycle()).isNotNull();
        assertThat(request.getLifecycle().getType().getValue()).isEqualTo("buildpack");
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

        StepVerifier.create(stagingService.createBuild("package-guid-789", null))
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

        StepVerifier.create(stagingService.pollBuild("build-guid-101"))
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

        StepVerifier.create(stagingService.pollBuild("build-guid-101"))
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

        StepVerifier.create(stagingService.pollBuild("build-guid-101"))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(BuildState.STAGED);
                    assertThat(response.getDroplet().getId()).isEqualTo("droplet-guid-201");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void stageReturnsSuccessResult() {
        when(spaceResolver.getSpaceGuid()).thenReturn("space-guid-123");
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

        StepVerifier.create(stagingService.stage(
                        "test source".getBytes(), "java_buildpack", 512, 1024))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.dropletGuid()).isEqualTo("droplet-guid-201");
                    assertThat(result.appGuid()).isEqualTo("app-guid-456");
                    assertThat(result.appName()).isNotNull().startsWith("dropship-");
                    assertThat(result.buildpack()).isEqualTo("java_buildpack");
                    assertThat(result.stagingLogs()).contains("Downloading JDK");
                    assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
                    assertThat(result.errorMessage()).isNull();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void stageReturnsFailureResultOnBuildFailure() {
        when(spaceResolver.getSpaceGuid()).thenReturn("space-guid-123");
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
                        "test source".getBytes(), null, null, null))
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
        when(spaceResolver.getSpaceGuid()).thenReturn("space-guid-123");
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.create(any(CreateApplicationRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("CF API error")));

        StepVerifier.create(stagingService.stage(
                        "test source".getBytes(), "java_buildpack", 512, 1024))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.errorMessage()).contains("CF API error");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void stageUsesAutoDetectWhenBuildpackIsNull() {
        when(spaceResolver.getSpaceGuid()).thenReturn("space-guid-123");
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

        StepVerifier.create(stagingService.stage(
                        "test source".getBytes(), null, null, null))
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
        when(spaceResolver.getSpaceGuid()).thenReturn("space-guid-123");
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
                        "test source".getBytes(), "java_buildpack", 512, 1024))
                .thenAwait(Duration.ofMinutes(6))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.errorMessage()).containsAnyOf("timed out", "Timeout", "did not observe", "within");
                })
                .verifyComplete();
    }
}
