#!/usr/bin/env bats
#
# BATS tests for the smoke-test.sh pre-flight checks.
#
# These tests exercise the pre-flight logic (JAR check, vars-file
# validation, cf env parsing) in isolation using mock `cf` and `curl`
# commands that simulate real CLI behaviour.
#
# Run:
#   bats tests/smoke-test-preflight.bats
#
# Requirements:
#   - bats-core (https://github.com/bats-core/bats-core)

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

setup() {
    TEST_TMPDIR="$(mktemp -d)"
    MOCK_BIN="$TEST_TMPDIR/bin"
    mkdir -p "$MOCK_BIN"

    # Create a fake JAR so the JAR check passes by default
    mkdir -p "$PROJECT_ROOT/target"
    FAKE_JAR="$PROJECT_ROOT/target/dropship-0.1.0-SNAPSHOT.jar"
    if [ ! -f "$FAKE_JAR" ]; then
        touch "$FAKE_JAR"
        JAR_CREATED=true
    else
        JAR_CREATED=false
    fi

    # --- Mock cf ---
    cat > "$MOCK_BIN/cf" <<'MOCK_CF'
#!/usr/bin/env bash
if [ "$1" = "env" ]; then
    if [ -n "$MOCK_CF_ENV_OUTPUT" ]; then
        printf '%s' "$MOCK_CF_ENV_OUTPUT"
        exit 0
    fi
    if [ "$MOCK_CF_APP_NOT_FOUND" = "true" ]; then
        echo "FAILED"
        echo "App '$2' not found"
        exit 1
    fi
    echo "User-Provided:"
    echo ""
    exit 0
elif [ "$1" = "push" ]; then
    echo "OK"
    exit 0
elif [ "$1" = "app" ]; then
    echo "routes: dropship-mcp.apps.example.com"
    exit 0
elif [ "$1" = "logs" ]; then
    echo "CloudFoundryHealthCheck connected"
    exit 0
fi
exit 0
MOCK_CF
    chmod +x "$MOCK_BIN/cf"

    # --- Mock curl ---
    # Handles -D (dump headers), -sS, --max-time, and URL-based routing.
    cat > "$MOCK_BIN/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
HEADER_FILE=""
URL=""
# Parse arguments to find the -D target and the URL
while [ $# -gt 0 ]; do
    case "$1" in
        -D) HEADER_FILE="$2"; shift 2 ;;
        -H|-d|--max-time) shift 2 ;;
        -sS|-s|-S) shift ;;
        http*) URL="$1"; shift ;;
        *) shift ;;
    esac
done

# Write mock response headers if -D was used
if [ -n "$HEADER_FILE" ]; then
    printf 'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nMcp-Session-Id: mock-session-id-1234\r\n\r\n' > "$HEADER_FILE"
fi

# Route response by URL
case "$URL" in
    *"/actuator/health"*)
        echo '{"status":"UP"}'
        ;;
    *"/mcp"*)
        echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"dropship","version":"0.1.0"},"capabilities":{}}}'
        ;;
    *)
        echo '{}'
        ;;
esac
exit 0
MOCK_CURL
    chmod +x "$MOCK_BIN/curl"

    # Put mocks first in PATH
    export PATH="$MOCK_BIN:$PATH"
}

teardown() {
    if [ "$JAR_CREATED" = true ] && [ -f "$FAKE_JAR" ]; then
        rm -f "$FAKE_JAR"
    fi
    rm -rf "$TEST_TMPDIR"
}

# --- Helper ---
run_smoke_preflight() {
    export DROPSHIP_URL="https://dropship-mcp.apps.example.com"
    run bash "$PROJECT_ROOT/scripts/smoke-test.sh" "$@"
}

