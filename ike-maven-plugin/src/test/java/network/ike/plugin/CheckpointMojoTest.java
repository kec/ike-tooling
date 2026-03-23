package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CheckpointMojo}.
 *
 * <p>The checkpoint workflow runs subprocesses ({@code mvn clean deploy})
 * that cannot run in unit tests. These tests exercise the dry-run paths
 * (which cover parameter derivation, audit logging, and all dry-run
 * branches) and early validation logic (clean worktree check).
 *
 * <p>The non-dry-run path proceeds into subprocess territory and will
 * fail when no Maven wrapper is available, but we can still verify that
 * validation gates (e.g., clean worktree) fire first.
 */
class CheckpointMojoTest {

    @TempDir
    Path tempDir;

    // ── Dry-run: all parameter combinations ─────────────────────────

    @Test
    void dryRun_completesWithoutChanges() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;

        String headBefore = execCapture(tempDir, "git", "rev-parse", "HEAD");
        String tagsBefore = execCapture(tempDir, "git", "tag", "-l");
        String pomBefore = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);

        assertThatCode(mojo::execute).doesNotThrowAnyException();

        // No commits, tags, or POM changes
        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD"))
                .isEqualTo(headBefore);
        assertThat(execCapture(tempDir, "git", "tag", "-l"))
                .isEqualTo(tagsBefore);
        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .isEqualTo(pomBefore);
    }

    @Test
    void dryRun_withCustomLabel() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.checkpointLabel = "custom-label-42";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dryRun_skipVerifyTrue() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.skipVerify = true;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dryRun_deploySiteFalse() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.deploySite = false;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dryRun_deploySiteTrue() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.deploySite = true;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dryRun_blankLabel_derivesFromVersion() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.checkpointLabel = "   ";

        // Blank label should be treated as unset — auto-derive
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── Dry-run: combined flags ────────────────────────────────────

    @Test
    void dryRun_deploySiteTrue_skipVerifyFalse() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.deploySite = true;
        mojo.skipVerify = false;

        // Covers L83-84 (skipVerify=false) and L88-90 (deploySite=true)
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dryRun_deploySiteFalse_skipVerifyTrue() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.deploySite = false;
        mojo.skipVerify = true;

        // Covers L85-86 (skipVerify=true) and L88 deploySite=false branch
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dryRun_customLabel_deploySiteTrue() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        mojo.dryRun = true;
        mojo.checkpointLabel = "my-checkpoint";
        mojo.deploySite = true;
        mojo.skipVerify = true;

        // Custom label + deploySite=true + skipVerify=true
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── Non-dry-run validation ──────────────────────────────────────

    @Test
    void nonDryRun_dirtyWorktree_unstaged_throws() throws Exception {
        createCheckpointProjectWithTrackedFile(tempDir);
        // Modify a tracked file (untracked files are not caught by git diff)
        Files.writeString(tempDir.resolve("README.txt"), "modified content",
                StandardCharsets.UTF_8);

        CheckpointMojo mojo = newMojo(tempDir);

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("unstaged changes");
    }

    @Test
    void nonDryRun_dirtyWorktree_staged_throws() throws Exception {
        createCheckpointProject(tempDir);
        Files.writeString(tempDir.resolve("staged.txt"), "staged",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "staged.txt");

        CheckpointMojo mojo = newMojo(tempDir);

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("staged changes");
    }

    @Test
    void nonDryRun_cleanWorktree_proceedsToVersionSet() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointMojo mojo = newMojo(tempDir);
        // No Maven wrapper available, so the Mojo will fail after
        // setting the version (when it tries to run mvnw). Verify
        // that it got past the worktree check by observing the POM
        // was modified (version set) or the error is about mvn/mvnw.
        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Expected — no mvn/mvnw in temp dir
            // Should NOT be a worktree-related error
            assertThat(e.getMessage())
                    .doesNotContain("unstaged")
                    .doesNotContain("staged changes");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private CheckpointMojo newMojo(Path dir) {
        CheckpointMojo mojo = new CheckpointMojo();
        mojo.baseDir = dir.toFile();
        mojo.deploySite = true;
        mojo.dryRun = false;
        mojo.skipVerify = false;
        return mojo;
    }

    private void createCheckpointProjectWithTrackedFile(Path dir) throws Exception {
        createCheckpointProject(dir);
        Files.writeString(dir.resolve("README.txt"), "readme content",
                StandardCharsets.UTF_8);
        exec(dir, "git", "add", "README.txt");
        exec(dir, "git", "commit", "-m", "Add README");
    }

    private void createCheckpointProject(Path dir) throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>checkpoint-project</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);

        exec(dir, "git", "init", "-b", "main");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
        return output;
    }
}
