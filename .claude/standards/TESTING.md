# Testing Standards — ike-tooling

## Test Pyramid

ike-tooling follows a mock-last testing strategy. The testing pyramid,
ordered from most preferred to least preferred:

| Level | Tool | When to use |
|-------|------|-------------|
| Real services | Testcontainers | External infrastructure (Git servers, Maven repos, SSH) |
| Hand-written fakes | Plain Java classes | Interfaces you own — implement with minimal in-memory behavior |
| JDK dynamic proxies | `java.lang.reflect.Proxy` | Interface stubs without writing a class; reflection-based, not bytecode |
| HTTP stubs | WireMock | REST endpoint testing without a real server |
| Mock framework | Mockito (last resort) | Only when all three criteria are met (see below) |

**Mock-last means**: exhaust the options above before reaching for a mock
framework. When a mock framework _is_ genuinely necessary, prefer
Mockito's interface-only mode (JDK proxy backend) over the inline
mock maker (ByteBuddy agent). Never use PowerMock, EasyMock, or any
framework that requires a Java agent for core functionality.

### Three criteria for mock framework use

A mock framework is acceptable only when the dependency being isolated
meets **all three**:

1. External I/O you cannot control (network, hardware, endpoints)
2. Non-deterministic or slow (>1 s per invocation)
3. Unavailable in CI (licensed software, physical devices)

If the dependency can be made fast and deterministic, use it directly.

## Why Bytecode Manipulation Is a Liability

Mock frameworks that rewrite class files at load time (ByteBuddy agents,
cglib, Java agents) create coupling to JVM internals that breaks across:

- **JVM major versions** — bytecode format changes require framework updates
  before you can upgrade the JDK
- **JPMS module enforcement** — reflective access restrictions tighten with
  each release; `--add-opens` flags are scar tissue
- **Preview features** — class file semantics change between releases;
  mock frameworks tested against stable may silently misbehave
- **GraalVM native image** — runtime bytecode generation is impossible

Designing tests that avoid this dependency is not purism — it is
engineering for a platform that evolves on a six-month cadence.

## Frameworks and Libraries

| Dependency | Version | Purpose |
|------------|---------|---------|
| JUnit 5 (Jupiter) | 5.11.4+ | Test lifecycle, `@TempDir`, `@Tag`, `@Nested`, `@ParameterizedTest` |
| AssertJ | 3.27.3+ | Fluent assertions — the only assertion library used |
| Testcontainers | 2.0.4+ | Docker-based integration tests |
| JaCoCo | 0.8.14+ | Line and branch coverage reporting |

Do not add Hamcrest, Truth, or JUnit 4 vintage. AssertJ is the single
assertion vocabulary.

## Current Test Architecture

### ike-maven-plugin (29 source files, 26 test files, 401 tests)

| Pattern | Files | Purpose |
|---------|-------|---------|
| `*SupportTest.java` | 8 | Pure function testing with `@TempDir` |
| `*MojoTest.java` | 2 | Dry-run parameter validation |
| `*IntegrationTest.java` | 13 | Real git/Maven/Docker subprocesses |
| `ContainerTestSupport.java` | 1 | Base class for Docker tests |
| `TestWorkspaceHelper.java` | 1 | Multi-component workspace fixture factory |

### ike-workspace-model (10 source files, 4 test files, 57 tests)

ManifestReader, ManifestWriter, WorkspaceGraph, and VersionSupport
are directly tested. Data classes (Component, Manifest, etc.) are
tested indirectly through reader/writer round-trips.

## Test Patterns

### Extract and Test (primary pattern)

Extract logic from Mojos into pure `*Support` classes:

```
Mojo (I/O orchestration)  →  *Support (pure logic)  →  *SupportTest
```

Existing examples: `ReleaseSupport`, `CheckBranchSupport`, `VerifySupport`.

### Temp Directory Fixtures

Use JUnit 5 `@TempDir` for file system isolation:

```java
@Test
void findComponentFromSubmodule(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("workspace.yaml"), "...");
    // Test against real file system, auto-cleaned
}
```

### Real Subprocess Testing

Git and Maven operations use `ProcessBuilder` via `ReleaseSupport.exec()`
and `ReleaseSupport.execCapture()`. This catches real integration failures
that mocked process calls would miss.

### Testcontainers for Infrastructure

| Test Class | Container | Purpose |
|------------|-----------|---------|
| `SshDeployIntegrationTest` | `openssh-server` | SSH site deployment |
| `NexusDeployIntegrationTest` | `reposilite:3.5.19` | Maven artifact deployment |
| `GitRemoteIntegrationTest` | Custom SSH Git | Remote git operations |

Tag: `@Tag("container")`, excluded by default.

### Dry-Run Mode

Mojos supporting `--dryRun` can be unit-tested by setting `dryRun = true`
and verifying no side effects. Tests real Mojo logic without subprocesses.

## Naming Conventions

| Category | Pattern | Example |
|----------|---------|---------|
| Unit tests | `*Test.java` | `ReleaseSupportTest.java` |
| Integration tests | `*IntegrationTest.java` | `GitIntegrationTest.java` |
| Container tests | `@Tag("container")` | `SshDeployIntegrationTest.java` |
| Support tests | `*SupportTest.java` | `CheckBranchSupportTest.java` |
| Test helpers | No `Test` suffix | `TestWorkspaceHelper.java` |

Method names describe behavior: `deriveReleaseVersion_stripsSnapshot()`,
`shouldRejectNegativeLatitude()` — never `test1()`.

## Coverage

- **Target**: 80%+ line coverage (JaCoCo)
- **Current**: 81% line coverage, 458 tests, zero mocks
- **Pure logic**: 90%+ expected
- **Mojo orchestration**: covered via integration tests

## Test Execution

```bash
mvn verify                                    # unit tests only
mvn verify -Dsurefire.excludedGroups=""        # include container tests
mvn verify -Dsurefire.groups=container         # container tests only
# Coverage report: target/site/jacoco/index.html
```
