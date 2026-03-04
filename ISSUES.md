# Dropship — Issues Breakdown

Each issue is scoped to be independently buildable, testable, and mergeable. Issues within a milestone can be worked in parallel unless a dependency is noted.

-----

## Labels

| Label | Color | Description |
|---|---|---|
| `scaffold` | `#0E8A16` | Project setup, build config, CI |
| `config` | `#1D76DB` | Configuration, properties, profiles |
| `tool` | `#5319E7` | MCP tool implementation |
| `service` | `#D93F0B` | CF service layer integration |
| `model` | `#FBCA04` | Data models, records, DTOs |
| `test` | `#B60205` | Unit or integration tests |
| `infra` | `#006B75` | CF foundation setup, deployment |
| `docs` | `#C5DEF5` | Documentation |
| `auth` | `#D876E3` | Authentication, security |

-----

## Completed Milestones

### M1: Skeleton — COMPLETE
> Project scaffold, Spring Boot app, DropshipProperties, Spring profiles

### M2: CF Wiring — COMPLETE
> CloudFoundryConfig, CF connectivity check, space GUID resolver

### M3: stage_code — COMPLETE
> StagingResult model, StagingService, StageCodeTool, unit tests

### M4: run_task — COMPLETE
> TaskResult model, TaskService, RunTaskTool, unit tests

### M5: get_task_logs — COMPLETE
> TaskLogs model, LogService, GetTaskLogsTool, unit tests

### M6: Integration & Deployment — COMPLETE
> CF manifest, CF prerequisites docs, integration tests, e2e verification, smoke tests

-----

## M7: Static Credentials Auth — "Spring Security foundation with dual credential support"

