# TaskService Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete the TaskService by adding `createTask`, `pollTask`, and `runTask` methods that create CF Diego tasks with resource clamping, poll them with exponential backoff, and orchestrate the full lifecycle returning a `TaskResult`.

**Architecture:** Fully reactive chain using Project Reactor `Mono`. Follows the exact same patterns as `StagingService` — resource clamping via `DropshipProperties`, polling via `Retry.backoff()` with a custom in-progress exception, error handling via `onErrorResume`, and timeout enforcement via `.timeout()`.

**Tech Stack:** Java 21, Spring Boot, CF Java Client 5.16.0 (reactive), Project Reactor, Mockito + StepVerifier for tests.

---

### Task 1: Add `createTask` method — failing tests

**Files:**
- Modify: `src/test/java/com/baskette/dropship/service/TaskServiceTest.java`

**Step 1: Write failing tests for createTask**

Add these imports and mocks to the existing test class, then add 4 test methods:

```java
// New imports needed:
import org.cloudfoundry.client.v3.tasks.Tasks;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.TaskState;
import org.mockito.ArgumentCaptor;

// New mocks in the test class:
@Mock private Tasks tasks;
@Captor private ArgumentCaptor<CreateTaskRequest> taskRequestCaptor;
```

Test methods:

```java
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
void createTaskSetsCommandAndAppGuid() {
    when(cfClient.tasks()).thenReturn(tasks);
    when(tasks.create(any(CreateTaskRequest.class)))
            .thenReturn(Mono.just(createTaskResponse("task-guid-4")));

    StepVerifier.create(taskService.createTask(
                    "app-guid-99", "rake db:migrate", 256, 512, 60, null))
            .expectNext("task-guid-4")
            .verifyComplete();

    verify(tasks).create(taskRequestCaptor.capture());
    CreateTaskRequest request = taskRequestCaptor.getValue();
    assertThat(request.getApplicationId()).isEqualTo("app-guid-99");
    assertThat(request.getCommand()).isEqualTo("rake db:migrate");
    assertThat(request.getMemoryInMb()).isEqualTo(256);
    assertThat(request.getDiskInMb()).isEqualTo(512);
}
```

Add this helper method at the bottom of the test class:

```java
private CreateTaskResponse createTaskResponse(String taskGuid) {
    return CreateTaskResponse.builder()
            .id(taskGuid)
            .name("task")
            .state(TaskState.RUNNING)
            .sequenceId(1)
            .memoryInMb(512)
            .diskInMb(1024)
            .createdAt("2024-01-01T00:00:00Z")
            .build();
}
```

**Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=TaskServiceTest -Dspring.profiles.active=test -q`
Expected: Compilation error — `createTask` method does not exist.

---

### Task 2: Add `createTask` method — implementation

**Files:**
- Modify: `src/main/java/com/baskette/dropship/service/TaskService.java`

**Step 3: Implement createTask with resource clamping**

Add these imports to TaskService.java:

```java
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.TaskState;
import java.time.Duration;
import java.util.Map;
```

Add the method after `setCurrentDroplet`:

```java
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
```

**Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=TaskServiceTest -Dspring.profiles.active=test -q`
Expected: All tests PASS.

**Step 5: Commit**

```bash
git add src/main/java/com/baskette/dropship/service/TaskService.java \
       src/test/java/com/baskette/dropship/service/TaskServiceTest.java
git commit -m "feat(TaskService): add createTask method with resource clamping (#50)"
```

---

### Task 3: Add `pollTask` method — failing tests

**Files:**
- Modify: `src/test/java/com/baskette/dropship/service/TaskServiceTest.java`

**Step 6: Write failing tests for pollTask**

Add these imports:

```java
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
```

Add test methods:

```java
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
            .thenReturn(Mono.just(getTaskResponse("task-guid-2", TaskState.FAILED)));

    StepVerifier.create(taskService.pollTask("task-guid-2"))
            .assertNext(response ->
                    assertThat(response.getState()).isEqualTo(TaskState.FAILED))
            .verifyComplete();
}

@Test
void pollTaskRetriesWhileRunning() {
    when(cfClient.tasks()).thenReturn(tasks);
    when(tasks.get(any(GetTaskRequest.class)))
            .thenReturn(Mono.just(getTaskResponse("task-guid-3", TaskState.RUNNING)))
            .thenReturn(Mono.just(getTaskResponse("task-guid-3", TaskState.RUNNING)))
            .thenReturn(Mono.just(getTaskResponse("task-guid-3", TaskState.SUCCEEDED)));

    StepVerifier.create(taskService.pollTask("task-guid-3"))
            .assertNext(response ->
                    assertThat(response.getState()).isEqualTo(TaskState.SUCCEEDED))
            .verifyComplete();
}
```