# ============================================================
# Test 1: Vars file provided and valid
# ============================================================
@test "preflight: vars file provided and valid passes" {
    VARS_FILE="$TEST_TMPDIR/vars.yml"
    cat > "$VARS_FILE" <<EOF
cf-api-url: https://api.sys.real-domain.com
cf-client-id: my-client
cf-client-secret: s3cret-value
sandbox-org: my-org
sandbox-space: my-space
EOF
    export CF_VARS_FILE="$VARS_FILE"
    run_smoke_preflight --skip-push
    echo "$output"
    [ "$status" -eq 0 ]
    [[ "$output" == *"Vars file exists"* ]]
    [[ "$output" == *"Vars file has no placeholder values"* ]]
}

# ============================================================
# Test 2: Vars file provided but does not exist
# ============================================================
@test "preflight: vars file provided but missing fails" {
    export CF_VARS_FILE="$TEST_TMPDIR/nonexistent-vars.yml"
    run_smoke_preflight --skip-push
    [ "$status" -ne 0 ]
    [[ "$output" == *"CF_VARS_FILE not found"* ]]
}

# ============================================================
# Test 3: Vars file provided but contains placeholder values
# ============================================================
@test "preflight: vars file with YOUR-DOMAIN placeholder fails" {
    VARS_FILE="$TEST_TMPDIR/vars.yml"
    cat > "$VARS_FILE" <<EOF
cf-api-url: https://api.sys.YOUR-DOMAIN
cf-client-id: my-client
cf-client-secret: s3cret-value
sandbox-org: my-org
sandbox-space: my-space
EOF
    export CF_VARS_FILE="$VARS_FILE"
    run_smoke_preflight --skip-push
    [ "$status" -ne 0 ]
    [[ "$output" == *"placeholder values"* ]]
}

@test "preflight: vars file with REPLACE_WITH placeholder fails" {
    VARS_FILE="$TEST_TMPDIR/vars.yml"
    cat > "$VARS_FILE" <<EOF
cf-api-url: https://api.sys.real-domain.com
cf-client-id: my-client
cf-client-secret: REPLACE_WITH_CLIENT_SECRET
sandbox-org: my-org
sandbox-space: my-space
EOF
    export CF_VARS_FILE="$VARS_FILE"
    run_smoke_preflight --skip-push
    [ "$status" -ne 0 ]
    [[ "$output" == *"placeholder values"* ]]
}

# ============================================================
# Test 4: No vars file, app exists with all env vars set
# ============================================================
@test "preflight: no vars file, app exists with all vars set passes" {
    unset CF_VARS_FILE
    export MOCK_CF_ENV_OUTPUT="Getting env variables for app dropship-mcp...

System-Provided:
  VCAP_APPLICATION: {}

User-Provided:
  CF_API_URL: https://api.sys.example.com
  CF_CLIENT_ID: my-client
  CF_CLIENT_SECRET: s3cret
  DROPSHIP_SANDBOX_ORG: my-org
  DROPSHIP_SANDBOX_SPACE: my-space

Running Environment Variable Groups:
"
    run_smoke_preflight --skip-push
    echo "$output"
    [ "$status" -eq 0 ]
    [[ "$output" == *"CF env var set: CF_API_URL"* ]]
    [[ "$output" == *"CF env var set: CF_CLIENT_ID"* ]]
    [[ "$output" == *"CF env var set: CF_CLIENT_SECRET"* ]]
    [[ "$output" == *"CF env var set: DROPSHIP_SANDBOX_ORG"* ]]
    [[ "$output" == *"CF env var set: DROPSHIP_SANDBOX_SPACE"* ]]
}

# ============================================================
# Test 5: No vars file, app exists with a missing env var
# ============================================================
@test "preflight: no vars file, app exists with missing var fails" {
    unset CF_VARS_FILE
    export MOCK_CF_ENV_OUTPUT="Getting env variables for app dropship-mcp...

System-Provided:
  VCAP_APPLICATION: {}

User-Provided:
  CF_API_URL: https://api.sys.example.com
  CF_CLIENT_ID: my-client
  DROPSHIP_SANDBOX_ORG: my-org
  DROPSHIP_SANDBOX_SPACE: my-space

Running Environment Variable Groups:
"
    run_smoke_preflight --skip-push
    [ "$status" -ne 0 ]
    [[ "$output" == *"CF env var missing: CF_CLIENT_SECRET"* ]]
}

