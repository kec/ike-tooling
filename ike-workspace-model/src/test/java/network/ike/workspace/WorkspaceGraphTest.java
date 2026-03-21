package network.ike.workspace;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceGraphTest {

    private static WorkspaceGraph graph;

    @BeforeAll
    static void loadGraph() {
        Path path = Path.of("src/test/resources/workspace.yaml");
        Manifest manifest = ManifestReader.read(path);
        graph = new WorkspaceGraph(manifest);
    }

    // ── Topological Sort ────────────────────────────────────────────

    @Test
    void topologicalSortPutsDependenciesFirst() {
        List<String> sorted = graph.topologicalSort();

        // ike-bom must come before tinkar-core (tinkar depends on bom)
        assertThat(sorted.indexOf("ike-bom"))
                .isLessThan(sorted.indexOf("tinkar-core"));

        // tinkar-core before komet
        assertThat(sorted.indexOf("tinkar-core"))
                .isLessThan(sorted.indexOf("komet"));

        // komet before komet-desktop
        assertThat(sorted.indexOf("komet"))
                .isLessThan(sorted.indexOf("komet-desktop"));

        // ike-pipeline before ike-lab-documents
        assertThat(sorted.indexOf("ike-pipeline"))
                .isLessThan(sorted.indexOf("ike-lab-documents"));
    }

    @Test
    void topologicalSortIncludesAllComponents() {
        List<String> sorted = graph.topologicalSort();
        assertThat(sorted).hasSize(graph.manifest().components().size());
    }

    @Test
    void topologicalSortWithSubset() {
        Set<String> subset = Set.of("komet", "tinkar-core", "ike-bom");
        List<String> sorted = graph.topologicalSort(subset);

        assertThat(sorted).containsExactlyInAnyOrder(
                "ike-bom", "tinkar-core", "komet");
        assertThat(sorted.indexOf("ike-bom"))
                .isLessThan(sorted.indexOf("tinkar-core"));
        assertThat(sorted.indexOf("tinkar-core"))
                .isLessThan(sorted.indexOf("komet"));
    }

    // ── Cascade Analysis ────────────────────────────────────────────

    @Test
    void cascadeFromTinkarCore() {
        List<String> affected = graph.cascade("tinkar-core");

        // rocks-kb, komet, komet-desktop, ike-lab-documents all depend
        // (directly or transitively) on tinkar-core
        assertThat(affected).contains(
                "rocks-kb", "komet", "komet-desktop", "ike-lab-documents");

        // ike-bom does NOT depend on tinkar-core
        assertThat(affected).doesNotContain("ike-bom", "ike-parent");
    }

    @Test
    void cascadeFromLeafComponentIsEmpty() {
        List<String> affected = graph.cascade("komet-desktop");
        assertThat(affected).isEmpty();
    }

    @Test
    void cascadeFromIkePipeline() {
        List<String> affected = graph.cascade("ike-pipeline");
        assertThat(affected).contains(
                "ike-lab-documents", "ike-infrastructure");
    }

    @Test
    void cascadeFromUnknownComponentThrows() {
        assertThatThrownBy(() -> graph.cascade("nonexistent"))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("Unknown component");
    }

    // ── Cycle Detection ─────────────────────────────────────────────

    @Test
    void noCyclesInRealManifest() {
        List<String> cycle = graph.detectCycle();
        assertThat(cycle).isEmpty();
    }

    @Test
    void detectsCycle() {
        String yaml = """
                schema-version: "1.0"
                components:
                  a:
                    type: software
                    depends-on:
                      - component: b
                        relationship: build
                  b:
                    type: software
                    depends-on:
                      - component: a
                        relationship: build
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        WorkspaceGraph g = new WorkspaceGraph(m);
        List<String> cycle = g.detectCycle();
        assertThat(cycle).isNotEmpty();
        assertThat(cycle).contains("a", "b");
    }

    // ── Group Expansion ─────────────────────────────────────────────

    @Test
    void expandStudioGroup() {
        Set<String> components = graph.expandGroup("studio");
        assertThat(components).containsExactlyInAnyOrder(
                "ike-parent", "ike-bom", "extra-tools",
                "tinkar-core", "rocks-kb", "komet", "komet-desktop");
    }

    @Test
    void expandDocsGroup() {
        Set<String> components = graph.expandGroup("docs");
        assertThat(components).containsExactlyInAnyOrder(
                "ike-pipeline", "ike-lab-documents",
                "ike-infrastructure", "ike");
    }

    @Test
    void expandNestedGroup() {
        // "core" = [foundation, tinkar-core, rocks-kb]
        // "foundation" = [ike-parent, ike-bom]
        Set<String> components = graph.expandGroup("core");
        assertThat(components).containsExactlyInAnyOrder(
                "ike-parent", "ike-bom", "tinkar-core", "rocks-kb");
    }

    @Test
    void expandComponentNameReturnsItself() {
        Set<String> components = graph.expandGroup("tinkar-core");
        assertThat(components).containsExactly("tinkar-core");
    }

    @Test
    void expandUnknownGroupThrows() {
        assertThatThrownBy(() -> graph.expandGroup("nonexistent"))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("Unknown component or group");
    }

    // ── Verify ──────────────────────────────────────────────────────

    @Test
    void verifyRealManifestIsClean() {
        List<String> errors = graph.verify();
        assertThat(errors).isEmpty();
    }

    @Test
    void verifyDetectsMissingDependency() {
        String yaml = """
                schema-version: "1.0"
                components:
                  a:
                    type: software
                    depends-on:
                      - component: missing
                        relationship: build
                component-types:
                  software:
                    build-command: "mvn clean install"
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        WorkspaceGraph g = new WorkspaceGraph(m);
        List<String> errors = g.verify();
        assertThat(errors).anyMatch(e -> e.contains("unknown component: missing"));
    }

    @Test
    void verifyDetectsUnknownType() {
        String yaml = """
                schema-version: "1.0"
                components:
                  a:
                    type: bogus
                component-types:
                  software:
                    build-command: "mvn clean install"
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        WorkspaceGraph g = new WorkspaceGraph(m);
        List<String> errors = g.verify();
        assertThat(errors).anyMatch(e -> e.contains("unknown type: bogus"));
    }
}
