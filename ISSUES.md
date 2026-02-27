# Dropship Phase 1 — Issues Breakdown

Each issue is scoped to be independently buildable, testable, and mergeable. Issues within a milestone can be worked in parallel unless a dependency is noted.

> **Note:** GitHub issue #1 was auto-created. Our issues start at **#2**.
> All issue cross-references below use actual GitHub issue numbers.

-----

## Labels

| Label | Color | Description |
|---|---|---|
| `scaffold` | `#0E8A16` | Project setup, build config, CI |
| `config` | `#1D76DB` | Configuration, properties, profiles |
| `tool` | `#5319E7` | MCP tool implementation |
| `service` | `#D93F0B` | CF service layer integration |
| `model` | `#FBCA04` | Data models, records, DTOs |
| `test` | `#B60205` | Unit or integration tests |
| `infra` | `#006B75` | CF foundation setup, deployment |
| `docs` | `#C5DEF5` | Documentation |
| `blocked` | `#E4E669` | Waiting on another issue |
| `mvp` | `#F9D0C4` | Required for MVP completion |

-----

## Milestones

### M1: Skeleton — "It boots and responds to MCP initialize"
> Issues #1–#4 | No CF dependency yet

### M2: CF Wiring — "Dropship can talk to Cloud Foundry"
> Issues #5–#7 | Connects to real CF foundation

### M3: stage_code — "Agents can compile code through CF"
> Issues #8–#11 | First usable tool, end-to-end staging

### M4: run_task — "Agents can execute code in Garden containers"
> Issues #12–#15 | Second tool, full execute cycle

### M5: get_task_logs — "Agents can retrieve structured execution output"
> Issues #16–#18 | Third tool, completes the feedback loop

### M6: Integration & Deployment — "Ship it to CF"
> Issues #19–#22 | End-to-end on real foundation, CF manifest, client config

-----

## M1: Skeleton

### Issue #1 — Initialize Spring Boot project with pom.xml
**Labels:** `scaffold` `mvp`
**Milestone:** M1
**Depends on:** nothing

**Description:**
Create the Maven project scaffold with all dependencies declared and verified to resolve.

**Acceptance criteria:**
- [ ] `pom.xml` with Spring Boot 3.4.x parent
- [ ] Spring AI 1.1.2 BOM in `<dependencyManagement>`
- [ ] Dependencies: `spring-ai-starter-mcp-server-webflux`, `mcp-annotations` (community), `cloudfoundry-client-reactor` 5.16.0, `cloudfoundry-operations` 5.16.0, `spring-boot-starter-test`
- [ ] `.gitignore` updated for Maven (`target/`, `.idea/`, `*.iml`, `.mvn/wrapper/`)
- [ ] `mvn dependency:resolve` succeeds with no conflicts
- [ ] Handle any Reactor Netty version conflicts between Spring Boot 3.x and cf-java-client 5.x

**Files:**
- `pom.xml`
- `.gitignore` (update)

---

### Issue #2 — Create DropshipApplication entry point
**Labels:** `scaffold` `mvp`
**Milestone:** M1
**Depends on:** #1

**Description:**
Minimal `@SpringBootApplication` class. Verify the app starts and the MCP server endpoint responds to protocol handshake.

**Acceptance criteria:**
- [ ] `DropshipApplication.java` with `@SpringBootApplication`
- [ ] `application.yml` with MCP server name/version and annotation scanner enabled
- [ ] App starts on `mvn spring-boot:run` without errors
- [ ] MCP endpoint is accessible (Streamable HTTP on default port)

**Files:**
- `src/main/java/com/baskette/dropship/DropshipApplication.java`
- `src/main/resources/application.yml`

---

### Issue #3 — Define DropshipProperties configuration
**Labels:** `config` `mvp`
**Milestone:** M1
**Depends on:** #2

**Description:**
`@ConfigurationProperties` record for all Dropship-specific settings. Includes sandbox org/space, resource limits, and defaults.

