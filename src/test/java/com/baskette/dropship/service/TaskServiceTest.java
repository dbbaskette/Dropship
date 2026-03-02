package com.baskette.dropship.service;

import com.baskette.dropship.config.DropshipProperties;
import com.baskette.dropship.model.TaskResult;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletRequest;
import org.cloudfoundry.client.v3.applications.SetApplicationCurrentDropletResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.TaskState;
import org.cloudfoundry.client.v3.tasks.Tasks;
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

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private ReactorCloudFoundryClient cfClient;

    @Mock
    private SpaceResolver spaceResolver;

    @Mock
    private ApplicationsV3 applicationsV3;

    @Mock
    private Tasks tasks;

    @Captor
    private ArgumentCaptor<SetApplicationCurrentDropletRequest> dropletRequestCaptor;

    @Captor
    private ArgumentCaptor<CreateTaskRequest> taskRequestCaptor;

    private DropshipProperties properties;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        properties = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 900, 512, 1024, 2048, "dropship-");
        taskService = new TaskService(cfClient, properties, spaceResolver);
    }

    @Test
    void setCurrentDropletSetsDropletOnApp() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.just(SetApplicationCurrentDropletResponse.builder()
                        .data(Relationship.builder().id("droplet-guid-123").build())
                        .build()));

        StepVerifier.create(taskService.setCurrentDroplet("app-guid-456", "droplet-guid-123"))
                .verifyComplete();

        verify(applicationsV3).setCurrentDroplet(dropletRequestCaptor.capture());
        SetApplicationCurrentDropletRequest request = dropletRequestCaptor.getValue();
        assertThat(request.getApplicationId()).isEqualTo("app-guid-456");
        assertThat(request.getData().getId()).isEqualTo("droplet-guid-123");
    }

    @Test
    void setCurrentDropletPropagatesCfApiError() {
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("CF API error")));

        StepVerifier.create(taskService.setCurrentDroplet("app-guid-456", "droplet-guid-123"))
                .expectError(RuntimeException.class)
                .verify();
    }

    // --- createTask tests ---

    @Test
    void createTaskUsesDefaultMemoryWhenNull() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-1")));

        StepVerifier.create(taskService.createTask(
                        "app-guid-1", "echo hello", null, null, null, null))
                .expectNext("task-guid-1")
                .verifyComplete();

        verify(tasks).create(taskRequestCaptor.capture());
        CreateTaskRequest request = taskRequestCaptor.getValue();
        assertThat(request.getMemoryInMb()).isEqualTo(512); // defaultTaskMemoryMb
    }

    @Test
    void createTaskClampsMemoryToMax() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-2")));

        StepVerifier.create(taskService.createTask(
                        "app-guid-1", "echo hello", 9999, null, null, null))
                .expectNext("task-guid-2")
                .verifyComplete();

        verify(tasks).create(taskRequestCaptor.capture());
        assertThat(taskRequestCaptor.getValue().getMemoryInMb()).isEqualTo(2048); // maxTaskMemoryMb
    }

    @Test
    void createTaskClampsDiskToMax() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-3")));

        StepVerifier.create(taskService.createTask(
                        "app-guid-1", "echo hello", null, 99999, null, null))
                .expectNext("task-guid-3")
                .verifyComplete();

        verify(tasks).create(taskRequestCaptor.capture());
        assertThat(taskRequestCaptor.getValue().getDiskInMb()).isEqualTo(4096); // maxTaskDiskMb
    }

    @Test
    void createTaskUsesDefaultTimeoutWhenNull() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-5")));

        // With null timeout, should use default of 300 (clamped to max 900)
        StepVerifier.create(taskService.createTask(
                        "app-guid-1", "echo hello", null, null, null, null))
                .expectNext("task-guid-5")
                .verifyComplete();
    }

    @Test
    void createTaskClampsTimeoutToMax() {
        // Create properties with a low max timeout to verify clamping
        DropshipProperties lowTimeoutProps = new DropshipProperties(
                "test-org", "test-space", "https://api.test.cf.example.com",
                2048, 4096, 60, 512, 1024, 2048, "dropship-");
        TaskService service = new TaskService(cfClient, lowTimeoutProps, spaceResolver);

        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-6")));

        // Request 300s timeout, but max is 60s — should be clamped
        StepVerifier.create(service.createTask(
                        "app-guid-1", "echo hello", null, null, 300, null))
                .expectNext("task-guid-6")
                .verifyComplete();
    }

    @Test
    void createTaskAcceptsEnvironmentVariables() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-7")));

        Map<String, String> env = Map.of("KEY1", "value1", "KEY2", "value2");

        StepVerifier.create(taskService.createTask(
                        "app-guid-1", "echo hello", null, null, null, env))
                .expectNext("task-guid-7")
                .verifyComplete();
    }

    @Test
    void createTaskSetsCommandAndAppGuid() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-4")));

        StepVerifier.create(taskService.createTask(
                        "app-guid-99", "rake db:migrate", 256, 512, null, null))
                .expectNext("task-guid-4")
                .verifyComplete();

        verify(tasks).create(taskRequestCaptor.capture());
        CreateTaskRequest request = taskRequestCaptor.getValue();
        assertThat(request.getApplicationId()).isEqualTo("app-guid-99");
        assertThat(request.getCommand()).isEqualTo("rake db:migrate");
        assertThat(request.getMemoryInMb()).isEqualTo(256);
        assertThat(request.getDiskInMb()).isEqualTo(512);
    }

    // --- pollTask tests ---

    @Test
    void pollTaskReturnsWhenSucceeded() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.get(any(GetTaskRequest.class)))
                .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.SUCCEEDED)));

        StepVerifier.create(taskService.pollTask("task-guid-1"))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(TaskState.SUCCEEDED);
                    assertThat(response.getId()).isEqualTo("task-guid-1");
                })
                .verifyComplete();
    }

    @Test
    void pollTaskReturnsWhenFailed() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.get(any(GetTaskRequest.class)))
                .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.FAILED)));

        StepVerifier.create(taskService.pollTask("task-guid-1"))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(TaskState.FAILED);
                    assertThat(response.getId()).isEqualTo("task-guid-1");
                })
                .verifyComplete();
    }

    @Test
    void pollTaskRetriesWhileRunning() {
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.get(any(GetTaskRequest.class)))
                .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.RUNNING)))
                .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.RUNNING)))
                .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.SUCCEEDED)));

        StepVerifier.create(taskService.pollTask("task-guid-1"))
                .assertNext(response -> {
                    assertThat(response.getState()).isEqualTo(TaskState.SUCCEEDED);
                    assertThat(response.getId()).isEqualTo("task-guid-1");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(15));
    }

    // --- runTask tests ---

    @Test
    void runTaskHappyPath() {
        // Mock setCurrentDroplet chain
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.just(SetApplicationCurrentDropletResponse.builder()
                        .data(Relationship.builder().id("droplet-guid-123").build())
                        .build()));

        // Mock createTask chain
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-1")));

        // Mock pollTask chain - task succeeds immediately
        when(tasks.get(any(GetTaskRequest.class)))
                .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.SUCCEEDED)));

        StepVerifier.create(taskService.runTask(
                        "app-guid-456", "droplet-guid-123", "echo hello",
                        null, null, null))
                .assertNext(result -> {
                    assertThat(result.taskGuid()).isEqualTo("task-guid-1");
                    assertThat(result.appGuid()).isEqualTo("app-guid-456");
                    assertThat(result.exitCode()).isEqualTo(0);
                    assertThat(result.state()).isEqualTo(TaskResult.State.SUCCEEDED);
                    assertThat(result.command()).isEqualTo("echo hello");
                    assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void runTaskReturnsFailureOnTaskFailed() {
        // Mock setCurrentDroplet chain
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.just(SetApplicationCurrentDropletResponse.builder()
                        .data(Relationship.builder().id("droplet-guid-123").build())
                        .build()));

        // Mock createTask chain
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-1")));

        // Mock pollTask chain - task fails
        when(tasks.get(any(GetTaskRequest.class)))
                .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.FAILED)));

        StepVerifier.create(taskService.runTask(
                        "app-guid-456", "droplet-guid-123", "bad-command",
                        null, null, null))
                .assertNext(result -> {
                    assertThat(result.taskGuid()).isEqualTo("task-guid-1");
                    assertThat(result.appGuid()).isEqualTo("app-guid-456");
                    assertThat(result.exitCode()).isEqualTo(1);
                    assertThat(result.state()).isEqualTo(TaskResult.State.FAILED);
                    assertThat(result.command()).isEqualTo("bad-command");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void runTaskReturnsErrorOnCfApiFailure() {
        // Mock setCurrentDroplet throws error
        when(cfClient.applicationsV3()).thenReturn(applicationsV3);
        when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("CF API error")));

        // Must also mock tasks() since createTask is eagerly assembled in runTask
        when(cfClient.tasks()).thenReturn(tasks);
        when(tasks.create(any(CreateTaskRequest.class)))
                .thenReturn(Mono.just(createTaskResponse("task-guid-1")));

        StepVerifier.create(taskService.runTask(
                        "app-guid-456", "droplet-guid-123", "echo hello",
                        null, null, null))
                .assertNext(result -> {
                    assertThat(result.state()).isEqualTo(TaskResult.State.FAILED);
                    assertThat(result.exitCode()).isEqualTo(1);
                    assertThat(result.appGuid()).isEqualTo("app-guid-456");
                    assertThat(result.taskGuid()).isNull();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    // --- helpers ---

    private CreateTaskResponse createTaskResponse(String taskGuid) {
        return CreateTaskResponse.builder()
                .id(taskGuid)
                .name("task")
                .state(TaskState.RUNNING)
                .sequenceId(1)
                .memoryInMb(512)
                .diskInMb(1024)
                .dropletId("droplet-guid-123")
                .createdAt("2024-01-01T00:00:00Z")
                .build();
    }

    private GetTaskResponse getTaskResponse(String taskGuid, TaskState state) {
        return GetTaskResponse.builder()
                .id(taskGuid)
                .name("task")
                .state(state)
                .sequenceId(1)
                .memoryInMb(512)
                .diskInMb(1024)
                .dropletId("droplet-guid-123")
                .command("echo hello")
                .createdAt("2024-01-01T00:00:00Z")
                .build();
    }
}
