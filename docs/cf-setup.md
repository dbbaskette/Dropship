# Cloud Foundry Foundation Setup for Dropship

Step-by-step runbook for preparing a Cloud Foundry foundation to support Dropship.
After completing these steps, Dropship will be able to stage code and run tasks
in an isolated sandbox space using UAA client credentials.

---

## Prerequisites

- `cf` CLI v8+ authenticated as an admin user
- `uaac` CLI (from the `cf-uaac` gem: `gem install cf-uaac`)
- Admin access to the CF foundation's UAA

Target the UAA server before running `uaac` commands:

```bash
uaac target https://uaa.sys.YOUR-DOMAIN --skip-ssl-validation
uaac token client get admin -s <ADMIN_CLIENT_SECRET>
```

---

## 1. Create the UAA Client

Dropship authenticates to the CF API using the `client_credentials` OAuth2 grant.
Create a dedicated UAA client with the minimum authorities required for app staging
and task execution.

```bash
uaac client add dropship-client \
  --name dropship-client \
  --secret <CHOOSE_A_STRONG_SECRET> \
  --authorized_grant_types client_credentials \
  --authorities "cloud_controller.read,cloud_controller.write,cloud_controller.admin" \
  --scope uaa.none
```

### Minimum Authorities

| Authority | Why Dropship Needs It |
|---|---|
| `cloud_controller.read` | List orgs, spaces; poll build and task state |
| `cloud_controller.write` | Create apps, packages, builds, and tasks |
| `cloud_controller.admin` | Operate across orgs/spaces without per-space role assignment |

**Least-privilege alternative:** If `cloud_controller.admin` is too broad for your
policy, omit it and instead assign the client a SpaceDeveloper role in the sandbox
space (see Step 4). In that case the authorities line becomes:

```bash
  --authorities "cloud_controller.read,cloud_controller.write"
```

---

## 2. Create the Org and Space

```bash
cf create-org ai-workloads
cf target -o ai-workloads

cf create-space agent-sandbox -o ai-workloads
```

These names map to the Dropship configuration properties:

| Property | Environment Variable | Value |
|---|---|---|
| `dropship.sandbox-org` | `DROPSHIP_SANDBOX_ORG` | `ai-workloads` |
| `dropship.sandbox-space` | `DROPSHIP_SANDBOX_SPACE` | `agent-sandbox` |
| `dropship.cf-api-url` | `CF_API_URL` | `https://api.sys.YOUR-DOMAIN` |

---

## 3. Create and Assign a Space Quota

Space quotas prevent runaway resource consumption by AI agent workloads.

```bash
cf create-space-quota dropship-sandbox-quota \
  -m 4G \
  -i 2G \
  -r 0 \
  -s 0 \
  --allow-paid-service-plans=false \
  -a -1
```

| Flag | Value | Rationale |
|---|---|---|
| `-m 4G` | 4 GB total memory | Enough for several concurrent tasks (default task = 512 MB) |
| `-i 2G` | 2 GB max per instance | Matches `dropship.max-task-memory-mb` (2048) |
| `-r 0` | 0 routes | Tasks do not serve HTTP traffic; no routes needed |
| `-s 0` | 0 service instances | Sandbox should not bind to databases or brokers by default |
| `--allow-paid-service-plans=false` | No paid plans | Prevent cost surprises |
| `-a -1` | Unlimited app instances | Tasks create ephemeral apps; allow enough headroom |

Assign the quota to the sandbox space:

```bash
cf set-space-quota agent-sandbox dropship-sandbox-quota
```

---

## 4. Assign Space Roles

**If using `cloud_controller.admin` authority (Step 1 default):** No space role
assignment is needed. The admin authority grants full access to all orgs and spaces.

**If using the least-privilege alternative:** Create a dummy user for the client
and assign SpaceDeveloper:

```bash
cf set-space-role dropship-client ai-workloads agent-sandbox SpaceDeveloper --origin client
```

> Note: The `--origin client` flag associates the role with a UAA client rather
> than a UAA user. Requires CF CLI v8.8+. On older CLIs, the client credentials
> grant with `cloud_controller.read` and `cloud_controller.write` authorities plus
> a SpaceDeveloper role is equivalent.

---

## 5. Configure Application Security Groups (ASGs)

Dropship tasks run arbitrary code submitted by AI agents. Lock down network egress
from the sandbox space with a restrictive ASG.

### 5a. Default Deny-All ASG

Create a file named `deny-all.json`:

```json
[]
```

Apply it as the running security group for the sandbox space:

```bash
cf bind-security-group deny-all ai-workloads agent-sandbox --lifecycle running
cf bind-security-group deny-all ai-workloads agent-sandbox --lifecycle staging
```

> An empty rule set blocks all egress. This is the safest starting point.

### 5b. Allow Package Registries (Optional)

If staged code needs to download dependencies during buildpack staging (Maven
Central, npm registry, PyPI, etc.), create `allow-package-registries.json`:

```json
[
  {
    "protocol": "tcp",
    "destination": "0.0.0.0/0",
    "ports": "443",
    "description": "HTTPS egress for package registries during staging"
  }
]
```

