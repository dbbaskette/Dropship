# Deploying Dropship to Cloud Foundry

Quick-reference guide for pushing Dropship to a CF foundation.
For full foundation setup (UAA client, org/space creation, quotas, ASGs),
see [cf-setup.md](cf-setup.md).

---

## Prerequisites

- `cf` CLI v8+ logged in and targeting the correct org/space
- UAA client created per [cf-setup.md](cf-setup.md)
- Java 21 and Maven installed (for building the JAR)

---

## 1. Build the JAR

```bash
mvn clean package -DskipTests
```

Verify the artifact exists:

```bash
ls target/dropship-0.1.0-SNAPSHOT.jar
```

The `manifest.yml` `path:` field references this exact file.

---

## 2. Set Required Environment Variables

Dropship requires five environment variables at runtime. There are two ways
to provide them: a **vars file** (recommended) or **`cf set-env`**.

### Required Variables

| Variable | Description | Example |
|---|---|---|
| `CF_API_URL` | CF API endpoint | `https://api.sys.example.com` |
| `CF_CLIENT_ID` | UAA client ID | `dropship-client` |
| `CF_CLIENT_SECRET` | UAA client secret | *(secret)* |
| `DROPSHIP_SANDBOX_ORG` | Org where agent workloads run | `ai-workloads` |
| `DROPSHIP_SANDBOX_SPACE` | Space within the org | `agent-sandbox` |

### Option A: Vars File (Recommended)

Copy the example vars file and fill in your values:

```bash
cp vars.yml.example vars.yml
# Edit vars.yml with real values for your foundation
```

Then push with:

```bash
cf push -f manifest.yml --vars-file vars.yml
```

The `manifest.yml` uses `((...))` placeholders that are resolved from the
vars file at push time. This keeps secrets out of the manifest.

### Option B: `cf set-env` (For Existing Apps)

If the app already exists in CF, set each variable individually:

```bash
cf set-env dropship-mcp CF_API_URL      "https://api.sys.YOUR-DOMAIN"
cf set-env dropship-mcp CF_CLIENT_ID    "dropship-client"
cf set-env dropship-mcp CF_CLIENT_SECRET "YOUR_CLIENT_SECRET"
cf set-env dropship-mcp DROPSHIP_SANDBOX_ORG  "ai-workloads"
cf set-env dropship-mcp DROPSHIP_SANDBOX_SPACE "agent-sandbox"
```

Then restage:

```bash
cf restage dropship-mcp
```

> **Security note:** `CF_CLIENT_ID` and `CF_CLIENT_SECRET` are sensitive.
> Never commit them to `manifest.yml` or version control. Use a vars file
> (added to `.gitignore`) or `cf set-env`.

---

## 3. Push

### Using the smoke-test script (recommended)

The smoke-test script runs preflight checks, pushes the app, and then
validates health, MCP protocol, and CF connectivity:

```bash
# With a vars file
CF_VARS_FILE=vars.yml ./scripts/smoke-test.sh

# Re-test without pushing again
./scripts/smoke-test.sh --skip-push
```

### Manual push

```bash
cf push -f manifest.yml --vars-file vars.yml
```

Or, if env vars are already set via `cf set-env`:

```bash
cf push -f manifest.yml
```

---

## 4. Verify Deployment

```bash
# Check app status
cf app dropship-mcp

# Health check
curl https://dropship-mcp.<apps-domain>/actuator/health

# Check logs for successful CF connectivity
cf logs dropship-mcp --recent | grep "Resolved space GUID"
```

A successful startup shows:

```
Resolved space GUID: <guid> for org=ai-workloads, space=agent-sandbox
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Organization not found` at startup | `DROPSHIP_SANDBOX_ORG` doesn't match a CF org | Verify with `cf orgs` and correct the value |
| `Space not found` at startup | `DROPSHIP_SANDBOX_SPACE` doesn't match a space in the org | Verify with `cf spaces` and correct the value |
| App starts but health returns DOWN | SpaceResolver failed — org/space mismatch or bad credentials | Check `cf logs dropship-mcp --recent` for the root cause |
| `cf push` fails with unresolved placeholders | Missing `--vars-file` flag or vars file has placeholder values | Provide a complete vars file or use `cf set-env` |
| `401 Unauthorized` in logs | `CF_CLIENT_ID` or `CF_CLIENT_SECRET` is wrong | Verify credentials with `uaac token client get` |
