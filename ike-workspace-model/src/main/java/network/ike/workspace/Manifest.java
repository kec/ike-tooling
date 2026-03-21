package network.ike.workspace;

import java.util.List;
import java.util.Map;

/**
 * The top-level workspace manifest, deserialized from {@code workspace.yaml}.
 *
 * <p>This is the typed, immutable representation of the entire manifest.
 * Use {@link ManifestReader#read} to parse YAML into this record.
 *
 * @param schemaVersion   manifest format version (currently "1.0")
 * @param generated       date string when the manifest was last updated
 * @param defaults        default values for component fields
 * @param componentTypes  named component type definitions
 * @param components      named component definitions (insertion-ordered)
 * @param groups          named groups mapping to lists of component/group names
 */
public record Manifest(
        String schemaVersion,
        String generated,
        Defaults defaults,
        Map<String, ComponentType> componentTypes,
        Map<String, Component> components,
        Map<String, List<String>> groups
) {}
