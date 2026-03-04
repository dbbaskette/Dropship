# Implement Static Credentials Security Configuration

**Date:** 2026-03-04
**Status:** APPROVED — scoped to static credentials mode only

---

## Context

Dropship currently has **no Spring Security on the classpath**. The MCP endpoint is open, and CF authentication uses `ClientCredentialsGrantTokenProvider` with `CF_CLIENT_ID`/`CF_CLIENT_SECRET`.

The [cloud-foundry-mcp](https://github.com/cpage-pivotal/cloud-foundry-mcp) project supports both static credentials (username/password) and OAuth/SSO. We're adopting the **static credentials foundation** now — adding Spring Security with `permitAll()` and supporting both credential types (client credentials + password grant). This lays the groundwork for future OAuth/SSO.

---

## What We're Building

1. **Spring Security on the classpath** with a `permitAll()` `SecurityFilterChain` — no behavioral change to the MCP endpoint
2. **Dual static credential support**: `CF_CLIENT_ID`/`CF_CLIENT_SECRET` (client credentials grant, current) OR `CF_USERNAME`/`CF_PASSWORD` (password grant, like cloud-foundry-mcp)
3. **Conditional bean wiring** in `CloudFoundryConfig` — auto-detect which credentials are provided
4. **Tests** for the security config and conditional bean paths

---

## Implementation Steps

### Step 1: Add Spring Security dependencies to `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

### Step 2: Create `StaticCredentialsSecurityConfiguration`

```java
@Configuration
public class StaticCredentialsSecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(CsrfConfigurer::disable)
                .build();
    }
}
```

Preserves current behavior — `/mcp` remains open.

### Step 3: Refactor `CloudFoundryConfig` for dual credential types

- `ConnectionContext` bean stays unconditional
- `ClientCredentialsGrantTokenProvider` — `@ConditionalOnProperty("cf.client-id")`
- `PasswordGrantTokenProvider` — `@ConditionalOnProperty("cf.username")`
- `ReactorCloudFoundryClient` and `DefaultCloudFoundryOperations` — use whichever `TokenProvider` is available

### Step 4: Update `application.yml`

Add `cf.username` / `cf.password` placeholders alongside existing `cf.client-id` / `cf.client-secret`.

### Step 5: Tests

- Security config test: verify `permitAll()` filter chain
- CloudFoundryConfig test: verify client-credentials path activates with `CF_CLIENT_ID`
- CloudFoundryConfig test: verify password-grant path activates with `CF_USERNAME`
- Verify mutual exclusion (only one `TokenProvider` active)

### Step 6: Update `docs/cf-setup.md`

Document both credential options: UAA client (client_credentials) vs user credentials (password grant).

---

## What We're NOT Building (Future — OAuth/SSO)

Deferred to a future milestone:
- `SecurityConfiguration` with JWT resource server
- `AccessTokenRelayTokenProvider` / `UserScopedCfClientFactory`
- RFC 9728 metadata endpoint
- `mcp-server-security` / `java-cfenv-boot-pivotal-sso` dependencies
- Per-user token relay to CF API
