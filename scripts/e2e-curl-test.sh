#!/usr/bin/env bash
#
# End-to-end curl verification of the Dropship MCP tool chain.
#
# Exercises the complete stage_code -> run_task -> get_task_logs workflow
# against a deployed Dropship instance using raw curl commands.
#
# Prerequisites:
#   - curl, jq, base64, tar
#   - cf CLI logged in (used to look up app name from GUID)
#   - A running Dropship instance with /mcp endpoint accessible
#
# Usage:
#   DROPSHIP_URL=https://dropship-mcp.apps.example.com/mcp ./scripts/e2e-curl-test.sh
#
# Environment variables:
#   DROPSHIP_URL  (required) Full URL to the Dropship /mcp endpoint
#   BUILDPACK     (optional) Buildpack to use, default: java_buildpack

set -euo pipefail

DROPSHIP_URL="${DROPSHIP_URL:?Set DROPSHIP_URL to the Dropship /mcp endpoint}"
BUILDPACK="${BUILDPACK:-java_buildpack}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FIXTURE_DIR="$PROJECT_ROOT/src/test/resources/fixtures/hello-world"

HEADER_FILE=$(mktemp)
trap 'rm -f "$HEADER_FILE"' EXIT

# --- Output helpers ---

pass() { printf '\033[0;32m  PASS: %s\033[0m\n' "$1"; }
fail() { printf '\033[0;31m  FAIL: %s\033[0m\n' "$1"; exit 1; }
info() { printf '\033[0;33m  >> %s\033[0m\n' "$1"; }
step() { printf '\n\033[1;36m=== Step %s: %s ===\033[0m\n' "$1" "$2"; }

# --- Helpers ---

# Extract JSON from a response that may be plain JSON or SSE (text/event-stream).
# SSE responses contain lines like "event: message\ndata: {...}\n\n".
extract_json() {
    local response="$1"
    # Plain JSON
    if printf '%s' "$response" | jq -e . >/dev/null 2>&1; then
        printf '%s' "$response"
        return
    fi
    # SSE: take the last data: line
    local data
    data=$(printf '%s' "$response" | grep '^data:' | tail -1 | sed 's/^data: *//')
    if [ -n "$data" ] && printf '%s' "$data" | jq -e . >/dev/null 2>&1; then
        printf '%s' "$data"
        return
    fi
    fail "Could not parse response as JSON or SSE"
}

# Extract the tool result object from a JSON-RPC tools/call response.
# MCP wraps tool output as: { result: { content: [{ type: "text", text: "..." }] } }
extract_tool_result() {
    local json="$1"
    # Check for JSON-RPC error
    local rpc_error
    rpc_error=$(printf '%s' "$json" | jq -r '.error // empty')
    if [ -n "$rpc_error" ]; then
        fail "JSON-RPC error: $rpc_error"
    fi
    # Standard MCP content wrapper
    local text
    text=$(printf '%s' "$json" | jq -r '.result.content[0].text // empty')
    if [ -n "$text" ] && printf '%s' "$text" | jq -e . >/dev/null 2>&1; then
        printf '%s' "$text"
        return
    fi
    # Fallback: result might be the direct object
    printf '%s' "$json" | jq '.result'
}

# Look up app name from app GUID using cf CLI.
lookup_app_name() {
    local app_guid="$1"
    cf curl "/v3/apps/$app_guid" 2>/dev/null | jq -r '.name'
}

# --- Verify prerequisites ---

for cmd in curl jq base64 tar cf; do
    command -v "$cmd" >/dev/null 2>&1 || fail "Required command not found: $cmd"
done

echo "Dropship E2E Curl Test"
echo "Target: $DROPSHIP_URL"
echo ""

# ============================================================
# Step 1: Initialize MCP session
# ============================================================
step 1 "Initialize MCP session"

info "POST initialize request"
INIT_RAW=$(curl -sS -D "$HEADER_FILE" "$DROPSHIP_URL" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d '{
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {
                "name": "e2e-curl-test",
                "version": "1.0"
            }
        }
    }')

MCP_SESSION=$(grep -i 'mcp-session-id' "$HEADER_FILE" | tr -d '\r' | awk -F': ' '{print $2}')
[ -n "$MCP_SESSION" ] || fail "No Mcp-Session-Id in response headers"
pass "Session ID: $MCP_SESSION"

info "POST notifications/initialized"
curl -sS "$DROPSHIP_URL" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: $MCP_SESSION" \
    -d '{"jsonrpc": "2.0", "method": "notifications/initialized"}' >/dev/null 2>&1 || true
pass "Initialized notification sent"

# ============================================================
# Step 2: Prepare source bundle
# ============================================================
step 2 "Prepare source bundle"

[ -d "$FIXTURE_DIR" ] || fail "Fixture directory not found: $FIXTURE_DIR"

SOURCE_BUNDLE=$(tar czf - -C "$FIXTURE_DIR" . | base64)
BUNDLE_SIZE=$(printf '%s' "$SOURCE_BUNDLE" | wc -c | tr -d ' ')
pass "Source bundle: $BUNDLE_SIZE base64 chars from $FIXTURE_DIR"

# ============================================================
# Step 3: stage_code
# ============================================================
step 3 "stage_code"

info "Calling stage_code (buildpack=$BUILDPACK) — this may take several minutes"

# Build the JSON payload (sourceBundle can be large, so use a temp file)
PAYLOAD_FILE=$(mktemp)
trap 'rm -f "$HEADER_FILE" "$PAYLOAD_FILE"' EXIT

