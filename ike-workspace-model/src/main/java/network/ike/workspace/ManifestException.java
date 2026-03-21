package network.ike.workspace;

/**
 * Thrown when a workspace manifest cannot be read or has invalid structure.
 */
public class ManifestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ManifestException(String message) {
        super(message);
    }

    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
