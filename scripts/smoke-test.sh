#!/usr/bin/env bash
#
# Smoke test for the Dropship MCP Server.
#
# Verifies that a deployed (or local) Dropship instance is healthy and
# responds correctly to MCP protocol requests over the Streamable HTTP
# transport (Spring AI 1.1.2, endpoint /mcp, protocol version 2025-03-26).
#
# Usage:
#   # Full deploy + smoke test
#   ./scripts/smoke-test.sh
#
#   # Re-test an already-running instance (skip cf push)
#   ./scripts/smoke-test.sh --skip-push
#
#   # Override the app URL
#   DROPSHIP_URL=https://dropship-mcp.apps.example.com ./scripts/smoke-test.sh --skip-push
#
# Environment variables:
#   DROPSHIP_URL      (optional) Base URL of the running Dropship instance.
#                     Discovered automatically from `cf app` if not set.
#   CF_VARS_FILE      (optional) Path to a vars file for cf push (e.g., vars-prod.yml)
#   CF_APP_NAME       (optional) CF application name, default: dropship-mcp
#
# Exit codes:
#   0  All checks passed
#   1  One or more checks failed

set -euo pipefail

# --- Configuration ---

CF_APP_NAME="${CF_APP_NAME:-dropship-mcp}"
MCP_ENDPOINT="/mcp"
PROTOCOL_VERSION="2025-03-26"
SKIP_PUSH=false

# --- Parse flags ---

for arg in "$@"; do
    case "$arg" in
        --skip-push) SKIP_PUSH=true ;;
        *) echo "Unknown flag: $arg"; exit 1 ;;
    esac
done

# --- Output helpers ---

PASS_COUNT=0
FAIL_COUNT=0

pass() { printf '\033[0;32m  PASS: %s\033[0m\n' "$1"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { printf '\033[0;31m  FAIL: %s\033[0m\n' "$1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
info() { printf '\033[0;33m  >> %s\033[0m\n' "$1"; }
step() { printf '\n\033[1;36m=== Step %s: %s ===\033[0m\n' "$1" "$2"; }

# --- Temp file for response headers ---

HEADER_FILE=$(mktemp)
trap 'rm -f "$HEADER_FILE"' EXIT

# --- Extract JSON from plain JSON or SSE (text/event-stream) response ---
# Spring AI STREAMABLE transport returns SSE-formatted responses with
# lines like "event: message\ndata: {...}\n\n".
extract_json() {
    local response="$1"
    # Try plain JSON first
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
    echo ""
}

# ============================================================
# Step 1: Deploy (unless --skip-push)
# ============================================================
step 1 "Deploy"

if [ "$SKIP_PUSH" = true ]; then
    info "Skipping cf push (--skip-push)"
else
    for cmd in cf; do
        command -v "$cmd" >/dev/null 2>&1 || { fail "Required command not found: $cmd"; exit 1; }
    done

    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

    info "Pushing $CF_APP_NAME via cf push"
    PUSH_CMD="cf push $CF_APP_NAME -f $PROJECT_ROOT/manifest.yml"
    if [ -n "${CF_VARS_FILE:-}" ]; then
        PUSH_CMD="$PUSH_CMD --vars-file $CF_VARS_FILE"
    fi
    if $PUSH_CMD; then
        pass "cf push succeeded"
    else
        fail "cf push failed"
        exit 1
    fi
fi

# --- Discover app URL ---

if [ -z "${DROPSHIP_URL:-}" ]; then
    for cmd in cf jq; do
        command -v "$cmd" >/dev/null 2>&1 || { fail "Required command not found: $cmd"; exit 1; }
    done
    info "Discovering app URL from cf app $CF_APP_NAME"
    APP_ROUTE=$(cf app "$CF_APP_NAME" | grep -i 'routes:' | awk '{print $2}')
    if [ -z "$APP_ROUTE" ]; then
        fail "Could not discover route for $CF_APP_NAME"
        exit 1
    fi
    DROPSHIP_URL="https://$APP_ROUTE"
    info "Discovered URL: $DROPSHIP_URL"
else
    info "Using DROPSHIP_URL=$DROPSHIP_URL"
fi

for cmd in curl jq; do
    command -v "$cmd" >/dev/null 2>&1 || { fail "Required command not found: $cmd"; exit 1; }
done

echo ""
echo "Dropship Smoke Test"
echo "Target: $DROPSHIP_URL"

# ============================================================
# Step 2: Health check
# ============================================================
step 2 "Health check"

info "GET /actuator/health"
HEALTH_RESPONSE=$(curl -sS --max-time 10 "$DROPSHIP_URL/actuator/health" 2>&1) || true

if printf '%s' "$HEALTH_RESPONSE" | jq -e '.status' >/dev/null 2>&1; then
    HEALTH_STATUS=$(printf '%s' "$HEALTH_RESPONSE" | jq -r '.status')
    if [ "$HEALTH_STATUS" = "UP" ]; then
        pass "Health status: UP"
    else
        fail "Health status: $HEALTH_STATUS (expected UP)"
    fi
else
    fail "Health endpoint did not return valid JSON"
fi

# ============================================================
# Step 3: MCP initialize
# ============================================================
step 3 "MCP initialize"

info "POST $MCP_ENDPOINT (initialize, protocolVersion=$PROTOCOL_VERSION)"
INIT_RAW=$(curl -sS -D "$HEADER_FILE" --max-time 15 \
    "$DROPSHIP_URL$MCP_ENDPOINT" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d "{
        \"jsonrpc\": \"2.0\",
        \"id\": 1,
        \"method\": \"initialize\",
        \"params\": {
            \"protocolVersion\": \"$PROTOCOL_VERSION\",
            \"capabilities\": {},
            \"clientInfo\": {
                \"name\": \"smoke-test\",
                \"version\": \"1.0\"
            }
        }
    }" 2>&1) || true

