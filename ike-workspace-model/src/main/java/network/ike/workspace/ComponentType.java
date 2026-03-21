package network.ike.workspace;

/**
 * Defines a category of workspace component with its build and
 * checkpoint conventions.
 *
 * @param name                the type identifier (e.g., "software", "document")
 * @param description         human-readable purpose
 * @param buildCommand        default Maven invocation (e.g., "mvn clean install")
 * @param checkpointMechanism how to record reproducible state ("git-tag" or "composite")
 */
public record ComponentType(
        String name,
        String description,
        String buildCommand,
        String checkpointMechanism
) {}