> Adds Spring Security to the classpath, supports both client_credentials and password grant for CF authentication. Lays groundwork for future OAuth/SSO mode.
>
> **Design:** `docs/plans/2026-03-04-cf-auth-dual-mode.md`
> **Reference:** [cloud-foundry-mcp](https://github.com/cpage-pivotal/cloud-foundry-mcp) auth pattern

---

### Issue #112 — Add Spring Security dependencies
**Labels:** `scaffold` `auth`
**Milestone:** M7
**Depends on:** nothing

**Description:**
Add `spring-boot-starter-security` and `spring-security-test` to `pom.xml`. No behavioral changes yet — just get Security on the classpath.

**Acceptance criteria:**
- [ ] `spring-boot-starter-security` added to `pom.xml`
- [ ] `spring-security-test` added with `<scope>test</scope>`
- [ ] `mvn dependency:resolve` succeeds
- [ ] Existing unit tests still pass (will need `StaticCredentialsSecurityConfiguration` from #113 to avoid Spring Security locking down endpoints)

**Files:**
- `pom.xml`

**Note:** Must be delivered together with #113 to avoid breaking existing behavior.

---

### Issue #113 — Create StaticCredentialsSecurityConfiguration
**Labels:** `config` `auth`
**Milestone:** M7
**Depends on:** #112

**Description:**
Create a `SecurityFilterChain` bean that permits all requests and disables CSRF. This preserves the current open-endpoint behavior after adding Spring Security to the classpath. Modeled after `StaticCredentialsSecurityConfiguration` in cloud-foundry-mcp.

**Acceptance criteria:**
- [ ] `StaticCredentialsSecurityConfiguration.java` in `com.baskette.dropship.auth` package
- [ ] `@Configuration` class
- [ ] `SecurityFilterChain` bean: `permitAll()` + CSRF disabled
- [ ] All existing tests pass unchanged
- [ ] MCP endpoint remains accessible without credentials
- [ ] Unit test: verify filter chain permits unauthenticated requests

**Files:**
- `src/main/java/com/baskette/dropship/auth/StaticCredentialsSecurityConfiguration.java`
- `src/test/java/com/baskette/dropship/auth/StaticCredentialsSecurityConfigurationTest.java`

---

### Issue #114 — Support password grant authentication (CF_USERNAME / CF_PASSWORD)
**Labels:** `config` `auth`
**Milestone:** M7
**Depends on:** #113

**Description:**
Add `PasswordGrantTokenProvider` as an alternative to `ClientCredentialsGrantTokenProvider`. Auto-detect which credential type to use based on which environment variables are set. This matches how cloud-foundry-mcp supports `CF_USERNAME`/`CF_PASSWORD` for local dev and user-scoped access.

**Acceptance criteria:**
- [ ] `CloudFoundryConfig` updated with two conditional `TokenProvider` beans:
  - `ClientCredentialsGrantTokenProvider` — `@ConditionalOnProperty("cf.client-id")` (existing behavior)
  - `PasswordGrantTokenProvider` — `@ConditionalOnProperty("cf.username")`
- [ ] `ReactorCloudFoundryClient` and `DefaultCloudFoundryOperations` beans use whichever `TokenProvider` is active
- [ ] `ConnectionContext` bean remains unconditional
- [ ] `application.yml` updated with `cf.username` / `cf.password` placeholders (empty defaults)
- [ ] Startup logs indicate which credential type is in use
- [ ] If neither credential type is configured, app still starts (allows local dev without CF)

**Files:**
- `src/main/java/com/baskette/dropship/config/CloudFoundryConfig.java` (refactor)
- `src/main/resources/application.yml` (update)

---

### Issue #115 — Unit tests for dual credential configuration
**Labels:** `test` `auth`
**Milestone:** M7
**Depends on:** #114

**Description:**
Verify that the conditional bean wiring correctly activates the right `TokenProvider` based on configuration.

**Acceptance criteria:**
- [ ] Test: when `cf.client-id` is set → `ClientCredentialsGrantTokenProvider` is created
- [ ] Test: when `cf.username` is set → `PasswordGrantTokenProvider` is created
- [ ] Test: when neither is set → no `TokenProvider` bean (app starts, CF operations unavailable)
- [ ] Test: `ConnectionContext` is always created when `cf-api-url` is present
- [ ] Existing `CloudFoundryConfigTest` updated or replaced

**Files:**
- `src/test/java/com/baskette/dropship/config/CloudFoundryConfigTest.java` (update)

---

### Issue #116 — Update CF setup documentation for dual credential types
**Labels:** `docs` `auth`
**Milestone:** M7
**Depends on:** #114

**Description:**
Update `docs/cf-setup.md` to document both authentication options and when to use each.

**Acceptance criteria:**
- [ ] Document client credentials option (existing — UAA client, `CF_CLIENT_ID`/`CF_CLIENT_SECRET`)
- [ ] Document password grant option (`CF_USERNAME`/`CF_PASSWORD` — for dev/testing with user account)
- [ ] Describe when to use each: client_credentials for service accounts, password for user-scoped dev
- [ ] Update `vars.yml.example` with both credential options
- [ ] Update `docs/client-setup.md` MCP client config examples with both options

**Files:**
- `docs/cf-setup.md` (update)
- `vars.yml.example` (update)
- `docs/client-setup.md` (update)

-----

## Dependency Graph

```
#112 Spring Security dependencies
 └── #113 StaticCredentialsSecurityConfiguration
      └── #114 Dual credential support (password grant + client credentials)
           ├── #115 Unit tests
           └── #116 Documentation update
```

All M7 issues are sequential — each builds on the previous.

-----

## Issue Summary

| GH # | Title | Labels | Milestone | Depends On | Status |
|---|---|---|---|---|---|
| #1–#22 | M1–M6 (Skeleton through Integration) | various | M1–M6 | various | COMPLETE |
| #112 | Add Spring Security dependencies | `scaffold` `auth` | M7 | — | TODO |
| #113 | Create StaticCredentialsSecurityConfiguration | `config` `auth` | M7 | #112 | TODO |
| #114 | Support password grant authentication | `config` `auth` | M7 | #113 | TODO |
| #115 | Unit tests for dual credential configuration | `test` `auth` | M7 | #114 | TODO |
| #116 | Update CF setup docs for dual credentials | `docs` `auth` | M7 | #114 | TODO |
