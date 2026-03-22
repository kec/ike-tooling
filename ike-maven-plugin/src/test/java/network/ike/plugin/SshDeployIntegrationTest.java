package network.ike.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SSH-based site deployment using a real
 * SSH server in a Docker container. Tests the stage-and-swap
 * deployment logic from ReleaseSupport against a real SSH daemon.
 *
 * <p>Uses key-based auth (no sshpass dependency). An ephemeral
 * RSA keypair is generated per test run and mounted into the
 * container.
 *
 * <p>Requires Docker. Excluded from default build; run with
 * {@code mvn verify -Dgroups=container}.
 */
class SshDeployIntegrationTest extends ContainerTestSupport {

    private static final String SSH_USER = "testuser";
    private static Path privateKeyFile;
    private static GenericContainer<?> sshContainer;
    private static int sshPort;

    @BeforeAll
    static void startContainer() throws Exception {
        // Generate ephemeral keypair
        Path keyDir = Files.createTempDirectory("ssh-test-keys");
        privateKeyFile = keyDir.resolve("id_rsa");
        Path publicKeyFile = keyDir.resolve("id_rsa.pub");

        // Use ssh-keygen (available on macOS, Linux, Windows with Git)
        Process keygen = new ProcessBuilder(
                "ssh-keygen", "-t", "rsa", "-b", "2048",
                "-f", privateKeyFile.toString(),
                "-N", "",  // no passphrase
                "-q")      // quiet
                .redirectErrorStream(true)
                .start();
        keygen.getInputStream().readAllBytes();
        assertThat(keygen.waitFor()).as("ssh-keygen failed").isZero();

        // Restrict private key permissions
        Files.setPosixFilePermissions(privateKeyFile,
                PosixFilePermissions.fromString("rw-------"));

        // Use linuxserver/openssh-server for full shell access
        // (atmoz/sftp is SFTP-only with chroot, no shell commands)
        sshContainer = new GenericContainer<>(
                DockerImageName.parse("lscr.io/linuxserver/openssh-server:latest"))
                .withExposedPorts(2222)
                .withEnv("PUID", "1000")
                .withEnv("PGID", "1000")
                .withEnv("USER_NAME", SSH_USER)
                .withEnv("PUBLIC_KEY", Files.readString(publicKeyFile).trim())
                .withEnv("SUDO_ACCESS", "true")
                .withEnv("PASSWORD_ACCESS", "false");

        sshContainer.start();
        sshPort = sshContainer.getMappedPort(2222);
    }

    @AfterAll
    static void stopContainer() {
        if (sshContainer != null) sshContainer.stop();
    }

    @Test
    void sshContainer_startsAndAcceptsConnections() {
        assertThat(sshContainer.isRunning()).isTrue();
        assertThat(sshPort).isGreaterThan(0);
    }

    @Test
    void canExecuteRemoteCommand() throws Exception {
        String output = sshExecCapture("echo hello");
        assertThat(output.trim()).isEqualTo("hello");
    }

    @Test
    void canCreateAndListRemoteDirectory() throws Exception {
        String dir = "/tmp/site-test/test-site";
        sshExec("mkdir -p " + dir);
        sshExec("echo 'test content' > " + dir + "/index.html");

        String listing = sshExecCapture("ls " + dir);
        assertThat(listing).contains("index.html");
    }

    @Test
    void canRemoveRemoteDirectory() throws Exception {
        String dir = "/tmp/site-test/to-delete";
        sshExec("mkdir -p " + dir + " && echo test > " + dir + "/file.txt");
        assertThat(sshExecCapture("ls " + dir)).contains("file.txt");

        sshExec("rm -rf " + dir);

        int exit = sshExecRaw("test -d " + dir);
        assertThat(exit).isNotZero();
    }

    @Test
    void stageAndSwap_replacesLiveDirectory() throws Exception {
        String base = "/tmp/site-test/swap-test";
        String live = base + "/release";
        String staging = live + ".staging";
        String old = live + ".old";

        // Create initial "live" v1
        sshExec("mkdir -p " + live + " && echo v1 > " + live + "/index.html");

        // Create "staging" v2
        sshExec("mkdir -p " + staging + " && echo v2 > " + staging + "/index.html");

        // Atomic swap (mirrors ReleaseSupport.swapRemoteSiteDir logic)
        sshExec("rm -rf " + old
                + " && mv " + live + " " + old
                + " && mv " + staging + " " + live
                + " && rm -rf " + old);

        // v2 is now live
        assertThat(sshExecCapture("cat " + live + "/index.html").trim())
                .isEqualTo("v2");
        // staging gone
        assertThat(sshExecRaw("test -d " + staging)).isNotZero();
        // old gone
        assertThat(sshExecRaw("test -d " + old)).isNotZero();
    }

    @Test
    void stageAndSwap_worksOnFirstDeploy() throws Exception {
        String base = "/tmp/site-test/first-deploy";
        String live = base + "/release";
        String staging = live + ".staging";
        String old = live + ".old";

        // Only staging exists (no previous live)
        sshExec("mkdir -p " + staging + " && echo v1 > " + staging + "/index.html");

        // Swap with || true for missing live dir
        sshExec("rm -rf " + old
                + " && (mv " + live + " " + old + " 2>/dev/null || true)"
                + " && mv " + staging + " " + live
                + " && rm -rf " + old);

        assertThat(sshExecCapture("cat " + live + "/index.html").trim())
                .isEqualTo("v1");
    }

    @Test
    void stageAndSwap_eliminatesStaleFiles() throws Exception {
        String base = "/tmp/site-test/stale-test";
        String live = base + "/release";
        String staging = live + ".staging";
        String old = live + ".old";

        // v1 has two files
        sshExec("mkdir -p " + live
                + " && echo v1 > " + live + "/index.html"
                + " && echo old > " + live + "/removed-page.html");

        // v2 has only one file (removed-page.html is gone)
        sshExec("mkdir -p " + staging + " && echo v2 > " + staging + "/index.html");

        // Swap
        sshExec("rm -rf " + old
                + " && mv " + live + " " + old
                + " && mv " + staging + " " + live
                + " && rm -rf " + old);

        // v2 live, stale file gone
        assertThat(sshExecCapture("cat " + live + "/index.html").trim())
                .isEqualTo("v2");
        assertThat(sshExecRaw("test -f " + live + "/removed-page.html"))
                .isNotZero();
    }

    // ── SSH helpers (key-based, no sshpass) ──────────────────────────

    private void sshExec(String command) throws Exception {
        int exit = sshExecRaw(command);
        assertThat(exit).as("SSH command failed: " + command).isZero();
    }

    private int sshExecRaw(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost",
                command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes();
        return proc.waitFor();
    }

    private String sshExecCapture(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost",
                command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertThat(exit).as("SSH command failed: " + command).isZero();
        return output;
    }
}
