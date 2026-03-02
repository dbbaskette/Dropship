# Dropship

**Enterprise-governed code execution for AI agents via Cloud Foundry**

Dropship is a [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server that lets AI agents — Claude Code, Cursor, Windsurf, or any MCP client — compile, execute, and observe code inside enterprise-managed Cloud Foundry containers. Every execution runs with UAA-federated identity, org/space quota enforcement, ASG network policies, and full CF Audit Event trails.

---

## The Problem

AI coding agents execute code in one of two places, and neither satisfies enterprise requirements:

| Approach | Limitation |
|---|---|
| **Developer's local machine** | No centralized audit trail, no resource governance, no credential isolation. A prompt injection can exfiltrate `~/.ssh` or `~/.aws`. |
| **Vendor cloud sandbox** | Code leaves the enterprise perimeter. Data residency (HIPAA, SOC 2, FedRAMP) may prohibit this. No control over network policies or service bindings. |

**Dropship fills the gap:** AI agents execute code inside the organization's Cloud Foundry foundation where identity, authorization, isolation, audit, network policy, and cost attribution are all platform-enforced.

---

## How It Works

Dropship exposes three MCP tools that map to Cloud Foundry primitives:

### `stage_code`

Upload source code and compile it through CF's buildpack pipeline. Catches dependency errors and compilation failures before execution.

```
Agent → stage_code(source, "java_buildpack") → CF creates ephemeral app →
buildpack compiles → produces droplet → returns droplet GUID + staging logs
```

### `run_task`

Execute a command in an isolated Garden container provisioned from a staged droplet. Diego schedules the task to a Cell, Garden enforces namespace/cgroup/seccomp isolation and ASG network policies.

```
Agent → run_task(dropletGuid, "mvn test", 512MB) → Diego creates container →
command executes → container destroyed → returns exit code + task GUID
```

### `get_task_logs`

Retrieve structured stdout/stderr from Loggregator. Logs persist independently of the container lifecycle.

```
Agent → get_task_logs(taskGuid) → Loggregator Log Cache →
returns timestamped, source-separated log entries
```

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   MCP Clients                       │
│  Claude Code │ Cursor │ Windsurf │ Custom Agent     │
└──────────┬──────────────────────────────────────────┘
           │  MCP (Streamable HTTP)
           ▼
┌─────────────────────────────────────────────────────┐
│              DROPSHIP MCP SERVER                    │
│         (Spring Boot 3.4 / Spring AI 1.1.2)        │
│                                                     │
│  ┌─────────────┐ ┌────────────┐ ┌───────────────┐  │
│  │ stage_code  │ │ run_task   │ │ get_task_logs │  │
│  └──────┬──────┘ └─────┬──────┘ └──────┬────────┘  │
│         └──────────────┼───────────────┘            │
│                        ▼                            │
│         CF Java Client (cf-java-client 5.x)         │
└────────────────────────┬────────────────────────────┘
                         │  CF API v3 (UAA authenticated)
                         ▼
┌─────────────────────────────────────────────────────┐
│              CLOUD FOUNDRY FOUNDATION               │
│                                                     │
│  UAA ──── CAPI v3 ──── Loggregator                  │
│             │                                       │
│          DIEGO (Auctioneer → BBS → Cells)           │
│             │                                       │
│     GARDEN CONTAINERS                               │
│     (namespaces, cgroups, seccomp, ASGs)            │
└─────────────────────────────────────────────────────┘
```

---

## Technology Stack

| Component | Artifact | Version |
|---|---|---|
| Spring Boot | `spring-boot-starter-webflux` | 3.4.x |
| Spring AI | `spring-ai-bom` | 1.1.2 |
| MCP Server | `spring-ai-starter-mcp-server-webflux` | 1.1.2 (via BOM) |
| MCP Annotations | `org.springaicommunity:mcp-annotations` | latest |
| CF Client | `cloudfoundry-client-reactor` | 5.14.0.RELEASE |
| CF Operations | `cloudfoundry-operations` | 5.14.0.RELEASE |
| Java | OpenJDK | 21 |

WebFlux is used because `cf-java-client` is Reactor-based. No bridging `block()` calls — fully reactive end-to-end.

---

## Enterprise Security

| Layer | Mechanism | What It Prevents |
|---|---|---|
| **Identity** | UAA client credentials, federated to enterprise IdP | Anonymous/unattributed execution |
| **Authorization** | RBAC at org/space level | Unauthorized access to production |
| **Isolation** | Garden containers (namespaces, cgroups, seccomp) | Container escape, host compromise |
| **Network** | Application Security Groups (ASGs) | Lateral movement, data exfiltration |
| **Audit** | CF Audit Events to SIEM | Undetected execution, compliance gaps |
| **Cost Control** | Org/space quotas | Runaway resource consumption |
| **Data Residency** | Code never leaves the CF foundation | Regulatory violations (HIPAA, SOC 2, FedRAMP) |
| **Credentials** | Service bindings via VCAP_SERVICES | Agent exfiltrating secrets |

---

## Why Not Docker?

| Concern | Docker | Dropship (CF) |
|---|---|---|
| Identity/auth | Docker daemon (root-equivalent) | UAA-federated RBAC |
| Resource governance | Manual `--memory` flags | Org/space quotas enforced platform-wide |
| Network isolation | Manual iptables | Application Security Groups (ASGs) |
| Audit trail | Docker daemon logs (if enabled) | CF Audit Events to SIEM |
| Credential management | Mounted `.env` files | Service bindings (VCAP_SERVICES) |
| Multi-tenancy | Separate daemons | Org / Space / App RBAC hierarchy |
| Scaling | Manual Swarm/Compose | Diego Auctioneer across 250+ Cells |
| Compliance | "Trust me, it's in Docker" | SOC 2-auditable CF Audit Events |

---

## Client Configuration

**Claude Code** (`managed-mcp.json` for org-wide deployment):

```json
{
  "dropship": {
    "type": "http",
    "url": "https://dropship-mcp.apps.internal:8080/mcp"
  }
}
```

**Cursor / Windsurf** (`.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "dropship": {
      "url": "https://dropship-mcp.your-cf-domain.com/mcp"
    }
  }
}
```

---

## Deployment

Dropship runs as a long-running CF app:

```bash
cp vars.yml.example vars.yml
# Edit vars.yml with your CF API URL, UAA credentials, and sandbox org/space
cf push -f manifest.yml --vars-file vars.yml
```

### CF Foundation Prerequisites

Before deploying, the target foundation needs:

1. **UAA client** with `client_credentials` grant scoped to `cloud_controller.read`, `cloud_controller.write`
2. **Org/Space** for sandbox execution (e.g., `ai-workloads` / `agent-sandbox`)
3. **Space quota** with appropriate resource limits
4. **ASGs** restricting sandbox egress as needed

See [`docs/cf-setup.md`](docs/cf-setup.md) for detailed setup instructions (coming in Phase 1).

---

## Roadmap

| Phase | Focus | Status |
|---|---|---|
| **Phase 1: Foundation (MVP)** | Three core tools, CF integration, deployment manifest | In progress |
| **Phase 2: Hardening** | Rate limiting, task queuing, droplet caching, health checks, metrics | Planned |
| **Phase 3: Enterprise** | Multi-space RBAC, service binding passthrough, cost attribution, audit enrichment | Planned |
| **Phase 4: Worldmind** | Centurion toolkit adapter, parallel test orchestration, structured log parsing | Planned |

Phase 1 is tracked as [22 GitHub issues](https://github.com/dbbaskette/Dropship/milestones) across 6 milestones.

---

## Project Structure (Phase 1)

```
src/main/java/com/baskette/dropship/
├── DropshipApplication.java
├── config/
│   ├── CloudFoundryConfig.java       # CF client beans, UAA auth
│   └── DropshipProperties.java       # @ConfigurationProperties
├── tool/
│   ├── StageCodeTool.java            # @McpTool stage_code
│   ├── RunTaskTool.java              # @McpTool run_task
│   └── GetTaskLogsTool.java          # @McpTool get_task_logs
├── model/
│   ├── StagingResult.java
│   ├── TaskResult.java
│   └── TaskLogs.java
└── service/
    ├── StagingService.java           # App creation, build lifecycle
    ├── TaskService.java              # Task execution, polling
    └── LogService.java               # Loggregator retrieval
```

---

## Ecosystem Positioning

Corby Page's [`cloud-foundry-mcp`](https://github.com/corby-page): **"Talk to CF about your apps."** (Management plane)

Dropship: **"Use CF to safely run the code your AI agents write."** (Execution plane)

Complementary, not competitive.

---

## License

[MIT](LICENSE)

---

*Dropship: Drop code safely. Ship results back.*
