# Dropship Implementation Plan

**Full scope with Phase 1 deep-dive**

-----

## Technology Stack (Confirmed)

|Component|Artifact|Version|
|---|---|---|
|Spring Boot|`spring-boot-starter-webflux`|3.4.x|
|Spring AI BOM|`org.springframework.ai:spring-ai-bom`|1.1.2|
|MCP Server Transport|`org.springframework.ai:spring-ai-starter-mcp-server-webflux`|1.1.2 (via BOM)|
|MCP Annotations (Community)|`org.springaicommunity:mcp-annotations`|latest|
|CF Client|`org.cloudfoundry:cloudfoundry-client-reactor`|5.14.0.RELEASE|
|CF Operations|`org.cloudfoundry:cloudfoundry-operations`|5.14.0.RELEASE|
|Java|OpenJDK|21|

### Key Decisions

- **WebFlux over WebMVC** — cf-java-client is reactive (Reactor-based). WebFlux aligns naturally. No bridging `block()` calls.
- **Community `@McpTool` / `@McpToolParam`** — from `spring-ai-community/mcp-annotations`. Cleaner than low-level `ToolCallbackProvider` registration. Requires `spring.ai.mcp.server.annotation-scanner.enabled=true`.
- **Streamable HTTP transport** — primary transport for CF-deployed server. STDIO profile for local dev/testing.
- **cf-java-client 5.14.0.RELEASE** — latest stable. Reactor Netty-based. CF API v3 support.

-----

## Full Scope Overview

### Phase 1: Foundation (MVP) — **THIS IS THE DEEP PLAN**

Core three-tool MCP server with CF integration. Details below.

### Phase 2: Hardening

- Rate limiting per UAA client identity (Resilience4j or Bucket4j)
- Task queue management — cap concurrent tasks per space, queuing excess
- Droplet caching — SHA-256 hash of source bundle → skip redundant staging
- `/actuator/health` with CF-aware readiness (can reach CAPI? can reach UAA?)
- Micrometer metrics: `dropship.stage.duration`, `dropship.task.duration`, `dropship.task.exit_code`
- Error taxonomy: `StagingFailedException`, `TaskTimeoutException`, `PlatformUnavailableException`

### Phase 3: Enterprise Features

- Multi-space RBAC — map MCP client identity to permitted CF spaces
- Service binding passthrough — inject `VCAP_SERVICES` into task environment
- Cost attribution API — aggregate task metadata (org, space, duration, memory) for chargeback
- Audit event enrichment — tag CF Audit Events with agent identity + mission context
- `managed-mcp.json` template generator for org-wide Claude Code rollout

### Phase 4: Worldmind Integration

- Centurion Nova Force toolkit adapter
- Parallel test orchestration with result aggregation
- Structured log parsing (JUnit XML, pytest JSON, Go test output)
- Mission-level cost rollup

-----

## Phase 1: Foundation (MVP) — Deep Plan

### 1.1 Project Scaffold

**Goal:** Buildable Spring Boot app with MCP server endpoint responding to `initialize`.

#### pom.xml Structure

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.2</version>
</parent>

<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.2</spring-ai.version>
    <cf-java-client.version>5.14.0.RELEASE</cf-java-client.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- MCP Server (WebFlux transport = Streamable HTTP) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
    </dependency>

    <!-- Community @McpTool / @McpToolParam annotations -->
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>mcp-annotations</artifactId>
        <version>LATEST</version>
    </dependency>

    <!-- CF Java Client (Reactive) -->
    <dependency>
        <groupId>org.cloudfoundry</groupId>
        <artifactId>cloudfoundry-client-reactor</artifactId>
        <version>${cf-java-client.version}</version>
    </dependency>
    <dependency>
        <groupId>org.cloudfoundry</groupId>
        <artifactId>cloudfoundry-operations</artifactId>
        <version>${cf-java-client.version}</version>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### Package Structure

```
src/main/java/com/baskette/dropship/
├── DropshipApplication.java              # @SpringBootApplication
├── config/
│   ├── CloudFoundryConfig.java           # CF client beans, UAA auth
│   └── DropshipProperties.java           # @ConfigurationProperties
├── tool/
│   ├── StageCodeTool.java                # @McpTool stage_code
│   ├── RunTaskTool.java                  # @McpTool run_task
│   └── GetTaskLogsTool.java              # @McpTool get_task_logs
├── model/
│   ├── StagingResult.java                # Tool return types
│   ├── TaskResult.java
│   └── TaskLogs.java
└── service/
    ├── StagingService.java               # App creation, build lifecycle
    ├── TaskService.java                  # Task execution, polling
    └── LogService.java                   # Loggregator retrieval

src/main/resources/
├── application.yml                       # Default config
├── application-local.yml                 # Local dev (STDIO transport)
└── application-cloud.yml                 # CF deployment profile

src/test/java/com/baskette/dropship/
├── tool/
│   ├── StageCodeToolTest.java
│   ├── RunTaskToolTest.java
│   └── GetTaskLogToolTest.java
└── integration/
    └── DropshipIntegrationTest.java

manifest.yml                              # CF deployment manifest
```