```bash
cf create-security-group allow-package-registries allow-package-registries.json
cf bind-security-group allow-package-registries ai-workloads agent-sandbox --lifecycle staging
```

This allows HTTPS egress only during staging (buildpack dependency resolution) while
keeping the running phase (task execution) locked down.

### 5c. Tighter Registry Rules (Recommended for Production)

For tighter control, restrict egress to specific registry IP ranges:

```json
[
  {
    "protocol": "tcp",
    "destination": "151.101.0.0/16",
    "ports": "443",
    "description": "Maven Central (Fastly CDN)"
  },
  {
    "protocol": "tcp",
    "destination": "104.16.0.0/12",
    "ports": "443",
    "description": "npm registry (Cloudflare)"
  }
]
```

> Look up current IP ranges for your required registries. CDN IPs change over time.

---

## 6. Configure Dropship Environment Variables

Set the environment variables that Dropship reads at startup:

```bash
export CF_API_URL="https://api.sys.YOUR-DOMAIN"
export CF_CLIENT_ID="dropship-client"
export CF_CLIENT_SECRET="<THE_SECRET_FROM_STEP_1>"
export CF_SKIP_SSL_VALIDATION="false"
export DROPSHIP_SANDBOX_ORG="ai-workloads"
export DROPSHIP_SANDBOX_SPACE="agent-sandbox"
```

Or, if deploying Dropship as a CF app, set them in `manifest.yml`:

```yaml
applications:
  - name: dropship-mcp
    env:
      CF_API_URL: https://api.sys.YOUR-DOMAIN
      CF_CLIENT_ID: dropship-client
      CF_CLIENT_SECRET: ((dropship-client-secret))
      CF_SKIP_SSL_VALIDATION: "false"
      DROPSHIP_SANDBOX_ORG: ai-workloads
      DROPSHIP_SANDBOX_SPACE: agent-sandbox
```

All available Dropship configuration properties with their defaults:

| Property | Default | Description |
|---|---|---|
| `dropship.max-task-memory-mb` | `2048` | Hard cap on task memory (MB) |
| `dropship.max-task-disk-mb` | `4096` | Hard cap on task disk (MB) |
| `dropship.max-task-timeout-seconds` | `900` | Maximum task duration (15 min) |
| `dropship.default-task-memory-mb` | `512` | Default memory when not specified |
| `dropship.default-staging-memory-mb` | `1024` | Default memory for staging builds |
| `dropship.default-staging-disk-mb` | `2048` | Default disk for staging builds |
| `dropship.app-name-prefix` | `dropship-` | Prefix for ephemeral app names |

---

## 7. Verification

Run these commands to confirm the setup is correct.

### Verify the UAA client exists

```bash
uaac client get dropship-client
```

Expected output includes `authorized_grant_types: client_credentials` and the
authorities from Step 1.

### Verify the org, space, and quota

```bash
cf org ai-workloads
cf space agent-sandbox
cf space-quotas
```

Confirm the `dropship-sandbox-quota` is listed and assigned to `agent-sandbox`.

### Verify ASGs

```bash
cf security-groups
cf security-group deny-all
```

Confirm `deny-all` is bound to `ai-workloads/agent-sandbox` for the `running`
lifecycle.

### Verify the client can authenticate

```bash
uaac token client get dropship-client -s <THE_SECRET>
uaac context
```

The token should show the expected `authorities`.

### End-to-end connectivity test

Start Dropship and check the logs for successful space resolution:

```bash
java -jar dropship.jar
```

Look for:

```
Resolved space GUID: <guid> for org=ai-workloads, space=agent-sandbox
```

If you see `Unable to resolve space GUID`, check that:

1. `CF_API_URL` is reachable from the Dropship host
2. `CF_CLIENT_ID` and `CF_CLIENT_SECRET` are correct
3. The org and space names match exactly (case-sensitive)
4. The client has the required authorities or space role

---

## Quick Reference

```bash
# Full setup in one block (replace placeholders)

uaac target https://uaa.sys.YOUR-DOMAIN --skip-ssl-validation
uaac token client get admin -s <ADMIN_CLIENT_SECRET>

uaac client add dropship-client \
  --name dropship-client \
  --secret <CHOOSE_A_STRONG_SECRET> \
  --authorized_grant_types client_credentials \
  --authorities "cloud_controller.read,cloud_controller.write,cloud_controller.admin" \
  --scope uaa.none

cf create-org ai-workloads
cf create-space agent-sandbox -o ai-workloads

cf create-space-quota dropship-sandbox-quota \
  -m 4G -i 2G -r 0 -s 0 --allow-paid-service-plans=false -a -1
cf set-space-quota agent-sandbox dropship-sandbox-quota

echo '[]' > /tmp/deny-all.json
cf create-security-group deny-all /tmp/deny-all.json
cf bind-security-group deny-all ai-workloads agent-sandbox --lifecycle running
cf bind-security-group deny-all ai-workloads agent-sandbox --lifecycle staging
```
