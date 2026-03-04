# MCP Client Setup for Dropship

How to connect MCP clients to a deployed Dropship instance. After completing these
steps, your AI agent or manual workflow can stage code, run tasks, and retrieve logs
through the Dropship MCP server.

---

## Prerequisites

- A running Dropship instance with the `/mcp` endpoint accessible (see
  [CF Foundation Setup](cf-setup.md) for deployment with either client credentials
  or password grant authentication)
- The Dropship URL (e.g., `https://dropship-mcp.apps.example.com`)
- For curl testing: `base64` CLI and `tar` (included on macOS and most Linux distros)

Confirm the server is reachable before configuring clients:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}}' \
  | jq .
```

You should see a JSON-RPC response containing the server's capabilities.

---

## 1. Claude Code

Claude Code supports two configuration locations:

### Organization-Wide (Managed)

For org-wide deployment, add Dropship to `~/.claude/managed-mcp.json`:

```json
{
  "dropship": {
    "type": "http",
    "url": "https://dropship-mcp.apps.example.com/mcp"
  }
}
```

This makes the Dropship tools available in every Claude Code session for the user.

### Project-Local

For per-project configuration, add to `.claude/mcp.json` in the project root:

```json
{
  "dropship": {
    "type": "http",
    "url": "https://dropship-mcp.apps.example.com/mcp"
  }
}
```

### Verify

After saving the configuration, restart Claude Code and check that the three Dropship
tools appear:

```
/mcp
```

You should see `stage_code`, `run_task`, and `get_task_logs` listed under the
`dropship` server.

---

## 2. Cursor

Add Dropship to `.cursor/mcp.json` in the project root:

```json
{
  "mcpServers": {
    "dropship": {
      "url": "https://dropship-mcp.apps.example.com/mcp"
    }
  }
}
```

### Verify

Open Cursor Settings → MCP and confirm the `dropship` server shows a green
connected status.

---

## 3. Windsurf

Add Dropship to `.windsurf/mcp.json` in the project root:

```json
{
  "mcpServers": {
    "dropship": {
      "url": "https://dropship-mcp.apps.example.com/mcp"
    }
  }
}
```

### Verify

Open Windsurf and check the MCP panel to confirm the `dropship` server is connected
and the three tools are listed.

---

## 4. Manual Testing with curl

Use curl to invoke the Dropship MCP tools directly via JSON-RPC over HTTP. This is
useful for debugging, scripting, and verifying the server outside an MCP client.

All requests go to the `/mcp` endpoint and use the MCP Streamable HTTP transport.

### Automated E2E Test Script

For a one-command verification of the complete `stage_code` → `run_task` →
`get_task_logs` workflow, use the automated test script:

```bash
DROPSHIP_URL=https://dropship-mcp.apps.example.com/mcp ./scripts/e2e-curl-test.sh
```

The script requires `curl`, `jq`, `base64`, `tar`, and the `cf` CLI (logged in to the
target foundation). It uses the `hello-world` test fixture, encodes it as a source bundle,
stages it, runs a task, and verifies the expected output appears in the logs.

Use `--verbose` to print the raw curl commands as they execute — useful for capturing
commands for your own scripts or debugging:

```bash
DROPSHIP_URL=https://dropship-mcp.apps.example.com/mcp ./scripts/e2e-curl-test.sh --verbose
```

### Initialize a Session

Before calling tools, initialize an MCP session:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
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
        "name": "curl-test",
        "version": "0.1"
      }
    }
  }'
```

Note the `Mcp-Session-Id` response header — include it in subsequent requests:

```bash
export MCP_SESSION="<value-from-response-header>"
```

Send the initialized notification:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -d '{
    "jsonrpc": "2.0",
    "method": "notifications/initialized"
  }'
```

### stage_code

Create a source bundle and base64-encode it:

```bash
# Create a simple Java app
mkdir -p /tmp/hello-app/src/main/java
cat > /tmp/hello-app/src/main/java/Hello.java << 'JAVA'
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello from Dropship!");
    }
}
JAVA

# Create a tarball and base64-encode it
tar czf /tmp/hello-app.tar.gz -C /tmp/hello-app .
SOURCE_BUNDLE=$(base64 < /tmp/hello-app.tar.gz)

# Stage the code
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -d "{
    \"jsonrpc\": \"2.0\",
    \"id\": 2,
    \"method\": \"tools/call\",
    \"params\": {
      \"name\": \"stage_code\",
      \"arguments\": {
        \"sourceBundle\": \"$SOURCE_BUNDLE\",
        \"buildpack\": \"java_buildpack\"
      }
    }
  }" | jq .
