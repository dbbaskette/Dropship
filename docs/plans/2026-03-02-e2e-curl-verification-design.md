# End-to-End Curl Verification Design

## Goal

Create an automated shell script that verifies the complete `stage_code` → `run_task` → `get_task_logs` MCP tool chain via curl against a deployed Dropship instance.

## Deliverables

1. **`scripts/e2e-curl-test.sh`** — Automated test script
2. **Updated `docs/client-setup.md`** — Add reference to the script

## Script Design

### Flow

1. Accept `DROPSHIP_URL` as env var or argument (required)
2. Initialize MCP session, capture `Mcp-Session-Id` header
3. Send `notifications/initialized`
4. Base64-encode hello-world fixture into source bundle
5. Call `stage_code` → assert `success=true`, extract `dropletGuid`, `appGuid`
6. Call `run_task` → assert `state="SUCCEEDED"`, `exitCode=0`, extract `taskGuid`
7. Call `get_task_logs` → assert log entries contain "Hello, Dropship!"
8. Print summary (pass/fail with timing)
9. Exit 0 on success, 1 on failure

### Dependencies

- `curl`, `jq`, `base64`, `tar` — standard CLI tools
- A running Dropship instance with CF foundation

### Source Bundle Preparation

The hello-world fixture (`src/test/resources/fixtures/hello-world/Main.java`) is Java
source. The existing integration test compiles it and creates a JAR. For the curl test,
we'll create a tar.gz of the fixture directory and base64-encode it. The CF java_buildpack
handles compilation.

### MCP Protocol

All requests use JSON-RPC 2.0 over HTTP POST to `/mcp`:
- `initialize` → get session ID from `Mcp-Session-Id` response header
- `notifications/initialized` → acknowledge session
- `tools/call` with `name` and `arguments` → invoke each tool

### Assertions

Simple `jq` extractions with bash conditionals. The script fails fast on first
assertion failure with clear error messages.

### Output

The script logs each curl command as it executes (with `set -x` or explicit echo),
making the raw commands directly capturable for documentation.
