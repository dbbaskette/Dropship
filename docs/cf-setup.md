# Cloud Foundry Foundation Setup for Dropship

Step-by-step runbook for preparing a Cloud Foundry foundation to support Dropship.
After completing these steps, Dropship will be able to stage code and run tasks
in an isolated sandbox space.

Dropship supports two authentication modes:

| Mode | Environment Variables | OAuth2 Grant Type | When to Use |
|---|---|---|---|
| **Client credentials** | `CF_CLIENT_ID` / `CF_CLIENT_SECRET` | `client_credentials` | Production and service-account deployments. The UAA client authenticates directly — no user account required. |
| **Password grant** | `CF_USERNAME` / `CF_PASSWORD` | `password` | Dev/testing with a personal CF user account. Simpler setup — no UAA admin access needed. |

**Precedence:** If both sets of variables are provided, client credentials wins
(`@Primary`). If neither is set, Dropship starts without CF connectivity.

---

## Prerequisites

- `cf` CLI v8+ authenticated as an admin user
- For client credentials mode: `uaac` CLI (from the `cf-uaac` gem: `gem install cf-uaac`) and admin access to the CF foundation's UAA
- For password grant mode: an existing CF user account with SpaceDeveloper role in the sandbox space

If using client credentials, target the UAA server before running `uaac` commands:

```bash
uaac target https://uaa.sys.YOUR-DOMAIN --skip-ssl-validation
uaac token client get admin -s <ADMIN_CLIENT_SECRET>
```

---

## 1. Authentication Setup

Choose one of the two authentication modes below.

### Option A: Client Credentials (Recommended)

Create a dedicated UAA client that authenticates via the `client_credentials` OAuth2
grant. This is the recommended mode for production and shared deployments because
it uses a service account — no human user is involved.

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

### Option B: Password Grant (Dev/Testing)

If you already have a CF user account with the necessary permissions, you can skip
the UAA client setup entirely and authenticate with your username and password using
the `password` OAuth2 grant.

**Requirements:**

- The user must have the **SpaceDeveloper** role in the sandbox space (see Step 4)
- The CF foundation's UAA must allow password grant for the `cf` client

No `uaac` commands are needed for this option. Simply set the environment variables
in Step 6 (Option B) and ensure your user has the correct space role.

> **When to use:** Local development, personal testing, or environments where you
> do not have UAA admin access to create clients. Not recommended for production
> because it ties Dropship to a personal user account.

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

### Client credentials with `cloud_controller.admin` (Step 1 Option A default)

No space role assignment is needed. The admin authority grants full access to all
orgs and spaces.

### Client credentials — least-privilege alternative

Create a dummy user for the client and assign SpaceDeveloper:

```bash
cf set-space-role dropship-client ai-workloads agent-sandbox SpaceDeveloper --origin client
```

> Note: The `--origin client` flag associates the role with a UAA client rather
> than a UAA user. Requires CF CLI v8.8+. On older CLIs, the client credentials
> grant with `cloud_controller.read` and `cloud_controller.write` authorities plus
> a SpaceDeveloper role is equivalent.

### Password grant (Step 1 Option B)

Assign your CF user the SpaceDeveloper role in the sandbox space:

```bash
cf set-space-role YOUR_USERNAME ai-workloads agent-sandbox SpaceDeveloper
```

This is required — the password grant authenticates as a regular user, so the user
must have explicit roles in the target space.

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

Set the environment variables that Dropship reads at startup. Choose the set that
matches your authentication mode from Step 1.

### Option A: Client Credentials

```bash
export CF_API_URL="https://api.sys.YOUR-DOMAIN"
export CF_CLIENT_ID="dropship-client"
export CF_CLIENT_SECRET="<THE_SECRET_FROM_STEP_1>"
export CF_SKIP_SSL_VALIDATION="false"
export DROPSHIP_SANDBOX_ORG="ai-workloads"
export DROPSHIP_SANDBOX_SPACE="agent-sandbox"
```

### Option B: Password Grant

```bash
export CF_API_URL="https://api.sys.YOUR-DOMAIN"
export CF_USERNAME="your-cf-username"
export CF_PASSWORD="your-cf-password"
export CF_SKIP_SSL_VALIDATION="false"
export DROPSHIP_SANDBOX_ORG="ai-workloads"
export DROPSHIP_SANDBOX_SPACE="agent-sandbox"
```

> Do not set both `CF_CLIENT_ID` and `CF_USERNAME` at the same time. If both are
> present, client credentials takes precedence and the password grant variables are
> ignored.

### Deploying as a CF App

If deploying Dropship as a CF app, set credentials via `manifest.yml` or a vars
file. See `vars.yml.example` for both credential options.