**Acceptance criteria:**
- [ ] `DropshipProperties.java` as a `record` with `@ConfigurationProperties(prefix = "dropship")`
- [ ] `@EnableConfigurationProperties(DropshipProperties.class)` on app or config
- [ ] All properties populated from `application.yml` with sensible defaults
- [ ] Properties: `sandboxOrg`, `sandboxSpace`, `maxTaskMemoryMb` (2048), `maxTaskDiskMb` (4096), `maxTaskTimeoutSeconds` (900), `defaultTaskMemoryMb` (512), `defaultStagingMemoryMb` (1024), `defaultStagingDiskMb` (2048), `appNamePrefix` ("dropship-")
- [ ] Unit test: verify defaults bind correctly from test yaml

**Files:**
- `src/main/java/com/baskette/dropship/config/DropshipProperties.java`
- `src/main/resources/application.yml` (update)
- `src/test/java/com/baskette/dropship/config/DropshipPropertiesTest.java`

---

### Issue #4 — Add Spring profiles for local and cloud
**Labels:** `config` `mvp`
**Milestone:** M1
**Depends on:** #2

**Description:**
Create profile-specific configuration for local development (STDIO transport option) and CF deployment (cloud profile auto-activated by `VCAP_APPLICATION`).

**Acceptance criteria:**
- [ ] `application-local.yml` — optional STDIO transport settings, console logging
- [ ] `application-cloud.yml` — CF API URL from `vcap.application.cf_api`, structured logging
- [ ] Default profile (no activation) works for Streamable HTTP local dev
- [ ] Document profile usage in a comment block in each file

**Files:**
- `src/main/resources/application-local.yml`
- `src/main/resources/application-cloud.yml`

-----

## M2: CF Wiring

### Issue #5 — Implement CloudFoundryConfig (CF client beans)
**Labels:** `config` `service` `mvp`
**Milestone:** M2
**Depends on:** #3

**Description:**
Spring `@Configuration` class that creates CF client beans: `ConnectionContext`, `TokenProvider` (client credentials grant), `ReactorCloudFoundryClient`, and `DefaultCloudFoundryOperations`.

**Acceptance criteria:**
- [ ] `CloudFoundryConfig.java` with `@Configuration`
- [ ] `DefaultConnectionContext` bean — reads CF API URL, skip-ssl config
- [ ] `ClientCredentialsGrantTokenProvider` bean — reads client ID/secret
- [ ] `ReactorCloudFoundryClient` bean — wired with context + token provider
- [ ] `DefaultCloudFoundryOperations` bean — scoped to sandbox org/space from `DropshipProperties`
- [ ] Helper method to extract host from full API URL (e.g., `https://api.sys.example.com` → `api.sys.example.com`)

**Files:**
- `src/main/java/com/baskette/dropship/config/CloudFoundryConfig.java`

---

### Issue #6 — Verify CF connectivity on startup
**Labels:** `service` `mvp`
**Milestone:** M2
**Depends on:** #5

**Description:**
Add a startup check that validates Dropship can authenticate to UAA and reach CF API. Log success/failure. This is not a health check (Phase 2) — just a fast-fail validation.

**Acceptance criteria:**
- [ ] `@EventListener(ApplicationReadyEvent.class)` method in a component
- [ ] Calls CF API info endpoint (`GET /`) to verify reachability
- [ ] Calls `organizations.list()` or similar to verify token works
- [ ] Logs: `"Dropship connected to CF: {api_url}, org: {org}, space: {space}"`
- [ ] Logs warning and continues (does not crash) if CF is unreachable — allows local dev without CF

**Files:**
- `src/main/java/com/baskette/dropship/config/CloudFoundryHealthCheck.java`

---

### Issue #7 — Resolve space GUID for sandbox org/space
**Labels:** `service` `mvp`
**Milestone:** M2
**Depends on:** #5

