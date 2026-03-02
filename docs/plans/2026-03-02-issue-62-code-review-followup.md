# Issue #62: Code Review Follow-Up Findings Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Address five code review findings from PR #61 to improve integration test correctness, property binding clarity, and staging failure reliability.

**Architecture:** All changes are localized to `application-integration.yml` and `DropshipIntegrationTest.java`. No production code changes needed — these are test infrastructure improvements.

**Tech Stack:** Spring Boot YAML config, JUnit 5, AssertJ assumptions, Java ZIP/JAR APIs

---

### Task 1: Add explicit env var bindings and resource limits to application-integration.yml

**Findings addressed:** #1 (MEDIUM — spec_compliance) and #5 (LOW — architecture_fit)

**Files:**
- Modify: `src/test/resources/application-integration.yml`
- Reference: `src/test/resources/application-test.yml` (for structure)
- Reference: `src/main/resources/application.yml` (for env var names)

**Step 1: Update application-integration.yml**

Replace the entire file with explicit env var bindings and production-like resource limits. The base `application.yml` already maps `CF_API_URL` → `dropship.cf-api-url`, but repeating it here makes the integration profile self-documenting and ensures env vars work even if the base config changes.

```yaml
cf:
  client-id: ${CF_CLIENT_ID:}
  client-secret: ${CF_CLIENT_SECRET:}
  skip-ssl-validation: ${CF_SKIP_SSL_VALIDATION:true}

dropship:
  cf-api-url: ${CF_API_URL:}
  sandbox-org: ${DROPSHIP_SANDBOX_ORG:}
  sandbox-space: ${DROPSHIP_SANDBOX_SPACE:}
  max-task-memory-mb: 2048
  max-task-disk-mb: 4096
  max-task-timeout-seconds: 900
  default-task-memory-mb: 512
  default-staging-memory-mb: 1024
  default-staging-disk-mb: 2048
  app-name-prefix: "dropship-it-"
```

**Step 2: Verify the project compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/test/resources/application-integration.yml
git commit -m "fix: add explicit env var bindings and resource limits to integration profile

Addresses code review findings #1 and #5 from issue #62:
- Wire CF_API_URL, CF_CLIENT_ID, CF_CLIENT_SECRET, DROPSHIP_SANDBOX_ORG,
  DROPSHIP_SANDBOX_SPACE explicitly so documented env vars work correctly
- Add production-like resource limits mirroring application-test.yml"
```

---

### Task 2: Change task command from `java -cp . Main` to `java -jar hello.jar`

**Finding addressed:** #2 (MEDIUM — correctness)

**Files:**
- Modify: `src/test/java/com/baskette/dropship/integration/DropshipIntegrationTest.java:117`

**Step 1: Update the task command**

In `runTask_success()` (line 117), change:
```java
"java -cp . Main",
```
to:
```java
"java -jar hello.jar",
```

The executable JAR with a `Main-Class` manifest is already included in the bundle by `createExecutableJar()` and is portable across buildpack versions.

**Step 2: Verify the project compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/test/java/com/baskette/dropship/integration/DropshipIntegrationTest.java
git commit -m "fix: use java -jar hello.jar for integration test task command

The executable JAR is more portable across buildpack versions than
java -cp . Main which depends on droplet file layout. Addresses
finding #2 from issue #62."
```

---

### Task 3: Fix potential NPE in assumeThat chain

**Finding addressed:** #3 (LOW — correctness)

**Files:**
- Modify: `src/test/java/com/baskette/dropship/integration/DropshipIntegrationTest.java:111-112`

**Step 1: Rewrite the assumption in runTask_success()**

Replace lines 111-112:
```java
        assumeThat(stagingResult).isNotNull();
        assumeThat(stagingResult.success()).isTrue();
```
with a guarded single assumption:
```java
        assumeThat(stagingResult != null && stagingResult.success()).isTrue();
```

This avoids the auto-unboxing NPE if `stagingResult` is null — the entire expression evaluates to `false` and the test is cleanly skipped.

**Step 2: Apply the same fix to runTask_failureWithInvalidCommand()**

Replace lines 175-176:
```java
        assumeThat(stagingResult).isNotNull();
        assumeThat(stagingResult.success()).isTrue();
```
with:
```java
        assumeThat(stagingResult != null && stagingResult.success()).isTrue();
```

**Step 3: Verify the project compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/test/java/com/baskette/dropship/integration/DropshipIntegrationTest.java
git commit -m "fix: guard assumeThat against NPE when stagingResult is null

Combines null check and success check into a single boolean expression
to prevent auto-unboxing NPE. A null stagingResult now cleanly skips
the test. Addresses finding #3 from issue #62."
```

---

### Task 4: Use corrupted bytecode for invalid source bundle

**Finding addressed:** #4 (LOW — correctness)

**Files:**
- Modify: `src/test/java/com/baskette/dropship/integration/DropshipIntegrationTest.java:243-253`

**Step 1: Replace createInvalidSourceBundle()**

Replace the method that creates a ZIP with a `.txt` file with one that creates a ZIP containing a `.class` file with corrupted bytecode. This reliably triggers a staging failure regardless of buildpack configuration since the java_buildpack will attempt to process a `.class` file but fail on the invalid bytecode.

```java
    private byte[] createInvalidSourceBundle() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Corrupted .class file: starts with valid magic bytes but has invalid content
            byte[] corruptedClass = new byte[] {
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, // Java magic number
                0x00, 0x00, 0x00, 0x34,                               // Version 52 (Java 8)
                0x00, 0x00                                             // Invalid constant pool count
            };
            zos.putNextEntry(new ZipEntry("Main.class"));
            zos.write(corruptedClass);
            zos.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }
```

**Step 2: Verify the project compiles**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/test/java/com/baskette/dropship/integration/DropshipIntegrationTest.java
git commit -m "fix: use corrupted bytecode for invalid source bundle in integration test

A .class file with corrupted bytecode reliably triggers staging failure
regardless of CF foundation configuration, unlike a .txt file which
some buildpacks may silently accept. Addresses finding #4 from issue #62."
```

---

### Task 5: Run unit tests to verify no regressions

**Step 1: Run unit tests**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS, all unit tests pass

Note: Integration tests are skipped by default (they require the `integration` profile and a real CF foundation). We only verify that the code compiles and unit tests pass.

---
