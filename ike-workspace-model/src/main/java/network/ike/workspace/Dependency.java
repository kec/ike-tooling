package network.ike.workspace;

/**
 * An inter-repository dependency declared in a component's
 * {@code depends-on} list.
 *
 * @param component       the name of the depended-on component
 * @param relationship    the nature of the dependency ("build", "content", or "tool-time")
 * @param versionProperty optional POM property name that tracks the upstream
 *                        component's version (e.g., "ike-maven-plugin.version").
 *                        Used by {@code ike:ws-release} to update version
 *                        references after releasing an upstream component.
 *                        Null if no property tracking is needed.
 */
public record Dependency(
        String component,
        String relationship,
        String versionProperty
) {
    /** Two-arg constructor for backwards compatibility (no version-property). */
    public Dependency(String component, String relationship) {
        this(component, relationship, null);
    }
}