Client credentials example:

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

Password grant example:

```yaml
applications:
  - name: dropship-mcp
    env:
      CF_API_URL: https://api.sys.YOUR-DOMAIN
      CF_USERNAME: ((cf-username))
      CF_PASSWORD: ((cf-password))
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

### Verify credentials

**Client credentials:** Verify the UAA client exists:

```bash
uaac client get dropship-client
```

Expected output includes `authorized_grant_types: client_credentials` and the
authorities from Step 1.

**Password grant:** Verify the user can log in:

```bash
cf auth YOUR_USERNAME YOUR_PASSWORD
```

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
2. Your credentials are correct (`CF_CLIENT_ID`/`CF_CLIENT_SECRET` or `CF_USERNAME`/`CF_PASSWORD`)
3. The org and space names match exactly (case-sensitive)
4. The identity has the required authorities or space role

---

## 8. Deployment Pre-Flight Checklist

Run through this checklist before every `cf push` to catch common deployment issues
early.

### Build

- [ ] `mvn clean package -DskipTests` completes without errors
- [ ] `target/dropship-0.1.0-SNAPSHOT.jar` exists and is a valid Spring Boot fat JAR
- [ ] `manifest.yml` `path:` matches the JAR filename above

### Manifest Environment Variables

Verify the required env vars are set (via manifest, `cf set-env`, or a vars file).
You need **one** of the two credential pairs:

| Variable | Required | Notes |
|---|---|---|
| `CF_CLIENT_ID` | Option A | UAA client ID from Step 1 |
| `CF_CLIENT_SECRET` | Option A | UAA client secret from Step 1 |
| `CF_USERNAME` | Option B | CF user account username |
| `CF_PASSWORD` | Option B | CF user account password |
| `CF_API_URL` | Recommended | Auto-detected from VCAP on CF, but set explicitly as a fallback |
| `DROPSHIP_SANDBOX_ORG` | Yes | Must match an existing CF org |
| `DROPSHIP_SANDBOX_SPACE` | Yes | Must match an existing CF space in the org above |
| `SPRING_PROFILES_ACTIVE` | Yes | Must be `cloud` for CF deployments |
| `CF_SKIP_SSL_VALIDATION` | No | Defaults to `false`; set `true` only for dev foundations with self-signed certs |

### Cloud Foundry Prerequisites

- [ ] Target foundation is reachable: `cf api $CF_API_URL`
- [ ] Credentials can authenticate (one of):
  - Client credentials: `uaac token client get $CF_CLIENT_ID -s $CF_CLIENT_SECRET`
  - Password grant: `cf auth $CF_USERNAME $CF_PASSWORD`
- [ ] Sandbox org exists: `cf org $DROPSHIP_SANDBOX_ORG`
- [ ] Sandbox space exists: `cf space $DROPSHIP_SANDBOX_SPACE`
- [ ] Space quota is assigned (see Step 3)
- [ ] ASGs are configured for the sandbox space (see Step 5)

### Health Check

The manifest uses `health-check-type: http` with endpoint `/actuator/health`.
After pushing, verify:

```bash
cf app dropship-mcp
# status should be "running"

curl https://dropship-mcp.<apps-domain>/actuator/health
# should return {"status":"UP"} or similar
```

If the app starts but the health check fails, check that:
1. The `cloud` profile activated (look for JSON-formatted log lines)
2. CF credentials are valid (look for `Dropship connected to CF` in logs)
3. The `/actuator/health` endpoint is accessible (not blocked by route config)

> **Note:** `CloudFoundryHealthCheck` runs on `ApplicationReadyEvent`. If CF
> credentials are missing or invalid the app will still start but log a warning.
> The HTTP health check at `/actuator/health` will pass regardless, so monitor
> logs after the first push to confirm CF connectivity.

---

## Quick Reference

### Client Credentials Setup

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

### Password Grant Setup

```bash
# Minimal setup for dev/testing (replace placeholders)

cf create-org ai-workloads
cf create-space agent-sandbox -o ai-workloads

cf set-space-role YOUR_USERNAME ai-workloads agent-sandbox SpaceDeveloper

cf create-space-quota dropship-sandbox-quota \
  -m 4G -i 2G -r 0 -s 0 --allow-paid-service-plans=false -a -1
cf set-space-quota agent-sandbox dropship-sandbox-quota

export CF_API_URL="https://api.sys.YOUR-DOMAIN"
export CF_USERNAME="YOUR_USERNAME"
export CF_PASSWORD="YOUR_PASSWORD"
export DROPSHIP_SANDBOX_ORG="ai-workloads"
export DROPSHIP_SANDBOX_SPACE="agent-sandbox"
```
