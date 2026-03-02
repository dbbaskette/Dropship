# RunTaskTool Design

## Summary
Implement `RunTaskTool` as an MCP tool (`run_task`) that executes commands in isolated CF containers. Follows the `StageCodeTool` → `StagingService` pattern: tool handles validation/annotations, service orchestrates the CF lifecycle.

## Architecture
- `RunTaskTool.java` — `@Service` with `@McpTool`, validates inputs, delegates to `TaskService.runTask()`, calls `.block()`
- `TaskService.runTask()` — new method orchestrating: `setCurrentDroplet → createTask → pollTask → return TaskResult`

## RunTaskTool Parameters
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| appGuid | String | yes | App GUID from prior `stage_code` result |
| dropletGuid | String | yes | Droplet GUID from prior `stage_code` result |
| command | String | yes | Shell command to execute |
| memoryMb | Integer | no | Memory limit in MB |
| timeoutSeconds | Integer | no | Max execution time in seconds |
| environment | Map<String,String> | no | Environment variables |

## Validation (RunTaskTool)
- Reject null/blank `appGuid`, `dropletGuid`, `command`

## TaskService.runTask() Flow
1. `setCurrentDroplet(appGuid, dropletGuid)` — assign droplet to app
2. `createTask(appGuid, command, memoryMb, null, timeoutSeconds, environment)` — create CF task
3. `pollTask(taskGuid)` — poll with `Mono.defer()` + `Retry.backoff` until SUCCEEDED/FAILED
4. Convert to `TaskResult` and return

## Error Handling
- CF API errors caught via `onErrorResume`, returned as failed `TaskResult`
- Timeout via `.timeout()` matching the staging pattern
