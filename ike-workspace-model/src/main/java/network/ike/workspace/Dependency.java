package network.ike.workspace;

/**
 * An inter-repository dependency declared in a component's
 * {@code depends-on} list.
 *
 * @param component    the name of the depended-on component
 * @param relationship the nature of the dependency ("build" or "content")
 */
public record Dependency(
        String component,
        String relationship
) {}
