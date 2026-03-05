# MCP Client Setup for Dropship

How to connect MCP clients to a deployed Dropship instance. Once connected, your
AI agent receives Dropship's server instructions automatically — no additional
configuration is needed to teach it how to use the tools.

---

## How It Works

When an MCP client connects to Dropship, the server sends **instructions** as part
of the MCP initialization handshake. These instructions teach the LLM:

- The two workflow patterns (ephemeral tasks vs. long-running web apps)
- Which tools to call in what order
- How to thread parameters between tools (appGuid, dropletGuid, etc.)
- Buildpack selection, resource defaults, and error recovery

This is built into the MCP protocol — no client-side rules files, skills, or
custom prompts are needed. Just point your client at the Dropship URL and go.

---

## Prerequisites

- A running Dropship instance with the `/mcp` endpoint accessible (see
  [deployment.md](deployment.md) and [cf-setup.md](cf-setup.md))
- The Dropship URL (e.g., `https://dropship-mcp.apps.example.com`)

Confirm the server is reachable before configuring clients:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}}' \
  | jq .
```

You should see a JSON-RPC response containing the server's capabilities and
`instructions` field.

---

## 1. Claude Code

Claude Code supports two configuration locations.

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

After saving the configuration, restart Claude Code and run `/mcp` to confirm the
Dropship tools are listed.

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

Open Cursor Settings -> MCP and confirm the `dropship` server shows a green
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

Open Windsurf and check the MCP panel to confirm the `dropship` server is connected.

---

## 4. Any MCP Client

Any client that implements the MCP protocol can connect to Dropship. Point the
client's MCP server configuration at `https://dropship-mcp.apps.example.com/mcp`
using Streamable HTTP transport. The client will receive:

- **10 tools** via `tools/list`: `test_cf_connection`, `stage_code`,
  `stage_git_repo`, `get_build_status`, `run_task`, `get_task_status`,
  `get_task_logs`, `start_app`, `get_app_status`, `stop_app`
- **Server instructions** via the `InitializeResult` that guide the LLM on tool
  orchestration patterns

---

## 5. Manual Testing with curl

Use curl to invoke the Dropship MCP tools directly via JSON-RPC over HTTP.

### Automated E2E Test Script

```bash
DROPSHIP_URL=https://dropship-mcp.apps.example.com/mcp ./scripts/e2e-curl-test.sh
```

Use `--verbose` to print raw curl commands for debugging.

### Initialize a Session

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

### stage_git_repo

Stage a public git repo (preferred over `stage_code` when source is in git):

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H "cf-apihost: api.cf.example.com" \
  -H "cf-username: myuser" \
  -H "cf-password: mypass" \
  -H "cf-org: my-org" \
  -H "cf-space: my-space" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "stage_git_repo",
      "arguments": {
        "repoUrl": "https://github.com/user/my-app.git",
        "buildpack": "java_buildpack"
      }
    }
  }' | jq .
```

The response contains a `buildId`. Poll with `get_build_status` until complete:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H "cf-apihost: api.cf.example.com" \
  -H "cf-username: myuser" \
  -H "cf-password: mypass" \
  -H "cf-org: my-org" \
  -H "cf-space: my-space" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "get_build_status",
      "arguments": {
        "buildId": "<buildId-from-stage_git_repo>"
      }
    }
  }' | jq .
```

The completed response contains `appGuid`, `appName`, and `dropletGuid`.

### run_task

Execute a command using the staged droplet:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H "cf-apihost: api.cf.example.com" \
  -H "cf-username: myuser" \
  -H "cf-password: mypass" \
  -H "cf-org: my-org" \
  -H "cf-space: my-space" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "run_task",
      "arguments": {
        "appGuid": "<appGuid-from-staging>",
        "dropletGuid": "<dropletGuid-from-staging>",
        "command": "java -cp /home/vcap/app/. Hello"
      }
    }
  }' | jq .
```

### get_task_logs

Retrieve logs from the executed task:

```bash
curl -s https://dropship-mcp.apps.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $MCP_SESSION" \
  -H "cf-apihost: api.cf.example.com" \
  -H "cf-username: myuser" \
  -H "cf-password: mypass" \
  -H "cf-org: my-org" \
  -H "cf-space: my-space" \
  -d '{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
      "name": "get_task_logs",
      "arguments": {
        "taskGuid": "<taskGuid-from-run_task>",
        "appGuid": "<appGuid-from-staging>"
      }
    }
  }' | jq .
```

---

## Available Tools

| Tool | Purpose |
|------|---------|
| `test_cf_connection` | Validate CF credentials and connectivity |
| `stage_code` | Stage a base64-encoded source bundle |
| `stage_git_repo` | Stage from a public git repo (async) |
| `get_build_status` | Poll async build status |
| `run_task` | Execute a command in an isolated container |
| `get_task_status` | Poll task completion |
| `get_task_logs` | Retrieve stdout/stderr from Loggregator |
| `start_app` | Start app as web process with HTTP route |
| `get_app_status` | Poll app process state |
| `stop_app` | Stop app and clean up route |

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

### Staging fails

1. Verify the `sourceBundle` is a valid base64-encoded tar.gz or zip archive
2. Check that the buildpack name matches an available system buildpack
3. Review the `stagingLogs` in the error response for dependency resolution failures

### Task times out

1. Increase `timeoutSeconds` in the `run_task` call (default limit: 900s)
2. Check the space quota — the task may be waiting for available memory
3. Verify the command path matches the droplet's filesystem layout

### App won't start

1. Check `get_app_status` for error details (e.g., OOM, port binding failure)
2. Try increasing memory via the staging step
3. Verify the droplet contains a web process type (check `detectedCommand` from staging)