jq -n \
    --arg bundle "$SOURCE_BUNDLE" \
    --arg bp "$BUILDPACK" \
    '{
        jsonrpc: "2.0",
        id: 2,
        method: "tools/call",
        params: {
            name: "stage_code",
            arguments: {
                sourceBundle: $bundle,
                buildpack: $bp
            }
        }
    }' > "$PAYLOAD_FILE"

STAGE_RAW=$(curl -sS "$DROPSHIP_URL" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: $MCP_SESSION" \
    --max-time 420 \
    -d @"$PAYLOAD_FILE")

STAGE_JSON=$(extract_json "$STAGE_RAW")
STAGE_RESULT=$(extract_tool_result "$STAGE_JSON")

SUCCESS=$(printf '%s' "$STAGE_RESULT" | jq -r '.success')
DROPLET_GUID=$(printf '%s' "$STAGE_RESULT" | jq -r '.dropletGuid')
APP_GUID=$(printf '%s' "$STAGE_RESULT" | jq -r '.appGuid')
DURATION_MS=$(printf '%s' "$STAGE_RESULT" | jq -r '.durationMs')

[ "$SUCCESS" = "true" ] || fail "stage_code: success=$SUCCESS (expected true). Result: $(printf '%s' "$STAGE_RESULT" | jq -c .)"
[ "$DROPLET_GUID" != "null" ] && [ -n "$DROPLET_GUID" ] || fail "stage_code: dropletGuid is null or missing"
[ "$APP_GUID" != "null" ] && [ -n "$APP_GUID" ] || fail "stage_code: appGuid is null or missing"

pass "success=true, dropletGuid=$DROPLET_GUID, appGuid=$APP_GUID, duration=${DURATION_MS}ms"

# ============================================================
# Step 4: run_task
# ============================================================
step 4 "run_task"

info "Calling run_task (command='java -jar hello.jar')"

TASK_RAW=$(curl -sS "$DROPSHIP_URL" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: $MCP_SESSION" \
    --max-time 360 \
    -d "$(jq -n \
        --arg appGuid "$APP_GUID" \
        --arg dropletGuid "$DROPLET_GUID" \
        '{
            jsonrpc: "2.0",
            id: 3,
            method: "tools/call",
            params: {
                name: "run_task",
                arguments: {
                    appGuid: $appGuid,
                    dropletGuid: $dropletGuid,
                    command: "java -jar hello.jar"
                }
            }
        }')")

TASK_JSON=$(extract_json "$TASK_RAW")
TASK_RESULT=$(extract_tool_result "$TASK_JSON")

STATE=$(printf '%s' "$TASK_RESULT" | jq -r '.state')
EXIT_CODE=$(printf '%s' "$TASK_RESULT" | jq -r '.exitCode')
TASK_GUID=$(printf '%s' "$TASK_RESULT" | jq -r '.taskGuid')
TASK_DURATION=$(printf '%s' "$TASK_RESULT" | jq -r '.durationMs')

[ "$STATE" = "SUCCEEDED" ] || fail "run_task: state=$STATE (expected SUCCEEDED). Result: $(printf '%s' "$TASK_RESULT" | jq -c .)"
[ "$EXIT_CODE" = "0" ] || fail "run_task: exitCode=$EXIT_CODE (expected 0)"
[ "$TASK_GUID" != "null" ] && [ -n "$TASK_GUID" ] || fail "run_task: taskGuid is null or missing"

pass "state=SUCCEEDED, exitCode=0, taskGuid=$TASK_GUID, duration=${TASK_DURATION}ms"

# ============================================================
# Step 5: get_task_logs
# ============================================================
step 5 "get_task_logs"

info "Looking up app name for appGuid=$APP_GUID"
APP_NAME=$(lookup_app_name "$APP_GUID")
[ -n "$APP_NAME" ] && [ "$APP_NAME" != "null" ] || fail "Could not resolve app name for $APP_GUID"
info "App name: $APP_NAME"

info "Calling get_task_logs"

LOGS_RAW=$(curl -sS "$DROPSHIP_URL" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: $MCP_SESSION" \
    --max-time 30 \
    -d "$(jq -n \
        --arg taskGuid "$TASK_GUID" \
        --arg appName "$APP_NAME" \
        '{
            jsonrpc: "2.0",
            id: 4,
            method: "tools/call",
            params: {
                name: "get_task_logs",
                arguments: {
                    taskGuid: $taskGuid,
                    appName: $appName
                }
            }
        }')")

LOGS_JSON=$(extract_json "$LOGS_RAW")
LOGS_RESULT=$(extract_tool_result "$LOGS_JSON")

TOTAL_LINES=$(printf '%s' "$LOGS_RESULT" | jq -r '.totalLines')
HAS_ENTRIES=$(printf '%s' "$LOGS_RESULT" | jq -r '.entries | length > 0')

[ "$HAS_ENTRIES" = "true" ] || fail "get_task_logs: entries is empty"

CONTAINS_HELLO=$(printf '%s' "$LOGS_RESULT" | jq -r '[.entries[].message] | any(contains("Hello, Dropship!"))')
[ "$CONTAINS_HELLO" = "true" ] || fail "get_task_logs: log entries do not contain 'Hello, Dropship!'"

pass "totalLines=$TOTAL_LINES, contains 'Hello, Dropship!' output"

# ============================================================
# Summary
# ============================================================
echo ""
echo "========================================"
printf '\033[1;32m  ALL CHECKS PASSED\033[0m\n'
echo "========================================"
echo ""
echo "Results:"
echo "  stage_code  : dropletGuid=$DROPLET_GUID  (${DURATION_MS}ms)"
echo "  run_task    : taskGuid=$TASK_GUID  exitCode=0  (${TASK_DURATION}ms)"
echo "  get_task_logs: $TOTAL_LINES log lines, output verified"
echo ""