-----

### 1.2 Configuration Model

#### `DropshipProperties.java`

```java
@ConfigurationProperties(prefix = "dropship")
public record DropshipProperties(
    String sandboxOrg,           // CF org for sandbox execution
    String sandboxSpace,         // CF space for sandbox execution
    int maxTaskMemoryMb,         // Hard cap on task memory (default: 2048)
    int maxTaskDiskMb,           // Hard cap on task disk (default: 4096)
    int maxTaskTimeoutSeconds,   // Hard cap on task timeout (default: 900)
    int defaultTaskMemoryMb,     // Default task memory (default: 512)
    int defaultStagingMemoryMb,  // Default staging memory (default: 1024)
    int defaultStagingDiskMb,    // Default staging disk (default: 2048)
    String appNamePrefix         // Prefix for ephemeral apps (default: "dropship-")
) {}
```

#### `application.yml`

```yaml
dropship:
  sandbox-org: ${DROPSHIP_SANDBOX_ORG:ai-workloads}
  sandbox-space: ${DROPSHIP_SANDBOX_SPACE:agent-sandbox}
  max-task-memory-mb: ${DROPSHIP_MAX_TASK_MEMORY_MB:2048}
  max-task-disk-mb: ${DROPSHIP_MAX_TASK_DISK_MB:4096}
  max-task-timeout-seconds: ${DROPSHIP_MAX_TASK_TIMEOUT_SECONDS:900}
  default-task-memory-mb: 512
  default-staging-memory-mb: 1024
  default-staging-disk-mb: 2048
  app-name-prefix: dropship-

spring:
  ai:
    mcp:
      server:
        name: dropship
        version: 0.1.0
        annotation-scanner:
          enabled: true
  cloud:
    cloudfoundry:
      url: ${CF_API_URL:https://api.sys.example.com}
      username: ${CF_CLIENT_ID:}
      password: ${CF_CLIENT_SECRET:}
      skip-ssl-validation: ${CF_SKIP_SSL:false}
```

#### `application-cloud.yml` (CF profile, activated via VCAP_APPLICATION)

```yaml
spring:
  cloud:
    cloudfoundry:
      url: ${vcap.application.cf_api:}
```

-----

### 1.3 CF Client Configuration

#### `CloudFoundryConfig.java`

```java
@Configuration
public class CloudFoundryConfig {

    @Bean
    ReactorCloudFoundryClient cloudFoundryClient(
            ConnectionContext connectionContext,
            TokenProvider tokenProvider) {
        return ReactorCloudFoundryClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();
    }

    @Bean
    DefaultConnectionContext connectionContext(
            @Value("${spring.cloud.cloudfoundry.url}") String apiHost,
            @Value("${spring.cloud.cloudfoundry.skip-ssl-validation:false}") boolean skipSsl) {
        return DefaultConnectionContext.builder()
                .apiHost(extractHost(apiHost))
                .skipSslValidation(skipSsl)
                .build();
    }

    @Bean
    TokenProvider tokenProvider(
            @Value("${spring.cloud.cloudfoundry.username}") String clientId,
            @Value("${spring.cloud.cloudfoundry.password}") String clientSecret) {
        return ClientCredentialsGrantTokenProvider.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
    }

    @Bean
    DefaultCloudFoundryOperations cloudFoundryOperations(
            ReactorCloudFoundryClient client,
            DropshipProperties props) {
        return DefaultCloudFoundryOperations.builder()
                .cloudFoundryClient(client)
                .organization(props.sandboxOrg())
                .space(props.sandboxSpace())
                .build();
    }
}
```

**Authentication strategy:** UAA client credentials grant. The Dropship service account is a UAA client (not a user). Scoped to `cloud_controller.read`, `cloud_controller.write` for the designated sandbox org/space.

-----

### 1.4 Tool Implementations (Deep Design)

#### Tool 1: `stage_code`

**Flow:**

1. Decode base64 source bundle → byte array
2. Generate unique app name: `dropship-{uuid-short}`
3. Create ephemeral app in sandbox space via CF API v3 (`POST /v3/apps`)
4. Upload source package (`POST /v3/packages` with type `bits`)
5. Create build (`POST /v3/builds` with package GUID and buildpack)
6. Poll build status until `STAGED` or `FAILED`
7. Extract droplet GUID from completed build
8. Return `StagingResult` with droplet GUID + staging logs

