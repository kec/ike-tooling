package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link ReleaseMojo} using real temp git repos.
 *
 * <p>Each test creates a release-ready project in a temporary directory,
 * configures the Mojo fields directly (package-private access), and
 * exercises the release workflow. External phases (Nexus, SSH, GitHub)
 * are expected to fail — tests verify local git state only.
 */
class ReleaseIntegrationTest {

    @TempDir
    Path tempDir;

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    void release_dryRun_noGitChanges() throws Exception {
        createReleaseProject(tempDir);

        String pomBefore = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        String headBefore = execCapture(tempDir, "git", "rev-parse", "HEAD");
        String tagsBefore = execCapture(tempDir, "git", "tag", "-l");

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.dryRun = true;
        mojo.skipVerify = true;
        mojo.deploySite = false;

        mojo.execute();

        // No tags created
        String tagsAfter = execCapture(tempDir, "git", "tag", "-l");
        assertThat(tagsAfter).isEqualTo(tagsBefore);

        // No new commits
        String headAfter = execCapture(tempDir, "git", "rev-parse", "HEAD");
        assertThat(headAfter).isEqualTo(headBefore);

        // POM unchanged
        String pomAfter = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomAfter).isEqualTo(pomBefore);
    }

    @Test
    void release_wrongBranch_throws() throws Exception {
        createReleaseProject(tempDir);
        exec(tempDir, "git", "checkout", "-b", "develop");

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.deploySite = false;
        mojo.skipVerify = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("main");
    }

    @Test
    void release_allowBranch_overridesCheck() throws Exception {
        createReleaseProject(tempDir);
        exec(tempDir, "git", "checkout", "-b", "develop");

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.allowBranch = "develop";
        mojo.skipVerify = true;
        mojo.deploySite = false;

        // The branch check should pass. The mojo will proceed into
        // the release workflow and may fail later (e.g., no remote,
        // no Maven wrapper). We just verify the branch check passed
        // by confirming the exception is NOT about the branch.
        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Acceptable — but must NOT be a branch check failure
            assertThat(e.getMessage()).doesNotContain("Must be on 'develop' branch");
        }
    }

    @Test
    void release_localPhase_createsTagAndBumps() throws Exception {
        createReleaseProject(tempDir);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.skipVerify = true;
        mojo.deploySite = false;

        // The local phase should complete: branch, version, tag, merge, bump.
        // The external phase will fail (no remote, no Nexus). Catch and
        // verify git state regardless.
        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Expected — external phase failure
        }

        // Tag v1.0.0 should exist
        String tags = execCapture(tempDir, "git", "tag", "-l", "v1.0.0");
        assertThat(tags.strip()).isEqualTo("v1.0.0");

        // Should be on main branch
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch.strip()).isEqualTo("main");

        // POM version should be bumped to 1.0.1-SNAPSHOT
        String pomContent = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomContent).contains("<version>1.0.1-SNAPSHOT</version>");
    }

    // ── SNAPSHOT release version rejection ─────────────────────────

    @Test
    void release_snapshotReleaseVersion_throws() throws Exception {
        createReleaseProject(tempDir);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.releaseVersion = "1.0.0-SNAPSHOT";
        mojo.skipVerify = true;
        mojo.deploySite = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("must not contain -SNAPSHOT");
    }

    // ── Next version enforcement ────────────────────────────────────

    @Test
    void release_nextVersionNotSnapshot_throws() throws Exception {
        createReleaseProject(tempDir);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.releaseVersion = "1.0.0";
        mojo.nextVersion = "1.0.1";  // missing -SNAPSHOT
        mojo.skipVerify = true;
        mojo.deploySite = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("must end with -SNAPSHOT");
    }

    // ── Release branch already exists ───────────────────────────────

    @Test
    void release_branchAlreadyExists_throws() throws Exception {
        createReleaseProject(tempDir);
        // Create the release branch beforehand
        exec(tempDir, "git", "branch", "release/1.0.0");

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.releaseVersion = "1.0.0";
        mojo.skipVerify = true;
        mojo.deploySite = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("already exists");
    }

    // ── Version defaulting ──────────────────────────────────────────

    @Test
    void release_noExplicitVersion_derivesFromPom() throws Exception {
        createReleaseProject(tempDir);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.dryRun = true;
        mojo.skipVerify = true;
        mojo.deploySite = false;

        mojo.execute();

        // After dry run, releaseVersion should be derived from
        // 1.0.0-SNAPSHOT -> 1.0.0
        assertThat(mojo.releaseVersion).isEqualTo("1.0.0");
        assertThat(mojo.nextVersion).isEqualTo("1.0.1-SNAPSHOT");
    }

    @Test
    void release_explicitReleaseVersion_used() throws Exception {
        createReleaseProject(tempDir);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.dryRun = true;
        mojo.releaseVersion = "42";
        mojo.skipVerify = true;
        mojo.deploySite = false;

        mojo.execute();

        assertThat(mojo.releaseVersion).isEqualTo("42");
        // nextVersion should be derived from 42
        assertThat(mojo.nextVersion).isEqualTo("43-SNAPSHOT");
    }

    // ── Dirty worktree rejection ────────────────────────────────────

    @Test
    void release_dirtyWorktree_throws() throws Exception {
        createReleaseProjectWithTrackedFile(tempDir);
        // Modify a tracked file (untracked files are not caught by git diff)
        Files.writeString(tempDir.resolve("README.txt"), "modified content",
                StandardCharsets.UTF_8);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.skipVerify = true;
        mojo.deploySite = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("unstaged");
    }

    // ── Local phase with ${project.version} resolution ──────────────

    @Test
    void release_projectVersionRefs_resolvedAndRestored() throws Exception {
        // Create a project with a sub-module that uses ${project.version}
        createReleaseProjectWithSubModule(tempDir);

        ReleaseMojo mojo = new ReleaseMojo();
        mojo.baseDir = tempDir.toFile();
        mojo.skipVerify = true;
        mojo.deploySite = false;

        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            // Expected — external phase failure
        }

        // After release + merge + bump, the sub-module POM should be
        // restored with ${project.version} (from backup restoration)
        // and the root version bumped to next SNAPSHOT.
        String subPom = Files.readString(
                tempDir.resolve("sub/pom.xml"), StandardCharsets.UTF_8);
        assertThat(subPom).contains("${project.version}");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Create a release-ready project: git repo on main with a clean
     * worktree and a POM at version 1.0.0-SNAPSHOT.
     */
    private void createReleaseProject(Path dir) throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);

        exec(dir, "git", "init", "-b", "main");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

    /**
     * Create a release-ready project with an extra tracked file.
     */
    private void createReleaseProjectWithTrackedFile(Path dir) throws Exception {
        createReleaseProject(dir);
        Files.writeString(dir.resolve("README.txt"), "readme content",
                StandardCharsets.UTF_8);
        exec(dir, "git", "add", "README.txt");
        exec(dir, "git", "commit", "-m", "Add README");
    }

    /**
     * Create a release-ready project with a sub-module that uses
     * ${project.version} for dependency version references.
     */
    private void createReleaseProjectWithSubModule(Path dir) throws Exception {
        String rootPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>sub</module>
                    </modules>
                </project>
                """;
        Files.writeString(dir.resolve("pom.xml"), rootPom, StandardCharsets.UTF_8);

        Files.createDirectories(dir.resolve("sub"));
        String subPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.test</groupId>
                            <artifactId>other</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(dir.resolve("sub/pom.xml"), subPom, StandardCharsets.UTF_8);

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
