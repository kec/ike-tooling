package network.ike.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionSupportTest {

    // ── stripSnapshot ───────────────────────────────────────────────

    @Test
    void stripSnapshotFromVersion() {
        assertThat(VersionSupport.stripSnapshot("1.1.0-SNAPSHOT"))
                .isEqualTo("1.1.0");
    }

    @Test
    void stripSnapshotFromBranchQualified() {
        assertThat(VersionSupport.stripSnapshot("1.1.0-my-feature-SNAPSHOT"))
                .isEqualTo("1.1.0-my-feature");
    }

    @Test
    void stripSnapshotFromNonSnapshot() {
        assertThat(VersionSupport.stripSnapshot("1.1.0"))
                .isEqualTo("1.1.0");
    }

    // ── deriveNextSnapshot ──────────────────────────────────────────

    @Test
    void nextSnapshotIncrementsPatch() {
        assertThat(VersionSupport.deriveNextSnapshot("1.1.0"))
                .isEqualTo("1.1.1-SNAPSHOT");
    }

    @Test
    void nextSnapshotFromSimpleVersion() {
        assertThat(VersionSupport.deriveNextSnapshot("2"))
                .isEqualTo("3-SNAPSHOT");
    }

    @Test
    void nextSnapshotFromSnapshotVersion() {
        assertThat(VersionSupport.deriveNextSnapshot("1.1.0-SNAPSHOT"))
                .isEqualTo("1.1.1-SNAPSHOT");
    }

    @Test
    void nextSnapshotFromTwoSegment() {
        assertThat(VersionSupport.deriveNextSnapshot("24"))
                .isEqualTo("25-SNAPSHOT");
    }

    // ── safeBranchName ──────────────────────────────────────────────

    @Test
    void safeBranchNameReplacesSlash() {
        assertThat(VersionSupport.safeBranchName("feature/shield-terminology"))
                .isEqualTo("feature-shield-terminology");
    }

    @Test
    void safeBranchNameNoSlash() {
        assertThat(VersionSupport.safeBranchName("develop"))
                .isEqualTo("develop");
    }

    // ── branchQualifiedVersion ──────────────────────────────────────

    @Test
    void branchQualifiedVersionForFeature() {
        assertThat(VersionSupport.branchQualifiedVersion(
                "1.2.0-SNAPSHOT", "feature/my-work"))
                .isEqualTo("1.2.0-feature-my-work-SNAPSHOT");
    }

    @Test
    void branchQualifiedVersionForMain() {
        assertThat(VersionSupport.branchQualifiedVersion(
                "1.2.0-SNAPSHOT", "main"))
                .isEqualTo("1.2.0-SNAPSHOT");
    }

    @Test
    void branchQualifiedVersionFromNonSnapshot() {
        assertThat(VersionSupport.branchQualifiedVersion("1.2.0", "main"))
                .isEqualTo("1.2.0-SNAPSHOT");
    }

    @Test
    void branchQualifiedStripsExistingQualifier() {
        assertThat(VersionSupport.branchQualifiedVersion(
                "1.2.0-old-feature-SNAPSHOT", "feature/new-work"))
                .isEqualTo("1.2.0-feature-new-work-SNAPSHOT");
    }

    // ── extractNumericBase ──────────────────────────────────────────

    @Test
    void extractNumericBaseStripsQualifier() {
        assertThat(VersionSupport.extractNumericBase("1.2.0-my-feature"))
                .isEqualTo("1.2.0");
    }

    @Test
    void extractNumericBasePreservesPlain() {
        assertThat(VersionSupport.extractNumericBase("1.2.0"))
                .isEqualTo("1.2.0");
    }

    @Test
    void extractNumericBaseSimpleVersion() {
        assertThat(VersionSupport.extractNumericBase("24"))
                .isEqualTo("24");
    }

    // ── isSnapshot / isBranchQualified ──────────────────────────────

    @Test
    void isSnapshotTrue() {
        assertThat(VersionSupport.isSnapshot("1.0-SNAPSHOT")).isTrue();
    }

    @Test
    void isSnapshotFalse() {
        assertThat(VersionSupport.isSnapshot("1.0")).isFalse();
    }

    @Test
    void isBranchQualifiedTrue() {
        assertThat(VersionSupport.isBranchQualified("1.0-my-feature-SNAPSHOT"))
                .isTrue();
    }

    @Test
    void isBranchQualifiedFalseForPlainSnapshot() {
        assertThat(VersionSupport.isBranchQualified("1.0-SNAPSHOT"))
                .isFalse();
    }

    @Test
    void isBranchQualifiedFalseForRelease() {
        assertThat(VersionSupport.isBranchQualified("1.0")).isFalse();
    }
}
