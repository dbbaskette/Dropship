# Implement Dual-Mode CF Authentication (Inspired by cloud-foundry-mcp)

**Date:** 2026-03-04
**Status:** DRAFT — awaiting approval

---

## Context

Dropship currently supports **only** `client_credentials` grant (service account) authentication via `CF_CLIENT_ID` / `CF_CLIENT_SECRET`. This is appropriate for local dev and STDIO transport but inadequate for multi-tenant production deployment where user identity and RBAC must be preserved.

The [cloud-foundry-mcp](https://github.com/cpage-pivotal/cloud-foundry-mcp) project implements a dual-mode auth pattern that we want to adopt:

| Mode | When Active | MCP Endpoint Security | CF API Auth |
|------|-------------|----------------------|-------------|
| **Static credentials** | `CF_CLIENT_ID` + `CF_CLIENT_SECRET` set, no OAuth issuer | `permitAll()` | `ClientCredentialsGrantTokenProvider` (current behavior) |
| **OAuth 2.1 / SSO** | `spring.security.oauth2.resourceserver.jwt.issuer-uri` is set (auto-configured via Tanzu SSO tile) | JWT Bearer token required | User's token relayed to CF API (`AccessTokenRelayTokenProvider`) |

The key insight from cloud-foundry-mcp: **mode selection is automatic at startup** via `@ConditionalOnExpression`, and in OAuth mode the user's own JWT token is relayed to CF so operations run under their permissions, not a shared service account.

---

## What Changes

### New Files (6)

| File | Purpose |
|------|---------|
| `src/main/java/com/baskette/dropship/auth/SecurityConfiguration.java` | OAuth2 Resource Server config — JWT validation, RFC 9728 metadata, activated when issuer-uri is present |
| `src/main/java/com/baskette/dropship/auth/StaticCredentialsSecurityConfiguration.java` | `permitAll()` security config — activated when no issuer-uri (current behavior preserved) |
| `src/main/java/com/baskette/dropship/auth/AccessTokenRelayTokenProvider.java` | `TokenProvider` record that wraps a user's Bearer token for relay to CF API |
| `src/main/java/com/baskette/dropship/auth/UserScopedCfClientFactory.java` | Factory that creates per-user `CloudFoundryOperations` from a relayed access token |
| `src/test/java/com/baskette/dropship/auth/SecurityConfigurationTest.java` | Tests for OAuth mode activation and filter chain |
| `src/test/java/com/baskette/dropship/auth/StaticCredentialsSecurityConfigurationTest.java` | Tests for static mode activation |

### Modified Files (5)

| File | Change |
|------|--------|
| `pom.xml` | Add `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `mcp-server-security`, `java-cfenv-boot-pivotal-sso`, `spring-security-test` |
| `src/main/java/com/baskette/dropship/config/CloudFoundryConfig.java` | Make static credential beans conditional on `cf.client-id` being set; extract `ConnectionContext` as unconditional bean |
| `src/main/resources/application.yml` | Add `spring.security.oauth2.resourceserver.jwt.issuer-uri` (empty default), `sso.auth-domain` |
| `src/main/java/com/baskette/dropship/service/StagingService.java` | Accept `CloudFoundryOperations` parameter (or resolve from factory) instead of always using injected singleton |
| `src/main/java/com/baskette/dropship/service/TaskService.java` | Same — support user-scoped operations |

### Conceptual Changes

| Area | Before | After |
|------|--------|-------|
| MCP endpoint security | None (open) | Conditional: `permitAll()` in static mode, JWT-required in OAuth mode |
| CF API auth | Always `ClientCredentialsGrantTokenProvider` | Static: `ClientCredentialsGrantTokenProvider` / OAuth: user token relay |
| User identity | Service account (opaque) | Preserved through CF API calls (auditable, RBAC-enforced) |
| Multi-tenancy | Not supported | Each request runs under the authenticated user's permissions |

---

## Implementation Plan

### Step 1: Add Security Dependencies to `pom.xml`

Add these dependencies:

```xml
<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- OAuth2 Resource Server (JWT validation) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<!-- MCP Server Security (RFC 9728 metadata, community) -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>mcp-server-security</artifactId>
    <version>0.0.6</version>
</dependency>

<!-- Auto-configures JWT issuer-uri from Tanzu SSO tile binding -->
<dependency>
    <groupId>io.pivotal.cfenv</groupId>
    <artifactId>java-cfenv-boot-pivotal-sso</artifactId>
    <version>3.5.1</version>
</dependency>

<!-- Test -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

### Step 2: Create `StaticCredentialsSecurityConfiguration`

- `@Configuration` + `@ConditionalOnExpression("''.equals('${spring.security.oauth2.resourceserver.jwt.issuer-uri:}')")`
- Bean: `SecurityFilterChain` that calls `permitAll()` and disables CSRF
- This preserves **exact current behavior** — no auth required on `/mcp` when running locally or with static credentials

### Step 3: Create `SecurityConfiguration` (OAuth mode)

- `@Configuration` + `@ConditionalOnExpression("!''.equals('${spring.security.oauth2.resourceserver.jwt.issuer-uri:}')")`
- Configures MCP endpoint as OAuth2 Resource Server
- Adds RFC 9728 `OAuth2ProtectedResourceMetadataEndpointFilter` (from `mcp-server-security`)
- Custom `BearerResourceMetadataTokenAuthenticationEntryPoint` returns 401 + metadata on unauthenticated requests
- Sets `sso.auth-domain` as the authorization server in metadata

### Step 4: Create `AccessTokenRelayTokenProvider`

```java
public record AccessTokenRelayTokenProvider(String accessToken) implements TokenProvider {
    @Override
    public Mono<String> getToken(ConnectionContext connectionContext) {
        return Mono.just("bearer " + accessToken);
    }
}
```

### Step 5: Create `UserScopedCfClientFactory`

- Injected with `ConnectionContext` (shared, unconditional bean)
- Method: `createOperations(String accessToken, String org, String space)` → `CloudFoundryOperations`
- Internally builds `ReactorCloudFoundryClient`, `ReactorDopplerClient`, `ReactorUaaClient` all using `AccessTokenRelayTokenProvider`
- No caching (tokens are short-lived, per-request)

### Step 6: Refactor `CloudFoundryConfig` — Make Static Beans Conditional

- `ConnectionContext` bean remains **unconditional** (needed by both modes)
- `ClientCredentialsGrantTokenProvider`, `ReactorCloudFoundryClient`, `DefaultCloudFoundryOperations` become `@ConditionalOnProperty("cf.client-id")`
- This prevents startup failures in OAuth-only mode (where no client-id/secret exists)

### Step 7: Update Service Layer to Support User-Scoped Operations

**Option A (recommended, matches cloud-foundry-mcp pattern):** Introduce a base service pattern:

- Services check `SecurityContextHolder` for `JwtAuthenticationToken`
- If JWT present → use `UserScopedCfClientFactory.createOperations(token)`
- If not → fall back to injected static `CloudFoundryOperations` (current behavior)

This keeps the tool layer unchanged — the service layer transparently handles auth mode.

### Step 8: Update `application.yml`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SSO_ISSUER_URI:}

sso:
  auth-domain: ${SSO_AUTH_DOMAIN:}
```

### Step 9: Write Tests

- `StaticCredentialsSecurityConfigurationTest` — verify `permitAll()` when no issuer-uri
- `SecurityConfigurationTest` — verify JWT required when issuer-uri set
- `AccessTokenRelayTokenProviderTest` — verify token format
- Update existing service tests to verify both code paths (static + user-scoped)

### Step 10: Update Documentation

- `docs/cf-setup.md` — add SSO tile binding instructions, UAA plan registration
- `docs/client-setup.md` — add OAuth token flow for MCP clients
- `manifest.yml` — add `p-identity` service binding for SSO tile

---

## Impact on Existing Issues

| Issue | Impact |
|-------|--------|
| All M1-M5 issues | **No changes needed** — existing functionality preserved in static mode |
| Issue #5 (CloudFoundryConfig) | **Refactored** — static beans become conditional |
| Issue #19 (manifest.yml) | **Updated** — add SSO service binding |
| Issue #20 (CF prerequisites) | **Updated** — add SSO tile setup instructions |
| New issues needed | Auth implementation (this plan), plus test coverage |

### New Issues to Create

1. **Add Spring Security + OAuth2 dependencies** (scaffold)
2. **Implement dual-mode security configurations** (auth)
3. **Implement token relay and user-scoped CF client factory** (auth/service)
4. **Refactor CloudFoundryConfig for conditional static credentials** (config)
5. **Update services to support user-scoped operations** (service)
6. **Auth integration tests** (test)
7. **Update deployment docs for SSO tile** (docs)

---

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Spring Security auto-config conflicts with WebFlux | Use explicit `SecurityFilterChain` beans; disable defaults |
| `mcp-server-security` 0.0.6 is early-stage | Pin version, review source; fallback: implement RFC 9728 filter manually |
| `java-cfenv-boot-pivotal-sso` only works on TAS | Conditional — no effect outside CF; can set `SSO_ISSUER_URI` manually |
| Existing tests break with Security on classpath | `StaticCredentialsSecurityConfiguration` ensures `permitAll()` when no issuer — matches current behavior |
| Service layer refactor touches critical path | Both modes share same service logic; only `CloudFoundryOperations` source changes |

---

## Architecture Diagram

```
MCP Client
    │
    ▼
┌─────────────────────────────────┐
│  Dropship MCP Server            │
│                                 │
│  ┌─────────────────────────┐    │
│  │ Security Filter Chain   │    │
│  │                         │    │
│  │ Static mode: permitAll  │    │
│  │ OAuth mode:  JWT check  │    │
│  └────────────┬────────────┘    │
│               │                 │
│  ┌────────────▼────────────┐    │
│  │ MCP Tool Layer          │    │
│  │ (StageCode, RunTask,    │    │
│  │  GetTaskLogs)           │    │
│  └────────────┬────────────┘    │
│               │                 │
│  ┌────────────▼────────────┐    │
│  │ Service Layer           │    │
│  │                         │    │
│  │ Static: injected CF ops │    │
│  │ OAuth:  user-scoped ops │    │
│  │         (token relay)   │    │
│  └────────────┬────────────┘    │
│               │                 │
└───────────────┼─────────────────┘
                │
                ▼
         CF API (CAPI + UAA)
         User's permissions enforced
```