```

The response contains `dropletGuid` and `appGuid` needed for the next step.

### run_task

Execute a command using the staged droplet:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "run_task",
      "arguments": {
        "appGuid": "<appGuid-from-stage_code>",
        "dropletGuid": "<dropletGuid-from-stage_code>",
        "command": "java -cp /home/vcap/app/. Hello"
      }
    }
  }' | jq .
```

The response contains `taskGuid`, `exitCode`, and `state`.

### get_task_logs

Retrieve logs from the executed task:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "get_task_logs",
      "arguments": {
        "taskGuid": "<taskGuid-from-run_task>",
        "appName": "<app-name-from-staging>"
      }
    }
  }' | jq .
```

---

## 5. Sample Session Transcript

Below is a sample session showing the full `stage_code` → `run_task` → `get_task_logs`
workflow as invoked by an AI agent through Claude Code.

### User Prompt

> Stage and run a simple Java hello-world app through Dropship, then show me the output.

### Agent calls `stage_code`

The agent creates a minimal Java source file, bundles it, and stages it through
the CF buildpack pipeline.

**Tool call:**

```json
{
  "name": "stage_code",
  "arguments": {
    "sourceBundle": "H4sIAAAAAAAAA+3OQQrCMBCF4d2neLsK0qSmSb2KZJq...<truncated>",
    "buildpack": "java_buildpack"
  }
}
```

**Response:**

```json
{
  "dropletGuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "appGuid": "f9e8d7c6-b5a4-3210-fedc-ba0987654321",
  "buildpack": "java_buildpack",
  "stagingLogs": "-----> Java Buildpack v4.67\n-----> Downloading Open JDK 21.0.2...\n       Expanding Open JDK to .java-buildpack/open_jdk_jre (1.2s)\n-----> Compiling source files...\n       BUILD SUCCESSFUL\n",
  "durationMs": 28450,
  "success": true,
  "errorMessage": null
}
```

### Agent calls `run_task`

Using the `appGuid` and `dropletGuid` from staging, the agent executes the compiled
class in an isolated Diego container.

**Tool call:**

```json
{
  "name": "run_task",
  "arguments": {
    "appGuid": "f9e8d7c6-b5a4-3210-fedc-ba0987654321",
    "dropletGuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "command": "java -cp /home/vcap/app/. Hello"
  }
}
```

**Response:**

```json
{
  "taskGuid": "11223344-5566-7788-99aa-bbccddeeff00",
  "appGuid": "f9e8d7c6-b5a4-3210-fedc-ba0987654321",
  "exitCode": 0,
  "state": "SUCCEEDED",
  "durationMs": 3210,
  "memoryMb": 512,
  "command": "java -cp /home/vcap/app/. Hello"
}
```

### Agent calls `get_task_logs`

The agent retrieves the task's stdout/stderr from Loggregator.

**Tool call:**

```json
{
  "name": "get_task_logs",
  "arguments": {
    "taskGuid": "11223344-5566-7788-99aa-bbccddeeff00",
    "appName": "dropship-a1b2c3d4"
  }
}
```

**Response:**

```json
{
  "taskGuid": "11223344-5566-7788-99aa-bbccddeeff00",
  "entries": [
    {
      "timestamp": "2026-03-02T14:32:01.123Z",
      "source": "stdout",
      "message": "Hello from Dropship!"
    },
    {
      "timestamp": "2026-03-02T14:32:01.456Z",
      "source": "platform",
      "message": "Exit status 0"
    }
  ],
  "totalLines": 2,
  "truncated": false
}
```

### Agent Summary

> The hello-world app staged successfully using `java_buildpack` (28.4s), then
> ran in a 512 MB Diego container and completed in 3.2s with exit code 0.
> Output: **Hello from Dropship!**

---

## Troubleshooting

### Client cannot connect

1. Verify the Dropship URL is reachable: `curl -I https://dropship-mcp.apps.example.com/mcp`
2. Ensure the URL ends with `/mcp` — the MCP endpoint is not at the root
3. Check that no corporate proxy or firewall blocks the connection

### Tools not listed in client

1. Confirm the configuration JSON is valid (no trailing commas, correct nesting)
2. Restart the MCP client after saving configuration changes
3. Check Dropship server logs for initialization errors

### stage_code fails

1. Verify the `sourceBundle` is a valid base64-encoded tar.gz or zip archive
2. Check that the buildpack name matches an available system buildpack
3. Review the `stagingLogs` in the error response for dependency resolution failures

### run_task times out

1. Increase `timeoutSeconds` in the `run_task` call (default limit: 900s)
2. Check the space quota — the task may be waiting for available memory
3. Verify the command path matches the droplet's filesystem layout