Add this helper:

```java
private GetTaskResponse getTaskResponse(String taskGuid, TaskState state) {
    return GetTaskResponse.builder()
            .id(taskGuid)
            .name("task")
            .state(state)
            .sequenceId(1)
            .memoryInMb(512)
            .diskInMb(1024)
            .command("echo hello")
            .createdAt("2024-01-01T00:00:00Z")
            .build();
}
```

**Step 7: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=TaskServiceTest -Dspring.profiles.active=test -q`
Expected: Compilation error — `pollTask` method does not exist.

---

### Task 4: Add `pollTask` method — implementation

**Files:**
- Modify: `src/main/java/com/baskette/dropship/service/TaskService.java`

**Step 8: Implement pollTask with exponential backoff**

Add these imports:

```java
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import reactor.util.retry.Retry;
```

Add constants at the top of the class (after the logger):

```java
private static final Duration INITIAL_POLL_INTERVAL = Duration.ofMillis(500);
private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(10);
```

Add the method and exception class:

```java
Mono<GetTaskResponse> pollTask(String taskGuid) {
    return cfClient.tasks()
            .get(GetTaskRequest.builder()
                    .taskId(taskGuid)
                    .build())
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

static class TaskInProgressException extends RuntimeException {
    TaskInProgressException(String message) {
        super(message);
    }
}
```

**Step 9: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=TaskServiceTest -Dspring.profiles.active=test -q`
Expected: All tests PASS.

**Step 10: Commit**

```bash
git add src/main/java/com/baskette/dropship/service/TaskService.java \
       src/test/java/com/baskette/dropship/service/TaskServiceTest.java
git commit -m "feat(TaskService): add pollTask method with exponential backoff (#51)"
```

---

### Task 5: Add `runTask` orchestration — failing tests

**Files:**
- Modify: `src/test/java/com/baskette/dropship/service/TaskServiceTest.java`

**Step 11: Write failing tests for runTask**

Add import:

```java
import com.baskette.dropship.model.TaskResult;
```

Add test methods:

```java
@Test
void runTaskReturnsSuccessResult() {
    // setCurrentDroplet
    when(cfClient.applicationsV3()).thenReturn(applicationsV3);
    when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
            .thenReturn(Mono.just(SetApplicationCurrentDropletResponse.builder()
                    .data(Relationship.builder().id("droplet-guid-1").build())
                    .build()));

    // createTask
    when(cfClient.tasks()).thenReturn(tasks);
    when(tasks.create(any(CreateTaskRequest.class)))
            .thenReturn(Mono.just(createTaskResponse("task-guid-1")));

    // pollTask — SUCCEEDED
    when(tasks.get(any(GetTaskRequest.class)))
            .thenReturn(Mono.just(getTaskResponse("task-guid-1", TaskState.SUCCEEDED)));

    StepVerifier.create(taskService.runTask(
                    "droplet-guid-1", "app-guid-1", "echo hello", null, null, null))
            .assertNext(result -> {
                assertThat(result.state()).isEqualTo(TaskResult.State.SUCCEEDED);
                assertThat(result.taskGuid()).isEqualTo("task-guid-1");
                assertThat(result.appGuid()).isEqualTo("app-guid-1");
                assertThat(result.command()).isEqualTo("echo hello");
                assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
            })
            .verifyComplete();
}

@Test
void runTaskReturnsFailureResultWhenTaskFails() {
    when(cfClient.applicationsV3()).thenReturn(applicationsV3);
    when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
            .thenReturn(Mono.just(SetApplicationCurrentDropletResponse.builder()
                    .data(Relationship.builder().id("droplet-guid-1").build())
                    .build()));

    when(cfClient.tasks()).thenReturn(tasks);
    when(tasks.create(any(CreateTaskRequest.class)))
            .thenReturn(Mono.just(createTaskResponse("task-guid-2")));
    when(tasks.get(any(GetTaskRequest.class)))
            .thenReturn(Mono.just(getTaskResponse("task-guid-2", TaskState.FAILED)));

    StepVerifier.create(taskService.runTask(
                    "droplet-guid-1", "app-guid-1", "exit 1", null, null, null))
            .assertNext(result -> {
                assertThat(result.state()).isEqualTo(TaskResult.State.FAILED);
                assertThat(result.taskGuid()).isEqualTo("task-guid-2");
            })
            .verifyComplete();
}

@Test
void runTaskReturnsErrorResultOnSetDropletFailure() {
    when(cfClient.applicationsV3()).thenReturn(applicationsV3);
    when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
            .thenReturn(Mono.error(new RuntimeException("CF API error")));

    StepVerifier.create(taskService.runTask(
                    "droplet-guid-1", "app-guid-1", "echo hello", null, null, null))
            .assertNext(result -> {
                assertThat(result.state()).isEqualTo(TaskResult.State.FAILED);
                assertThat(result.appGuid()).isEqualTo("app-guid-1");
            })
            .verifyComplete();
}

@Test
void runTaskReturnsTimeoutErrorWhenTaskNeverCompletes() {
    when(cfClient.applicationsV3()).thenReturn(applicationsV3);
    when(applicationsV3.setCurrentDroplet(any(SetApplicationCurrentDropletRequest.class)))
            .thenReturn(Mono.just(SetApplicationCurrentDropletResponse.builder()
                    .data(Relationship.builder().id("droplet-guid-1").build())
                    .build()));

    when(cfClient.tasks()).thenReturn(tasks);
    when(tasks.create(any(CreateTaskRequest.class)))
            .thenReturn(Mono.just(createTaskResponse("task-guid-5")));
    when(tasks.get(any(GetTaskRequest.class)))
            .thenReturn(Mono.just(getTaskResponse("task-guid-5", TaskState.RUNNING)));

    StepVerifier.withVirtualTime(() -> taskService.runTask(
                    "droplet-guid-1", "app-guid-1", "sleep 9999", null, null, null))
            .thenAwait(Duration.ofSeconds(1000))
            .assertNext(result -> {
                assertThat(result.state()).isEqualTo(TaskResult.State.FAILED);
                assertThat(result.command()).isEqualTo("sleep 9999");
            })
            .verifyComplete();
}
```

**Step 12: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=TaskServiceTest -Dspring.profiles.active=test -q`
Expected: Compilation error — `runTask` method does not exist.

---

### Task 6: Add `runTask` orchestration — implementation

**Files:**
- Modify: `src/main/java/com/baskette/dropship/service/TaskService.java`

**Step 13: Implement runTask**

Add import:

```java
import com.baskette.dropship.model.TaskResult;
```

Add the method:

```java
public Mono<TaskResult> runTask(String dropletGuid, String appGuid, String command,
                                 Integer memoryMb, Integer timeoutSeconds,
                                 Map<String, String> environment) {
    long startTime = System.currentTimeMillis();

    log.info("Starting task: appGuid={}, command={}", appGuid, command);

    return setCurrentDroplet(appGuid, dropletGuid)
            .then(createTask(appGuid, command, memoryMb, null, timeoutSeconds, environment))
            .flatMap(this::pollTask)
            .map(taskResponse -> toTaskResult(taskResponse, appGuid, command, startTime))
            .onErrorResume(error -> Mono.just(
                    toErrorResult(appGuid, command, startTime, error)))
            .timeout(Duration.ofSeconds(properties.maxTaskTimeoutSeconds()))
            .onErrorResume(error -> {
                log.error("Task timed out or failed: appGuid={}, error={}",
                        appGuid, error.getMessage());
                return Mono.just(new TaskResult(
                        null, appGuid, -1, TaskResult.State.FAILED,
                        System.currentTimeMillis() - startTime, 0, command));
            });
}

private TaskResult toTaskResult(GetTaskResponse taskResponse,
                                 String appGuid, String command, long startTime) {
    long duration = System.currentTimeMillis() - startTime;
    TaskResult.State state = taskResponse.getState() == TaskState.SUCCEEDED
            ? TaskResult.State.SUCCEEDED : TaskResult.State.FAILED;

    log.info("Task completed: guid={}, state={}, duration={}ms",
            taskResponse.getId(), state, duration);

    return new TaskResult(
            taskResponse.getId(), appGuid,
            state == TaskResult.State.SUCCEEDED ? 0 : 1,
            state, duration,
            taskResponse.getMemoryInMb(), command);
}

private TaskResult toErrorResult(String appGuid, String command,
                                  long startTime, Throwable error) {
    long duration = System.currentTimeMillis() - startTime;
    log.error("Task error: appGuid={}, error={}", appGuid, error.getMessage());
    return new TaskResult(null, appGuid, -1, TaskResult.State.FAILED,
            duration, 0, command);
}
```

**Step 14: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=TaskServiceTest -Dspring.profiles.active=test -q`
Expected: All tests PASS.

**Step 15: Commit**

```bash
git add src/main/java/com/baskette/dropship/service/TaskService.java \
       src/test/java/com/baskette/dropship/service/TaskServiceTest.java
git commit -m "feat(TaskService): add runTask orchestration with TaskResult (#52)"
```

---

### Task 7: Full test suite verification

**Step 16: Run all tests**

Run: `./mvnw test -Dspring.profiles.active=test -q`
Expected: All tests PASS (TaskServiceTest + StagingServiceTest + StageCodeToolTest + others).

**Step 17: Verify compilation**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS.

**Step 18: Final commit (if any cleanup needed)**

Only if adjustments were needed during full test run.