# Extract session ID from response headers
MCP_SESSION=$(grep -i 'mcp-session-id' "$HEADER_FILE" 2>/dev/null | tr -d '\r' | awk -F': ' '{print $2}')
if [ -n "$MCP_SESSION" ]; then
    pass "Session ID: $MCP_SESSION"
else
    fail "No Mcp-Session-Id in response headers"
fi

# Parse the initialize response
INIT_JSON=$(extract_json "$INIT_RAW")
if [ -z "$INIT_JSON" ]; then
    fail "Could not parse initialize response as JSON or SSE"
else
    # Verify server info
    SERVER_NAME=$(printf '%s' "$INIT_JSON" | jq -r '.result.serverInfo.name // empty')
    SERVER_VERSION=$(printf '%s' "$INIT_JSON" | jq -r '.result.serverInfo.version // empty')
    NEGOTIATED_VERSION=$(printf '%s' "$INIT_JSON" | jq -r '.result.protocolVersion // empty')

    if [ "$SERVER_NAME" = "dropship" ]; then
        pass "Server name: $SERVER_NAME (version: $SERVER_VERSION)"
    else
        fail "Server name: '${SERVER_NAME:-<empty>}' (expected 'dropship')"
    fi

    if [ -n "$NEGOTIATED_VERSION" ]; then
        pass "Negotiated protocol version: $NEGOTIATED_VERSION"
    else
        fail "No protocolVersion in initialize response"
    fi
fi

# Send initialized notification
if [ -n "$MCP_SESSION" ]; then
    info "POST notifications/initialized"
    curl -sS "$DROPSHIP_URL$MCP_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Mcp-Session-Id: $MCP_SESSION" \
        -d '{"jsonrpc": "2.0", "method": "notifications/initialized"}' >/dev/null 2>&1 || true
    pass "Initialized notification sent"
fi

# ============================================================
# Step 4: List tools
# ============================================================
step 4 "List tools"

if [ -n "$MCP_SESSION" ]; then
    info "POST $MCP_ENDPOINT (tools/list)"
    TOOLS_RAW=$(curl -sS --max-time 15 \
        "$DROPSHIP_URL$MCP_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -H "Mcp-Session-Id: $MCP_SESSION" \
        -d '{
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/list"
        }' 2>&1) || true

    TOOLS_JSON=$(extract_json "$TOOLS_RAW")
    if [ -z "$TOOLS_JSON" ]; then
        fail "Could not parse tools/list response"
    else
        TOOL_COUNT=$(printf '%s' "$TOOLS_JSON" | jq -r '.result.tools | length // 0')
        TOOL_NAMES=$(printf '%s' "$TOOLS_JSON" | jq -r '[.result.tools[].name] | join(", ") // empty')

        if [ "$TOOL_COUNT" -gt 0 ] 2>/dev/null; then
            pass "Tools registered: $TOOL_COUNT [$TOOL_NAMES]"
        else
            fail "No tools registered (expected stage_code, run_task, get_task_logs)"
        fi

        # Check for expected tools
        for expected_tool in stage_code run_task get_task_logs; do
            if printf '%s' "$TOOLS_JSON" | jq -e ".result.tools[] | select(.name == \"$expected_tool\")" >/dev/null 2>&1; then
                pass "Tool present: $expected_tool"
            else
                fail "Missing tool: $expected_tool"
            fi
        done
    fi
else
    fail "Skipping tools/list — no MCP session"
fi

# ============================================================
# Step 5: CloudFoundry connectivity (recent logs)
# ============================================================
step 5 "CloudFoundry connectivity"

if command -v cf >/dev/null 2>&1; then
    info "Checking recent logs for CF connectivity message"
    RECENT_LOGS=$(cf logs "$CF_APP_NAME" --recent 2>/dev/null | tail -100) || true

    if printf '%s' "$RECENT_LOGS" | grep -qE "CloudFoundryHealthCheck.*connected|Dropship connected to CF"; then
        pass "CF connectivity confirmed in recent logs"
    else
        fail "CF connectivity message not found in recent logs"
        info "Expected pattern: 'CloudFoundryHealthCheck.*connected' or 'Dropship connected to CF'"
    fi
else
    info "cf CLI not available — skipping CF connectivity log check"
fi

# ============================================================
# Summary
# ============================================================
echo ""
echo "========================================"
if [ "$FAIL_COUNT" -eq 0 ]; then
    printf '\033[1;32m  ALL %d CHECKS PASSED\033[0m\n' "$PASS_COUNT"
    echo "========================================"
    exit 0
else
    printf '\033[1;31m  %d PASSED, %d FAILED\033[0m\n' "$PASS_COUNT" "$FAIL_COUNT"
    echo "========================================"
    exit 1
fi