**Description:**
Tools need the space GUID to create apps and tasks. Resolve and cache the sandbox space GUID at startup from the org/space names in `DropshipProperties`.

**Acceptance criteria:**
- [ ] Service or config method that resolves `orgName + spaceName → spaceGuid`
- [ ] Uses `cloudfoundry-client` v3 API: list orgs by name → list spaces by name + org GUID
- [ ] Caches result (space GUID won't change during app lifetime)
- [ ] Fails fast with clear error if org or space not found
- [ ] Unit test with mocked CF client

**Files:**
- `src/main/java/com/baskette/dropship/service/SpaceResolver.java`
- `src/test/java/com/baskette/dropship/service/SpaceResolverTest.java`

-----

## M3: stage_code

### Issue #8 — Define StagingResult model
**Labels:** `model` `mvp`
**Milestone:** M3
**Depends on:** nothing

**Description:**
Java record for the `stage_code` tool's return value.

**Acceptance criteria:**
- [ ] `StagingResult.java` record with fields: `dropletGuid`, `appGuid`, `buildpack`, `stagingLogs`, `durationMs`, `success`, `errorMessage`
- [ ] Serializes cleanly to JSON (verify with a simple test)

**Files:**
- `src/main/java/com/baskette/dropship/model/StagingResult.java`

---

### Issue #9 — Implement StagingService (CF app + build lifecycle)
**Labels:** `service` `mvp`
**Milestone:** M3
**Depends on:** #5, #7, #8

**Description:**
Core service that manages the ephemeral app lifecycle: create app → upload package → create build → poll until staged → extract droplet GUID.

**Acceptance criteria:**
- [ ] `StagingService.java` with constructor injection of `ReactorCloudFoundryClient` and `DropshipProperties`
- [ ] `stage(byte[] sourceBundle, String buildpack, Integer memoryMb, Integer diskMb)` returns `Mono<StagingResult>`
- [ ] Creates ephemeral app with generated name (`dropship-{uuid-short}`) in sandbox space
- [ ] Uploads source as a package of type `bits`
- [ ] Creates build with specified buildpack (or auto-detect if null)
- [ ] Polls build status with exponential backoff (500ms, 1s, 2s, 4s... capped at 10s)
- [ ] Extracts staging logs from build response
- [ ] Returns `StagingResult` with droplet GUID on success, error details on failure
- [ ] Respects staging timeout (default 5 minutes)
- [ ] All operations are reactive (`Mono`/`Flux`) — no `block()` calls

**Files:**
- `src/main/java/com/baskette/dropship/service/StagingService.java`

---

### Issue #10 — Implement StageCodeTool (@McpTool)
**Labels:** `tool` `mvp`
**Milestone:** M3
**Depends on:** #9

**Description:**
MCP tool class that exposes `stage_code` to MCP clients. Thin layer over `StagingService` — handles parameter mapping and base64 decoding.

**Acceptance criteria:**
- [ ] `StageCodeTool.java` annotated as Spring `@Service`
- [ ] Method annotated with `@McpTool(name = "stage_code", description = "...")`
- [ ] Parameters: `sourceBundle` (base64 string), `buildpack` (string, optional), `memoryMb` (int, optional), `diskMb` (int, optional)
- [ ] Each parameter annotated with `@McpToolParam(description = "...")`
- [ ] Decodes base64 → `byte[]`, delegates to `StagingService.stage()`
- [ ] Returns `StagingResult` (serialized to JSON by MCP framework)
- [ ] Input validation: reject empty source bundle, validate base64 encoding

**Files:**
- `src/main/java/com/baskette/dropship/tool/StageCodeTool.java`

---

### Issue #11 — Unit tests for StageCodeTool + StagingService
**Labels:** `test` `mvp`
**Milestone:** M3
**Depends on:** #9, #10

**Description:**
Unit tests for the staging pipeline with mocked CF client.

**Acceptance criteria:**
- [ ] `StageCodeToolTest.java`:
  - Valid base64 source → delegates to service
  - Invalid base64 → returns error result
  - Null/empty source → returns error result
  - Memory/disk defaults applied when not specified
- [ ] `StagingServiceTest.java`:
  - Happy path: create app → upload → build → staged → returns droplet GUID
  - Build fails: returns staging logs with error
  - Build timeout: returns partial logs + timeout error
  - CF API error: returns meaningful error message
- [ ] All tests use Mockito to mock `ReactorCloudFoundryClient`
- [ ] Tests use `StepVerifier` for reactive assertions

**Files:**
- `src/test/java/com/baskette/dropship/tool/StageCodeToolTest.java`
- `src/test/java/com/baskette/dropship/service/StagingServiceTest.java`

-----

## M4: run_task

### Issue #12 — Define TaskResult model
**Labels:** `model` `mvp`
**Milestone:** M4
**Depends on:** nothing

**Description:**
Java record for the `run_task` tool's return value.

**Acceptance criteria:**
- [ ] `TaskResult.java` record with fields: `taskGuid`, `appGuid`, `exitCode`, `state`, `durationMs`, `memoryMb`, `command`
- [ ] Serializes cleanly to JSON

**Files:**
- `src/main/java/com/baskette/dropship/model/TaskResult.java`

---

### Issue #13 — Implement TaskService (CF task execution)
**Labels:** `service` `mvp`
**Milestone:** M4
**Depends on:** #5, #7, #12

**Description:**
Service that sets a droplet on an app, creates a Diego task, and polls it to completion.

**Acceptance criteria:**
- [ ] `TaskService.java` with constructor injection of `ReactorCloudFoundryClient` and `DropshipProperties`
- [ ] `runTask(String dropletGuid, String appGuid, String command, Integer memoryMb, Integer timeoutSeconds, Map<String, String> environment)` returns `Mono<TaskResult>`
- [ ] Sets current droplet on the app via `PATCH /v3/apps/:guid/relationships/current_droplet`
- [ ] Creates task via `POST /v3/apps/:guid/tasks` with command, memory, disk, timeout
- [ ] Resource clamping: `Math.min(requested, maxConfigured)` for memory, disk, timeout
- [ ] Polls task status with exponential backoff until `SUCCEEDED` or `FAILED`
- [ ] Extracts exit code from terminal task state
- [ ] Environment variables passed through to task
- [ ] Tracks task duration (wall clock from create to terminal)
- [ ] All reactive, no `block()`

**Files:**
- `src/main/java/com/baskette/dropship/service/TaskService.java`

---

### Issue #14 — Implement RunTaskTool (@McpTool)
**Labels:** `tool` `mvp`
**Milestone:** M4
**Depends on:** #13

**Description:**
MCP tool class that exposes `run_task` to MCP clients.

**Acceptance criteria:**
- [ ] `RunTaskTool.java` annotated as Spring `@Service`
- [ ] Method annotated with `@McpTool(name = "run_task", description = "...")`
- [ ] Parameters: `dropletGuid` (required), `command` (required), `memoryMb` (optional), `timeoutSeconds` (optional), `environment` (optional map)
- [ ] Each parameter annotated with `@McpToolParam`
- [ ] Needs to resolve `dropletGuid → appGuid` (query CF for the droplet's parent app, or accept `appGuid` as parameter from prior `stage_code` result)
- [ ] Delegates to `TaskService.runTask()`
- [ ] Input validation: reject empty command, reject unknown droplet GUID

**Files:**
- `src/main/java/com/baskette/dropship/tool/RunTaskTool.java`

---

### Issue #15 — Unit tests for RunTaskTool + TaskService
**Labels:** `test` `mvp`
**Milestone:** M4
**Depends on:** #13, #14

**Description:**
Unit tests for the task execution pipeline.

**Acceptance criteria:**
- [ ] `RunTaskToolTest.java`:
  - Valid inputs → delegates to service
  - Missing droplet GUID → error
  - Missing command → error
  - Memory exceeds max → clamped to max
  - Timeout exceeds max → clamped to max
- [ ] `TaskServiceTest.java`:
  - Happy path: set droplet → create task → poll → SUCCEEDED → exit code 0
  - Task fails: poll → FAILED → exit code non-zero
  - Task timeout: poll exceeds timeout → returns timeout error
  - Environment variables passed through
  - Resource clamping logic verified
- [ ] Mockito + StepVerifier

**Files:**
- `src/test/java/com/baskette/dropship/tool/RunTaskToolTest.java`
- `src/test/java/com/baskette/dropship/service/TaskServiceTest.java`

-----

## M5: get_task_logs

### Issue #16 — Define TaskLogs model
**Labels:** `model` `mvp`
**Milestone:** M5
**Depends on:** nothing

**Description:**
Java records for the `get_task_logs` tool's return value, including nested `LogEntry`.

**Acceptance criteria:**
- [ ] `TaskLogs.java` record with fields: `taskGuid`, `entries` (List<LogEntry>), `totalLines`, `truncated`
- [ ] Nested `LogEntry` record: `timestamp` (Instant), `source` ("stdout"/"stderr"), `message`
- [ ] Serializes cleanly to JSON (Instant as ISO-8601)

**Files:**
- `src/main/java/com/baskette/dropship/model/TaskLogs.java`

---

### Issue #17 — Implement LogService (log retrieval)
**Labels:** `service` `mvp`
**Milestone:** M5
**Depends on:** #5, #16

**Description:**
Service that retrieves logs for a task from CF. MVP uses `cloudfoundry-operations` `Logs.getRecent()` approach. Phase 2 upgrades to Loggregator Log Cache API for structured metadata.

**Acceptance criteria:**
- [ ] `LogService.java` with constructor injection of `DefaultCloudFoundryOperations` (or `ReactorCloudFoundryClient`)
- [ ] `getTaskLogs(String taskGuid, String appName, Integer maxLines, String source)` returns `Mono<TaskLogs>`
- [ ] Retrieves recent logs for the app associated with the task
- [ ] Filters by source type: `stdout`, `stderr`, or `all`
- [ ] Orders entries by timestamp
- [ ] Truncates to `maxLines` (default 500), sets `truncated` flag if applicable
- [ ] Maps CF log envelopes to `LogEntry` records
- [ ] Handles case where no logs are found (empty result, not error)

**Files:**
- `src/main/java/com/baskette/dropship/service/LogService.java`

---

### Issue #18 — Implement GetTaskLogsTool (@McpTool) + unit tests
**Labels:** `tool` `test` `mvp`
**Milestone:** M5
**Depends on:** #17

**Description:**
MCP tool class for `get_task_logs` and its unit tests. Bundled since this is the simplest tool.

**Acceptance criteria:**
- [ ] `GetTaskLogsTool.java` annotated as Spring `@Service`
- [ ] Method annotated with `@McpTool(name = "get_task_logs", description = "...")`
- [ ] Parameters: `taskGuid` (required), `maxLines` (optional, default 500), `source` (optional, default "all")
- [ ] Each parameter annotated with `@McpToolParam`
- [ ] Delegates to `LogService.getTaskLogs()`
- [ ] `GetTaskLogToolTest.java`:
  - Valid task GUID → returns logs
  - Filter stdout only → only stdout entries
  - Filter stderr only → only stderr entries
  - Max lines truncation works
  - Unknown task GUID → empty result or error
- [ ] `LogServiceTest.java`:
  - Happy path: returns ordered log entries
  - Empty logs: returns empty list, truncated=false
  - Truncation: 600 log lines, maxLines=500 → 500 entries, truncated=true
  - Source filtering logic

**Files:**
- `src/main/java/com/baskette/dropship/tool/GetTaskLogsTool.java`
- `src/test/java/com/baskette/dropship/tool/GetTaskLogToolTest.java`
- `src/test/java/com/baskette/dropship/service/LogServiceTest.java`

-----

## M6: Integration & Deployment

### Issue #19 — Create CF deployment manifest
**Labels:** `infra` `mvp`
**Milestone:** M6
**Depends on:** #1

**Description:**
CF `manifest.yml` for deploying Dropship to the foundation.

**Acceptance criteria:**
- [ ] `manifest.yml` with app name `dropship-mcp`
- [ ] Memory: 512M, Disk: 1G, Instances: 1
- [ ] Java buildpack with JRE 21
- [ ] `SPRING_PROFILES_ACTIVE: cloud`
- [ ] Environment variables for sandbox org/space (overridable)
- [ ] Service binding placeholder for UAA credentials
- [ ] Path points to built JAR

**Files:**
- `manifest.yml`

---

### Issue #20 — Document CF foundation prerequisites
**Labels:** `docs` `infra` `mvp`
**Milestone:** M6
**Depends on:** nothing

**Description:**
Runbook for setting up the CF foundation to support Dropship: UAA client, org/space, quotas, ASGs, space roles.

**Acceptance criteria:**
- [ ] `docs/cf-setup.md` with step-by-step commands:
  - UAA client creation (`uaac client add`)
  - Org and space creation
  - Space role assignment
  - Space quota creation and assignment
  - ASG recommendations for sandbox
- [ ] Describes required UAA authorities
- [ ] Describes minimum quota recommendations
- [ ] Includes verification commands to confirm setup

**Files:**
- `docs/cf-setup.md`

---

### Issue #21 — Integration tests against real CF foundation
**Labels:** `test` `mvp`
**Milestone:** M6
**Depends on:** #10, #14, #18

**Description:**
End-to-end integration tests that exercise all three tools against a real CF foundation. Gated behind Maven profile.

**Acceptance criteria:**
- [ ] `DropshipIntegrationTest.java` with `@ActiveProfiles("integration")`
- [ ] Test fixture: minimal Java project (Hello World with `main()`)
- [ ] Test 1 — `stage_code`: stage the fixture, assert `StagingResult.success == true`, droplet GUID non-null
- [ ] Test 2 — `run_task`: run `java -cp . Main` against staged droplet, assert exit code 0
- [ ] Test 3 — `get_task_logs`: retrieve logs from test 2, assert stdout contains expected output
- [ ] Test 4 — staging failure: stage invalid source, assert `success == false`, `errorMessage` non-null
- [ ] Test 5 — task failure: run invalid command, assert non-zero exit code
- [ ] Maven profile `-Pintegration` activates these tests
- [ ] Requires env vars: `CF_API_URL`, `CF_CLIENT_ID`, `CF_CLIENT_SECRET`, `DROPSHIP_SANDBOX_ORG`, `DROPSHIP_SANDBOX_SPACE`
- [ ] Cleanup: delete ephemeral apps after tests (best-effort)

**Files:**
- `src/test/java/com/baskette/dropship/integration/DropshipIntegrationTest.java`
- `src/test/resources/fixtures/hello-world/` (minimal Java source)
- `pom.xml` (add integration profile)

---

### Issue #22 — End-to-end verification: MCP client → Dropship → CF
**Labels:** `test` `docs` `mvp`
**Milestone:** M6
**Depends on:** #19, #21

**Description:**
Deploy Dropship to CF and verify a real MCP client (Claude Code or curl) can call all three tools.

**Acceptance criteria:**
- [ ] Deploy Dropship via `cf push` using manifest
- [ ] Configure Claude Code (or curl-based MCP client) to connect to deployed Dropship
- [ ] Verify `stage_code` → `run_task` → `get_task_logs` cycle works end-to-end
- [ ] Document MCP client configuration in `docs/client-setup.md`:
  - Claude Code (`managed-mcp.json` or `.claude/mcp.json`)
  - Cursor (`.cursor/mcp.json`)
  - curl examples for manual testing
- [ ] Capture and document a sample session transcript

**Files:**
- `docs/client-setup.md`

-----

## Dependency Graph (GitHub issue numbers)

```
#2  pom.xml
 ├── #3  DropshipApplication + application.yml
 │    ├── #4  DropshipProperties
 │    │    └── #6  CloudFoundryConfig
 │    │         ├── #7  CF connectivity check
 │    │         ├── #8  Space GUID resolver
 │    │         │    ├── #10 StagingService ──── #9  StagingResult (model)
 │    │         │    │    └── #11 StageCodeTool
 │    │         │    │         └── #12 Staging unit tests
 │    │         │    ├── #14 TaskService ─────── #13 TaskResult (model)
 │    │         │    │    └── #15 RunTaskTool
 │    │         │    │         └── #16 Task unit tests
 │    │         │    └── #18 LogService ──────── #17 TaskLogs (model)
 │    │         │         └── #19 GetTaskLogsTool + tests
 │    │         │
 │    │         └─────────────────── #22 Integration tests
 │    └── #5  Spring profiles (local/cloud)
 │
 ├── #20 manifest.yml
 └── #21 CF prerequisites docs

#22 + #20 → #23 End-to-end verification
```

**Parallel work opportunities:**
- Models (#9, #13, #17) can all be built in parallel, anytime
- #4 and #5 can be built in parallel (both depend on #3)
- #7 and #8 can be built in parallel (both depend on #6)
- After M2, all three tool tracks (M3, M4, M5) can be built in parallel
- #20 and #21 can be built anytime after #2

-----

## Issue Summary (GitHub issue numbers)

| GH # | Title | Labels | Milestone | Depends On |
|---|---|---|---|---|
| #2 | Initialize Spring Boot project with pom.xml | `scaffold` `mvp` | M1 | — |
| #3 | Create DropshipApplication entry point | `scaffold` `mvp` | M1 | #2 |
| #4 | Define DropshipProperties configuration | `config` `mvp` | M1 | #3 |
| #5 | Add Spring profiles for local and cloud | `config` `mvp` | M1 | #3 |
| #6 | Implement CloudFoundryConfig (CF client beans) | `config` `service` `mvp` | M2 | #4 |
| #7 | Verify CF connectivity on startup | `service` `mvp` | M2 | #6 |
| #8 | Resolve space GUID for sandbox org/space | `service` `mvp` | M2 | #6 |
| #9 | Define StagingResult model | `model` `mvp` | M3 | — |
| #10 | Implement StagingService (CF app + build lifecycle) | `service` `mvp` | M3 | #6, #8, #9 |
| #11 | Implement StageCodeTool (@McpTool) | `tool` `mvp` | M3 | #10 |
| #12 | Unit tests for StageCodeTool + StagingService | `test` `mvp` | M3 | #10, #11 |
| #13 | Define TaskResult model | `model` `mvp` | M4 | — |
| #14 | Implement TaskService (CF task execution) | `service` `mvp` | M4 | #6, #8, #13 |
| #15 | Implement RunTaskTool (@McpTool) | `tool` `mvp` | M4 | #14 |
| #16 | Unit tests for RunTaskTool + TaskService | `test` `mvp` | M4 | #14, #15 |
| #17 | Define TaskLogs model | `model` `mvp` | M5 | — |
| #18 | Implement LogService (log retrieval) | `service` `mvp` | M5 | #6, #17 |
| #19 | Implement GetTaskLogsTool + unit tests | `tool` `test` `mvp` | M5 | #18 |
| #20 | Create CF deployment manifest | `infra` `mvp` | M6 | #2 |
| #21 | Document CF foundation prerequisites | `docs` `infra` `mvp` | M6 | — |
| #22 | Integration tests against real CF | `test` `mvp` | M6 | #11, #15, #19 |
| #23 | End-to-end verification: MCP client → Dropship → CF | `test` `docs` `mvp` | M6 | #20, #22 |
