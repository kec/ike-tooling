package network.ike.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Maven artifact deployment against a real
 * repository manager. Uses Reposilite (lightweight Maven repo,
 * starts in seconds) instead of Nexus (500MB+, 60s+ startup).
 *
 * <p>Verifies that deploy works and that duplicate release uploads
 * are rejected (the bug that burned version 24 of ike-pipeline).
 *
 * <p>Requires Docker. Excluded from default build; run with
 * {@code mvn verify -Dsurefire.excludedGroups= -Dsurefire.groups=container}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NexusDeployIntegrationTest extends ContainerTestSupport {

    private static String repoBaseUrl;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> repoContainer =
            new GenericContainer<>(DockerImageName.parse("dzikoysk/reposilite:3.5.19"))
                    .withExposedPorts(8080)
                    .withEnv("REPOSILITE_OPTS", "--token admin:secret")
                    .waitingFor(new HttpWaitStrategy()
                            .forPort(8080)
                            .forPath("/")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(30)));

    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startContainer() {
        repoContainer.start();
        int port = repoContainer.getMappedPort(8080);
        repoBaseUrl = "http://localhost:" + port;
    }

    @AfterAll
    static void stopContainer() {
        repoContainer.stop();
    }

    @Test
    @Order(1)
    void repoContainer_isRunning() {
        assertThat(repoContainer.isRunning()).isTrue();
    }

    @Test
    @Order(2)
    void canQueryRepoStatus() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(repoBaseUrl + "/api/status/instance"))
                        .header("Authorization", authHeader())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void canDeployArtifact() throws Exception {
        byte[] fakeJar = "PK\003\004fake-jar-content".getBytes();

        int status = deployArtifact(
                "releases",
                "network/ike/test-artifact/1.0/test-artifact-1.0.jar",
                fakeJar);

        assertThat(status)
                .as("Deploy should succeed (200 or 201)")
                .isBetween(200, 201);
    }

    @Test
    @Order(4)
    void canDeployPom() throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike</groupId>
                    <artifactId>test-artifact</artifactId>
                    <version>1.0</version>
                </project>
                """;

        int status = deployArtifact(
                "releases",
                "network/ike/test-artifact/1.0/test-artifact-1.0.pom",
                pom.getBytes());

        assertThat(status)
                .as("POM deploy should succeed")
                .isBetween(200, 201);
    }

    @Test
    @Order(5)
    void canResolveDeployedArtifact() throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(repoBaseUrl
                                + "/releases/network/ike/test-artifact/1.0/test-artifact-1.0.jar"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body())).contains("fake-jar-content");
    }

    @Test
    @Order(6)
    void redeployingSameVersionIsRejected() throws Exception {
        byte[] differentContent = "PK\003\004different-content".getBytes();

        int status = deployArtifact(
                "releases",
                "network/ike/test-artifact/1.0/test-artifact-1.0.jar",
                differentContent);

        // Reposilite rejects duplicate release artifacts
        assertThat(status)
                .as("Redeploy of release artifact should be rejected (400 or 409)")
                .isIn(400, 409);
    }

    @Test
    @Order(7)
    void canDeployDifferentVersion() throws Exception {
        byte[] content = "PK\003\004version-2".getBytes();

        int status = deployArtifact(
                "releases",
                "network/ike/test-artifact/2.0/test-artifact-2.0.jar",
                content);

        assertThat(status)
                .as("New version should deploy")
                .isBetween(200, 201);
    }

    @Test
    @Order(8)
    void snapshotRepoAcceptsTimestampedVersions() throws Exception {
        // Maven deploys SNAPSHOTs with unique timestamps, not overwriting
        byte[] v1 = "snapshot-v1".getBytes();
        byte[] v2 = "snapshot-v2".getBytes();

        int first = deployArtifact("snapshots",
                "network/ike/snap-test/1.0-SNAPSHOT/snap-test-1.0-20260321.120000-1.jar", v1);
        assertThat(first).as("First snapshot deploy").isBetween(200, 201);

        int second = deployArtifact("snapshots",
                "network/ike/snap-test/1.0-SNAPSHOT/snap-test-1.0-20260321.120001-2.jar", v2);
        assertThat(second).as("Second snapshot deploy with different timestamp")
                .isBetween(200, 201);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private int deployArtifact(String repo, String path, byte[] content)
            throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(repoBaseUrl + "/" + repo + "/" + path))
                        .header("Authorization", authHeader())
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private static String authHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
                "admin:secret".getBytes());
    }
}