**Key CF API v3 calls:**

```
POST /v3/apps
  { name, relationships: { space: { data: { guid } } } }

POST /v3/packages
  { type: "bits", relationships: { app: { data: { guid } } } }

POST /v3/packages/:guid/upload
  (multipart: source bundle)

POST /v3/builds
  { package: { guid }, buildpack: { name } }

GET /v3/builds/:guid  (poll until state != STAGING)

GET /v3/droplets/:guid
```

**Error handling:**

- Source decode failure → return error with "invalid base64 source bundle"
- Build state `FAILED` → return staging logs with compilation errors
- Timeout (configurable, default 5 min) → return partial logs + timeout error

**Return type:**

```java
public record StagingResult(
    String dropletGuid,
    String appGuid,
    String buildpack,
    String stagingLogs,
    long durationMs,
    boolean success,
    String errorMessage
) {}
```

#### Tool 2: `run_task`

**Flow:**

1. Validate droplet GUID exists
2. Set the app's current droplet (`PATCH /v3/apps/:guid/relationships/current_droplet`)
3. Clamp memory/timeout to configured maximums
4. Create task (`POST /v3/apps/:guid/tasks`)
5. Poll task status until terminal state (`SUCCEEDED` or `FAILED`)
6. Return `TaskResult` with exit code + task GUID

**Key CF API v3 calls:**

```
PATCH /v3/apps/:guid/relationships/current_droplet
  { data: { guid: dropletGuid } }

POST /v3/apps/:guid/tasks
  { command, memory_in_mb, disk_in_mb, time_in_seconds }

GET /v3/tasks/:guid  (poll until state is terminal)
```

**Resource clamping logic:**

```java
int effectiveMemory = Math.min(
    requestedMemory != null ? requestedMemory : props.defaultTaskMemoryMb(),
    props.maxTaskMemoryMb()
);
int effectiveTimeout = Math.min(
    requestedTimeout != null ? requestedTimeout : 300,
    props.maxTaskTimeoutSeconds()
);
```

**Return type:**

```java
public record TaskResult(
    String taskGuid,
    String appGuid,
    int exitCode,
    String state,       // SUCCEEDED, FAILED
    long durationMs,
    int memoryMb,
    String command
) {}
```

#### Tool 3: `get_task_logs`

**Flow:**

1. Resolve task GUID → app GUID (if not cached from prior `run_task` call)
2. Query Loggregator Log Cache API for recent envelopes for the app
3. Filter by task source instance and time window
4. Separate stdout/stderr
5. Apply max lines limit
6. Return `TaskLogs`

**CF Log Cache API:**

```
GET /api/v1/read/:source_id
  ?envelope_types=LOG
  &start_time=...
  &end_time=...
  &limit=...
```

**Return type:**

```java
public record TaskLogs(
    String taskGuid,
    List<LogEntry> entries,
    int totalLines,
    boolean truncated
) {
    public record LogEntry(
        Instant timestamp,
        String source,    // "stdout" or "stderr"
        String message
    ) {}
}
```

**Fallback:** If Loggregator Log Cache is unavailable, fall back to `cf logs --recent` equivalent via `cloudfoundry-operations` `Logs.getRecent()`.

-----

### 1.5 Service Layer Design

#### `StagingService.java`

Responsibilities:
- Ephemeral app lifecycle (create → stage → track)
- Source bundle upload (base64 decode, multipart upload)
- Build polling with configurable interval and timeout
- Staging log extraction from build response
- Cleanup strategy: mark apps for deletion after droplet TTL (Phase 2)

Key reactive chain:
```
createApp() → uploadPackage() → createBuild() → pollBuild() → extractDroplet()
```

All operations return `Mono<T>` — no blocking.

#### `TaskService.java`

Responsibilities:
- Set current droplet on app
- Create and poll tasks
- Resource limit enforcement (clamp to configured max)
- Environment variable injection
- Task state tracking

Key reactive chain:
```
setCurrentDroplet() → createTask() → pollTask()
```

#### `LogService.java`

Responsibilities:
- Loggregator Log Cache integration
- Log filtering (source type, time window)
- Structured log entry creation
- Pagination / truncation

-----

### 1.6 Manifest & Deployment

#### `manifest.yml`

