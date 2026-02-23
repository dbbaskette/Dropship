# Dropship MCP Server

**Enterprise-governed code execution for AI agents**

-----

## Project Identity

|Field         |Value                                                      |
|--------------|-----------------------------------------------------------|
|**Name**      |Dropship                                                   |
|**Repository**|`dropship-mcp-server`                                      |
|**Tagline**   |Enterprise-governed code execution for AI agents           |
|**Author**    |Dan Baskette ([@dbbaskette](https://github.com/dbbaskette))|
|**Stack**     |Spring Boot 3.x, Spring AI 1.1.0 GA, Java 21               |
|**Transport** |Streamable HTTP (primary), STDIO (local dev)               |
|**License**   |Apache 2.0                                                 |

### Name Rationale

"Dropship" plays on Cloud Foundry's core primitives: `cf push` ships code to the platform, staging produces a **droplet** (the compiled artifact), and Diego **ships** that droplet to a Cell for execution. The military connotation — a vehicle that delivers assets to a target zone with precision — maps perfectly to the project's purpose: deliver AI-generated code to an isolated, governed execution zone and return structured results.

-----

## Problem Statement

AI coding agents (Claude Code, Cursor, Windsurf, Copilot) execute code in one of two places, and neither satisfies enterprise requirements:

**1. Developer's local machine.** The agent runs with the developer's full shell permissions. No centralized audit trail. No resource governance. No credential isolation. If a prompt injection attack exfiltrates `~/.ssh` or `~/.aws`, there's no platform-level control to prevent it.

**2. Vendor cloud sandbox.** Anthropic's Claude Code web sandbox uses isolated VMs with credential proxying and audit logging — a major improvement. But code leaves the enterprise perimeter. Data residency requirements (HIPAA, SOC 2, FedRAMP) may prohibit this. The organization has no control over the execution environment's network policies, resource limits, or service bindings.

**The gap:** There is no standard mechanism for AI agents to execute code inside an enterprise-governed platform where identity, authorization, isolation, audit, network policy, credential management, and cost attribution are all platform-enforced rather than agent-configured.

### What Exists Today

|Project                             |What It Does                                                                                         |What It Doesn't Do                                                                              |
|------------------------------------|-----------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
|**Corby Page's `cloud-foundry-mcp`**|Management plane — lists apps, scales instances, manages routes via Spring AI MCP Server Boot Starter|Execute AI-generated code in sandboxed containers                                               |
|**`claude-code-container-mcp`**     |Runs Claude Code inside Docker containers for isolation                                              |Provide enterprise RBAC, audit events, org/space quotas, ASG network policies, or UAA federation|
|**Anthropic's built-in sandbox**    |bubblewrap/seatbelt OS-level isolation, 84% reduction in permission prompts                          |Run inside the enterprise perimeter or integrate with enterprise identity/governance            |
|**Claude Code on web**              |Isolated VMs with git credential proxying and audit logging                                          |Keep code on-premises; no CF-style multi-tenancy, quotas, or service bindings                   |

### The Distinction

Corby Page's server: **"Talk to CF about your apps."**
Dropship: **"Use CF to safely RUN the code your AI agents write."**

Management plane vs. execution plane. Complementary, not competitive.

-----

## Architecture

### Core Concept

Dropship is a Spring Boot MCP Server that exposes Cloud Foundry's staging pipeline, Diego Task execution, and Loggregator log retrieval as MCP tools. Any MCP-compatible client — Claude Code, Cursor, Windsurf, a Worldmind Centurion, or a custom agent — can use these tools to compile, execute, and observe code in enterprise-governed containers without any CF knowledge.

### System Context

```
┌─────────────────────────────────────────────────────┐
│                   MCP Clients                       │
│  Claude Code │ Cursor │ Windsurf │ Worldmind Agent  │
└──────────┬──────────────────────────────────────────┘
           │  MCP (Streamable HTTP)
           ▼
┌─────────────────────────────────────────────────────┐
│              DROPSHIP MCP SERVER                    │
│         (Spring Boot 3.x / Spring AI 1.1.0)        │
│                                                     │
│  ┌─────────────┐ ┌────────────┐ ┌───────────────┐  │
│  │ stage_code  │ │ run_task   │ │ get_task_logs │  │
│  └──────┬──────┘ └─────┬──────┘ └──────┬────────┘  │
│         │              │               │            │
│         ▼              ▼               ▼            │
│  ┌─────────────────────────────────────────────┐    │
│  │         CF Java Client (cf-java-client)     │    │
│  └──────────────────┬──────────────────────────┘    │
└─────────────────────┼───────────────────────────────┘
                      │  CF API v3 (authenticated via UAA)
                      ▼
┌─────────────────────────────────────────────────────┐
│              CLOUD FOUNDRY FOUNDATION               │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │   UAA    │  │  CAPI v3 │  │   Loggregator    │  │
│  │ (Identity│  │ (Apps,   │  │ (Structured Logs)│  │
│  │  + Auth) │  │  Tasks,  │  │                  │  │
│  │          │  │  Builds) │  │                  │  │
│  └──────────┘  └────┬─────┘  └──────────────────┘  │
│                     │                               │
│  ┌──────────────────▼──────────────────────────┐    │
│  │               DIEGO                         │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  │    │
│  │  │Auctioneer│  │   BBS    │  │   Cells  │  │    │
│  │  │(schedule)│  │(converge)│  │(execute) │  │    │
│  │  └──────────┘  └──────────┘  └────┬─────┘  │    │
│  │                                   │        │    │
│  │  ┌────────────────────────────────▼─────┐  │    │
│  │  │           GARDEN CONTAINERS          │  │    │
│  │  │  (namespaces, cgroups, seccomp,      │  │    │
│  │  │   ASG network policies enforced)     │  │    │
│  │  └──────────────────────────────────────┘  │    │
│  └─────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

### Authentication Model

Dropship authenticates to CF via **UAA client credentials** (not a developer's personal token). The client credential is scoped to designated sandbox spaces with limited quotas. This means:

- Every task execution maps to an auditable identity
- The Dropship service account has exactly the permissions it needs — no more
- Developer tokens are never exposed to AI agents
- RBAC at the org/space level controls what code can access (junior devs: sandbox only; senior devs: staging access; nobody: production)

-----

## Tool Surface

### Tool 1: `stage_code`

**Purpose:** Validate AI-generated code compiles and its dependencies resolve before any execution occurs.

**CF Mechanics:** Creates an ephemeral app, uploads source bundle to blobstore, triggers a build via the buildpack pipeline. Diego schedules a staging Task, the Builder compiles via the appropriate buildpack, and produces a droplet (the immutable compiled artifact).

```java
@McpTool(
    name = "stage_code",
    description = "Stage source code through Cloud Foundry's buildpack pipeline. "
        + "Validates compilation and dependency resolution in an isolated environment. "
        + "Returns a droplet GUID for subsequent execution via run_task."
)
public StagingResult stageCode(
    @McpParam(description = "Base64-encoded source bundle (tarball or zip)")
    String sourceBundle,
    @McpParam(description = "Buildpack hint: java_buildpack, nodejs_buildpack, python_buildpack, go_buildpack, etc.")
    String buildpack,
    @McpParam(description = "Memory limit in MB for staging (default: 1024)")
    Integer memoryMb,
    @McpParam(description = "Disk limit in MB for staging (default: 2048)")
    Integer diskMb
)
```

**Returns:** `StagingResult` containing droplet GUID, staging logs (compilation output, dependency resolution), build duration, and detected buildpack version.

**Why This Matters:** Buildpack-aware staging catches dependency issues early. A Maven dependency version conflict surfaces in staging output as a compilation error, not at runtime as a `ClassNotFoundException`. The agent can fix the dependency and re-stage before wasting a full execution cycle.

### Tool 2: `run_task`

**Purpose:** Execute a command inside an isolated Garden container provisioned from a staged droplet.

**CF Mechanics:** POST to `/v3/apps/:guid/tasks` with command, resource limits, and timeout. The Auctioneer schedules the Task to a Cell with the correct stack, available resources, and balanced load. Garden creates an isolated container (Linux namespaces, cgroups, seccomp profiles). The task runs to completion with a deterministic exit code.

```java
@McpTool(
    name = "run_task",
    description = "Execute a command in an isolated Diego Cell container. "
        + "The container is provisioned from a previously staged droplet, "
        + "enforcing org/space quotas, ASG network policies, and Garden isolation. "
        + "Returns exit code and task GUID for log retrieval."
)
public TaskResult runTask(
    @McpParam(description = "Droplet GUID from a previous stage_code call")
    String dropletGuid,
    @McpParam(description = "Command to execute (e.g., 'mvn test', 'pytest', 'npm test')")
    String command,
    @McpParam(description = "Memory limit in MB (default: 512)")
    Integer memoryMb,
    @McpParam(description = "Timeout in seconds (default: 300, max: 900)")
    Integer timeoutSeconds,
    @McpParam(description = "Environment variables as key=value pairs")
    Map<String, String> environment
)
```

**Returns:** `TaskResult` containing task GUID, exit code, execution duration, Cell ID, and resource consumption metrics.

**Enterprise Differentiators vs. Local Docker:**

|Concern              |Local Docker                    |Dropship (CF)                                                  |
|---------------------|--------------------------------|---------------------------------------------------------------|
|Identity/auth        |Docker daemon (root-equivalent) |UAA-federated RBAC                                             |
|Resource governance  |Manual `--memory` flags         |Org/space quotas enforced platform-wide                        |
|Network isolation    |Docker networks, manual iptables|Application Security Groups (ASGs), platform-managed           |
|Audit trail          |Docker daemon logs if configured|CF Audit Events → SIEM pipeline                                |
|Credential management|Mounted `.env` or Docker secrets|Service bindings via VCAP_SERVICES (agent never sees raw creds)|
|Multi-tenancy        |Separate daemons per team       |Org → Space → App RBAC hierarchy                               |
|Scaling              |Manual Swarm/Compose            |Auctioneer distributes across 250+ Cells                       |
|Self-healing         |Docker restart policies         |BBS convergence + Cell evacuation                              |
|Compliance           |"Trust me, it's in Docker"      |SOC 2-auditable CF Audit Events                                |

Docker isolates a container from the host. CF isolates a container from the host AND governs it within an enterprise policy framework. When the CISO asks "who ran what, where, with access to what data, and what did it cost?" — CF has the answer. Docker doesn't.

### Tool 3: `get_task_logs`

**Purpose:** Retrieve structured stdout/stderr from a completed (or running) task.

**CF Mechanics:** Queries Loggregator's Log Cache API with the app GUID and task time window. Returns structured protobuf envelopes with source type, timestamp, and payload. Stdout and stderr are separated. Retention is configurable. The firehose integration means logs can flow to any drain (Splunk, ELK, Datadog) independently of Dropship's retrieval.

```java
@McpTool(
    name = "get_task_logs",
    description = "Retrieve structured stdout and stderr logs from a task execution. "
        + "Logs are sourced from Loggregator with platform/app log separation, "
        + "structured metadata, and retention independent of container lifecycle."
)
public TaskLogs getTaskLogs(
    @McpParam(description = "Task GUID from a previous run_task call")
    String taskGuid,
    @McpParam(description = "Maximum number of log lines to return (default: 500)")
    Integer maxLines,
    @McpParam(description = "Filter by source: 'stdout', 'stderr', or 'all' (default: 'all')")
    String source
)
```

**Returns:** `TaskLogs` containing ordered log entries (timestamp, source, message), total line count, and whether logs were truncated.

**Why Loggregator > Docker Logs:** Structured metadata (not raw text), platform vs. app log separation, retention independent of container lifecycle (container is gone, logs persist), and firehose integration for enterprise observability pipelines.

-----

## Execution Flow

A typical agent interaction follows this three-phase pattern:

```
Agent                    Dropship                 Cloud Foundry
  │                         │                         │
  │  stage_code(source,     │                         │
  │    "java_buildpack")    │                         │
  │────────────────────────>│                         │
  │                         │  POST /v3/apps          │
  │                         │  Upload source bundle   │
  │                         │  POST /v3/builds        │
  │                         │────────────────────────>│
  │                         │                         │  Diego stages
  │                         │                         │  Buildpack compiles
  │                         │                         │  Produces droplet
  │                         │<────────────────────────│
  │  StagingResult          │                         │
  │  {dropletGuid, logs}    │                         │
  │<────────────────────────│                         │
  │                         │                         │
  │  [Agent inspects staging logs for errors]         │
  │  [If errors: fix source, re-stage]                │
  │                         │                         │
  │  run_task(dropletGuid,  │                         │
  │    "mvn test", 512MB)   │                         │
  │────────────────────────>│                         │
  │                         │  POST /v3/apps/:id/tasks│
  │                         │────────────────────────>│
  │                         │                         │  Auctioneer assigns Cell
  │                         │                         │  Garden container starts
  │                         │                         │  Command executes
  │                         │                         │  Container destroyed
  │                         │<────────────────────────│
  │  TaskResult             │                         │
  │  {taskGuid, exitCode}   │                         │
  │<────────────────────────│                         │
  │                         │                         │
  │  get_task_logs(taskGuid)│                         │
  │────────────────────────>│                         │
  │                         │  GET Log Cache API      │
  │                         │────────────────────────>│
  │                         │<────────────────────────│
  │  TaskLogs               │                         │
  │  {stdout, stderr}       │                         │
  │<────────────────────────│                         │
  │                         │                         │
  │  [Agent analyzes test results]                    │
  │  [If failures: fix code, re-stage, re-run]        │
```

Each `run_task` gets a **fresh container from the droplet**. No state leaks between executions. This eliminates flaky-test-from-dirty-environment problems that plague local development.

-----

## What This Enables

### Parallel Test Execution at Platform Scale

An agent can fire 20 `run_task` calls simultaneously. Diego's Auctioneer distributes them across available Cells. Wall-clock time collapses from serial to parallel. This is impractical with local Docker (limited CPU cores). A CF foundation with hundreds of Cells absorbs this trivially.

### Immutable Execution Environments

Every `run_task` gets a fresh container from the droplet. No state leaks. No "works on my machine" because the agent's CI environment is identical to every other execution. Buildpack version, JDK version, OS packages — all deterministic.

### Credential Isolation via Service Bindings

If tests need a database, the CF space has a service binding. Credentials are injected via `VCAP_SERVICES` at container start. The agent never sees raw credentials in its context window. Even if a prompt injection attack tries to exfiltrate environment variables, the credentials exist only inside the ephemeral container — not in the agent's prompt history.

### Cost Attribution to Agent Missions

CF Task metadata (org, space, app, task GUID) combined with Diego Cell metrics enable precise cost attribution: "Code review of PR #847 consumed 2.3 Cell-hours across 14 Tasks in the `ai-sandbox` space." Finance gets a line item. Platform engineering gets capacity planning data.

### Cross-Space Integration Testing

An agent in the `development` space can call `run_task` against an app in the `staging` space (with RBAC). Integration tests run against real staging services without deploying to staging. RBAC controls which agents can touch which spaces.

-----

## Deployment Model

Dropship itself runs as a long-running CF app (LRP) within the foundation:

```bash
# Deploy Dropship to CF
cf push dropship-mcp -p target/dropship-mcp-server.jar \
  -m 512M \
  --no-route  # Internal route only, or configure with specific route

# Bind to UAA for client credentials
cf bind-service dropship-mcp uaa-client-credentials

# Set sandbox space configuration
cf set-env dropship-mcp DROPSHIP_SANDBOX_ORG ai-workloads
cf set-env dropship-mcp DROPSHIP_SANDBOX_SPACE agent-sandbox
cf set-env dropship-mcp DROPSHIP_MAX_TASK_MEMORY_MB 2048
cf set-env dropship-mcp DROPSHIP_MAX_TASK_TIMEOUT_SECONDS 900
```

### Client Configuration

**Claude Code (managed-mcp.json for org-wide deployment):**

```json
{
  "dropship": {
    "type": "http",
    "url": "https://dropship-mcp.apps.internal:8080/mcp"
  }
}
```

**Cursor / Windsurf (.cursor/mcp.json):**

```json
{
  "mcpServers": {
    "dropship": {
      "url": "https://dropship-mcp.your-cf-domain.com/mcp"
    }
  }
}
```

**Worldmind Centurion (programmatic via Spring AI MCP Client):**

```java
McpClient dropship = McpClient.builder()
    .transportUrl("https://dropship-mcp.apps.internal:8080/mcp")
    .build();
```

-----

## Enterprise Security Story

|Layer             |Mechanism                                                           |What It Prevents                                             |
|------------------|--------------------------------------------------------------------|-------------------------------------------------------------|
|**Identity**      |UAA client credentials, federated to enterprise IdP (LDAP/SAML/OIDC)|Anonymous or unattributed execution                          |
|**Authorization** |RBAC at org/space level                                             |Unauthorized access to production services or sensitive data |
|**Isolation**     |Garden containers (namespaces, cgroups, seccomp)                    |Container escape, host compromise                            |
|**Network**       |Application Security Groups (ASGs)                                  |Lateral movement, data exfiltration to unauthorized endpoints|
|**Audit**         |CF Audit Events → Splunk/ELK/SIEM                                   |Undetected execution, compliance gaps                        |
|**Cost Control**  |Org/space quotas cap memory and task count                          |Runaway agent consuming unbounded resources                  |
|**Data Residency**|Code never leaves the CF foundation                                 |Regulatory violations (HIPAA, SOC 2, FedRAMP)                |
|**Credentials**   |Service bindings inject via VCAP_SERVICES                           |Agent exfiltrating database passwords, API keys, or secrets  |

-----

## Ecosystem Positioning

```
┌──────────────────────────────────────────────────────────────┐
│                    AI Agent Ecosystem                        │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              MCP Clients (Consumers)                   │  │
│  │  Claude Code  │  Cursor  │  Windsurf  │  Custom Agent  │  │
│  └───────┬────────────┬──────────┬──────────────┬────────┘  │
│          │            │          │              │            │
│          ▼            ▼          ▼              ▼            │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              MCP Servers (Tools)                       │  │
│  │                                                        │  │
│  │  ┌──────────────────┐  ┌─────────────────────────┐    │  │
│  │  │ MANAGEMENT PLANE │  │    EXECUTION PLANE      │    │  │
│  │  │                  │  │                         │    │  │
│  │  │ cloud-foundry-   │  │  ┌─────────────────┐   │    │  │
│  │  │ mcp (Corby Page) │  │  │    DROPSHIP     │   │    │  │
│  │  │ "Manage CF apps" │  │  │  "Execute code  │   │    │  │
│  │  │                  │  │  │   safely in CF"  │   │    │  │
│  │  │ github-mcp       │  │  └─────────────────┘   │    │  │
│  │  │ "Manage repos"   │  │                         │    │  │
│  │  │                  │  │  claude-code-container-  │    │  │
│  │  │ jira-mcp         │  │  mcp (Docker-based)     │    │  │
│  │  │ "Manage issues"  │  │  "Execute in Docker"    │    │  │
│  │  └──────────────────┘  └─────────────────────────┘    │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘

Dropship occupies a unique position: the only MCP server that provides
enterprise-governed code execution through an established PaaS platform.
```

### Differentiation Summary

|Dimension            |Docker sandbox           |Anthropic cloud sandbox |Dropship                               |
|---------------------|-------------------------|------------------------|---------------------------------------|
|Execution location   |Developer machine        |Anthropic infrastructure|Enterprise CF foundation               |
|Identity federation  |None                     |Anthropic account       |UAA → enterprise IdP                   |
|Multi-tenancy        |Manual                   |Per-user VMs            |Org → Space → App RBAC                 |
|Network governance   |Manual iptables          |Anthropic-managed       |Platform ASGs                          |
|Audit pipeline       |DIY                      |Anthropic logs          |CF Audit Events → SIEM                 |
|Cost attribution     |None                     |Per-seat pricing        |Per-task, per-space metering           |
|Data residency       |Local disk               |Anthropic cloud         |On-premises CF foundation              |
|Credential management|Env files, Docker secrets|Git credential proxy    |Service bindings (VCAP_SERVICES)       |
|Buildpack compilation|Not applicable           |Not applicable          |Catches dependency errors pre-execution|

-----

## Worldmind Integration (Optional Enhancement)

If integrated with Worldmind, Dropship tools become part of each Centurion's Nova Force toolkit:

|Mission Phase        |Dropship Tool                              |What It Does                                                                                         |
|---------------------|-------------------------------------------|-----------------------------------------------------------------------------------------------------|
|**Forge** (implement)|`stage_code` → `run_task` → `get_task_logs`|Validate compilation, run unit tests, iterate TAOR loop without round-trip to Worldmind control plane|
|**Gauntlet** (test)  |`run_task` (parallel)                      |Full test suite across multiple Diego Cells simultaneously, parse structured logs                    |
|**Vigil** (verify)   |`stage_code` → `run_task`                  |Verify compilation, run static analysis tools (SpotBugs, PMD, Checkstyle)                            |

This creates **two-level isolation**: the Centurion runs in its own Diego Cell. When validating or testing code, it spawns additional ephemeral containers via Dropship. The Centurion's workspace is never polluted by test side effects.

-----

## Implementation Roadmap

### Phase 1: Foundation (MVP)

- [ ] Spring Boot project scaffold with Spring AI 1.1.0 MCP Server
- [ ] `cf-java-client` integration for CF API v3
- [ ] UAA client credential authentication
- [ ] `stage_code` tool — upload source, trigger build, return droplet GUID + staging logs
- [ ] `run_task` tool — execute command against droplet, return exit code + task GUID
- [ ] `get_task_logs` tool — retrieve structured stdout/stderr from Loggregator
- [ ] Configuration: sandbox org/space, memory limits, timeout caps
- [ ] Integration test suite against a local CF (cf-deployment on BOSH-Lite or korifi)

### Phase 2: Hardening

- [ ] Rate limiting per client identity
- [ ] Task queue management (prevent agent from overwhelming Diego with concurrent tasks)
- [ ] Droplet caching — reuse droplet if source hash matches (skip redundant staging)
- [ ] Health check endpoint and CF-native readiness probes
- [ ] Metrics exposure via Micrometer → Prometheus
- [ ] Error taxonomy: distinguish staging failures, task failures, platform failures, timeout

### Phase 3: Enterprise Features

- [ ] Multi-space support with RBAC enforcement (agent scoped to permitted spaces)
- [ ] Service binding passthrough (tests can access bound databases, message queues)
- [ ] Cost attribution API: task metadata → resource consumption → chargeback data
- [ ] Audit event enrichment: tag CF Audit Events with agent identity and mission context
- [ ] Managed-mcp.json template for org-wide Claude Code deployment

### Phase 4: Worldmind Integration

- [ ] Centurion Nova Force toolkit adapter
- [ ] Parallel test execution orchestration
- [ ] Structured log parsing for test result extraction (JUnit XML, pytest JSON)
- [ ] Mission-level cost aggregation

-----

## Project Structure (Proposed)

```
dropship-mcp-server/
├── README.md
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/baskette/dropship/
│   │   │   ├── DropshipApplication.java
│   │   │   ├── config/
│   │   │   │   ├── CloudFoundryConfig.java      # CF client beans, UAA auth
│   │   │   │   └── DropshipProperties.java       # Configurable limits
│   │   │   ├── tools/
│   │   │   │   ├── StageCodeTool.java            # @McpTool stage_code
│   │   │   │   ├── RunTaskTool.java              # @McpTool run_task
│   │   │   │   └── GetTaskLogsTool.java          # @McpTool get_task_logs
│   │   │   ├── model/
│   │   │   │   ├── StagingResult.java
│   │   │   │   ├── TaskResult.java
│   │   │   │   └── TaskLogs.java
│   │   │   └── service/
│   │   │       ├── CloudFoundryService.java      # CF API v3 operations
│   │   │       ├── StagingService.java           # App creation, build lifecycle
│   │   │       ├── TaskService.java              # Task execution, polling
│   │   │       └── LogService.java               # Loggregator retrieval
│   │   └── resources/
│   │       ├── application.yml
│   │       └── application-cloud.yml             # CF-specific profile
│   └── test/
│       └── java/com/baskette/dropship/
│           ├── tools/                            # Tool-level unit tests
│           └── integration/                      # CF integration tests
├── manifest.yml                                  # CF deployment manifest
└── docs/
    ├── architecture.md
    ├── security.md
    └── operations.md
```

-----

## Key Dependencies

|Dependency                                |Version|Purpose                    |
|------------------------------------------|-------|---------------------------|
|`spring-boot-starter`                     |3.4.x  |Application framework      |
|`spring-ai-mcp-server-spring-boot-starter`|1.1.0  |MCP server capabilities    |
|`cloudfoundry-client-reactor`             |5.x    |CF API v3 client (reactive)|
|`cloudfoundry-operations`                 |5.x    |High-level CF operations   |
|`spring-security-oauth2-client`           |6.x    |UAA client credential flow |

-----

## Success Criteria

**MVP is complete when:**

1. A developer can add Dropship to Claude Code's MCP configuration
1. Claude Code can stage a Java or Node.js project via `stage_code` and get compilation feedback
1. Claude Code can run tests via `run_task` and get structured pass/fail results via `get_task_logs`
1. All execution occurs inside CF Garden containers with org/space quota enforcement
1. Every task execution appears in CF Audit Events with the Dropship service account identity

**The pitch to the CISO:**

> "Every line of AI-generated code executes inside Cloud Foundry's enterprise-governed containers. Identity is UAA-federated. Network access is ASG-controlled. Resources are quota-capped. Every execution is audit-logged to your SIEM. Credentials never appear in the AI agent's context. Code never leaves your foundation."

-----

*Dropship: Drop code safely. Ship results back.*
