package network.ike.workspace;

/**
 * Default values applied to components that omit a field.
 *
 * @param branch the default git branch (typically "main")
 */
public record Defaults(
        String branch
) {}