# ============================================================
# Test 6: No vars file, app does not exist
# ============================================================
@test "preflight: no vars file, app not found fails with helpful message" {
    unset CF_VARS_FILE
    export MOCK_CF_APP_NOT_FOUND=true
    run_smoke_preflight --skip-push
    [ "$status" -ne 0 ]
    [[ "$output" == *"does not exist yet"* ]]
    [[ "$output" == *"vars file"* ]]
}

# ============================================================
# Test 7: Env var in System-Provided section is not a false positive
# ============================================================
@test "preflight: env var name in System-Provided section only is not a false positive" {
    unset CF_VARS_FILE
    export MOCK_CF_ENV_OUTPUT="Getting env variables for app dropship-mcp...

System-Provided:
  VCAP_APPLICATION: {\"cf_api\":\"https://api.sys.example.com\",\"CF_CLIENT_ID\":\"system-injected\"}

User-Provided:
  CF_API_URL: https://api.sys.example.com
  CF_CLIENT_SECRET: s3cret
  DROPSHIP_SANDBOX_ORG: my-org
  DROPSHIP_SANDBOX_SPACE: my-space

Running Environment Variable Groups:
"
    run_smoke_preflight --skip-push
    [ "$status" -ne 0 ]
    [[ "$output" == *"CF env var missing: CF_CLIENT_ID"* ]]
}

# ============================================================
# Test 8: JAR check runs when pushing (not --skip-push)
# ============================================================
@test "preflight: JAR check passes when artifact exists and pushing" {
    VARS_FILE="$TEST_TMPDIR/vars.yml"
    cat > "$VARS_FILE" <<EOF
cf-api-url: https://api.sys.real-domain.com
cf-client-id: my-client
cf-client-secret: s3cret-value
sandbox-org: my-org
sandbox-space: my-space
EOF
    export CF_VARS_FILE="$VARS_FILE"
    run_smoke_preflight
    echo "$output"
    [ "$status" -eq 0 ]
    [[ "$output" == *"JAR artifact exists"* ]]
}

# ============================================================
# Test 9: JAR check skipped with --skip-push
# ============================================================
@test "preflight: JAR check is skipped with --skip-push" {
    VARS_FILE="$TEST_TMPDIR/vars.yml"
    cat > "$VARS_FILE" <<EOF
cf-api-url: https://api.sys.real-domain.com
cf-client-id: my-client
cf-client-secret: s3cret-value
sandbox-org: my-org
sandbox-space: my-space
EOF
    export CF_VARS_FILE="$VARS_FILE"
    run_smoke_preflight --skip-push
    [ "$status" -eq 0 ]
    [[ "$output" == *"Skipping JAR check"* ]]
}

# ============================================================
# Test 10: Env-var check runs even with --skip-push
# ============================================================
@test "preflight: env-var check runs even with --skip-push" {
    unset CF_VARS_FILE
    export MOCK_CF_ENV_OUTPUT="Getting env variables for app dropship-mcp...

User-Provided:
  CF_API_URL: https://api.sys.example.com
  CF_CLIENT_ID: my-client
  CF_CLIENT_SECRET: s3cret
  DROPSHIP_SANDBOX_ORG: my-org
  DROPSHIP_SANDBOX_SPACE: my-space

"
    run_smoke_preflight --skip-push
    echo "$output"
    [ "$status" -eq 0 ]
    [[ "$output" == *"CF env var set: CF_API_URL"* ]]
    [[ "$output" == *"Skipping JAR check"* ]]
}