```yaml
applications:
  - name: dropship-mcp
    memory: 512M
    disk_quota: 1G
    instances: 1
    path: target/dropship-mcp-server.jar
    buildpack: java_buildpack
    env:
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
      SPRING_PROFILES_ACTIVE: cloud
      DROPSHIP_SANDBOX_ORG: ai-workloads
      DROPSHIP_SANDBOX_SPACE: agent-sandbox
    services:
      - dropship-uaa-credentials
```

-----

### 1.7 Testing Strategy

#### Unit Tests (Phase 1 scope)

Each tool class gets a unit test that mocks the service layer:

- `StageCodeToolTest` — verify base64 decode, parameter validation, service delegation, error mapping
- `RunTaskToolTest` — verify resource clamping, service delegation, timeout enforcement
- `GetTaskLogToolTest` — verify log filtering, truncation, source filtering

Each service class gets a unit test that mocks cf-java-client:

- `StagingServiceTest` — mock CF API responses for app create, package upload, build lifecycle
- `TaskServiceTest` — mock CF API responses for task create, polling, state transitions
- `LogServiceTest` — mock Loggregator responses, verify filtering/sorting

#### Integration Tests (Phase 1 scope)

- `DropshipIntegrationTest` — end-to-end against real CF foundation
  - Stage a simple Java project (Hello World)
  - Run a command against the droplet
  - Retrieve logs
  - Verify audit trail

**Test CF target:** Real foundation (user confirmed). Tests gated behind `@ActiveProfiles("integration")` or Maven profile `-Pintegration`.

-----

### 1.8 Implementation Order

| Step | What | Depends On | Estimated Complexity |
|------|------|------------|---------------------|
| 1 | `pom.xml` + `DropshipApplication.java` + `application.yml` | Nothing | Low |
| 2 | `DropshipProperties.java` + `CloudFoundryConfig.java` | Step 1 | Low |
| 3 | `StagingService.java` (CF API integration) | Step 2 | High |
| 4 | `StageCodeTool.java` + `StagingResult.java` | Step 3 | Medium |
| 5 | `TaskService.java` (CF Task API integration) | Step 2 | High |
| 6 | `RunTaskTool.java` + `TaskResult.java` | Step 5 | Medium |
| 7 | `LogService.java` (Loggregator integration) | Step 2 | Medium |
| 8 | `GetTaskLogsTool.java` + `TaskLogs.java` | Step 7 | Medium |
| 9 | Unit tests for all tools + services | Steps 4, 6, 8 | Medium |
| 10 | `manifest.yml` + CF deployment config | Step 1 | Low |
| 11 | Integration tests against real CF | Steps 4, 6, 8, 10 | High |
| 12 | Verify end-to-end: Claude Code → Dropship → CF | Step 11 | Medium |

### 1.9 CF Foundation Prerequisites

Before running Dropship, the target CF foundation needs:

1. **UAA client** — `dropship-mcp` client with `client_credentials` grant
   ```bash
   uaac client add dropship-mcp \
     --authorities "cloud_controller.read,cloud_controller.write,cloud_controller.admin_read_only" \
     --authorized_grant_types "client_credentials" \
     --secret <secret>
   ```

2. **Org/Space** — `ai-workloads` org with `agent-sandbox` space
   ```bash
   cf create-org ai-workloads
   cf create-space agent-sandbox -o ai-workloads
   ```

3. **Space role** — Dropship client as SpaceDeveloper
   ```bash
   cf set-space-role dropship-mcp ai-workloads agent-sandbox SpaceDeveloper
   ```

4. **Quotas** — Appropriate org/space quotas for sandbox execution
   ```bash
   cf create-space-quota sandbox-quota \
     -m 8G -r 50 -s 10 --allow-paid-service-plans \
     -i 2G --reserved-route-ports 0
   cf set-space-quota agent-sandbox sandbox-quota
   ```

5. **ASGs** — Network security groups for sandbox space (restrict egress as needed)

-----

## Open Questions for Phase 1

1. **Ephemeral app cleanup** — Phase 1: leave apps (mark for manual cleanup). Phase 2: automated TTL-based cleanup. Sound right?

2. **Buildpack auto-detection** — Should `stage_code` require a buildpack hint, or should we allow CF auto-detection? (Auto-detection is slower but more user-friendly for agents.)

3. **Log Cache vs. `cf logs --recent`** — Log Cache is the modern approach but requires direct Loggregator access. The `cloudfoundry-operations` library's `Logs.getRecent()` is simpler but less structured. Start with `Logs.getRecent()` for MVP, upgrade to Log Cache in Phase 2?

4. **Source bundle format** — Base64-encoded tarball works, but large codebases may exceed MCP message size limits. Should we consider a two-step upload (presigned URL → upload → stage)?

-----

*Next step: Start building Step 1 → pom.xml, application class, and configuration.*
