package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copy the selected renderer's PDF to the default {@code pdf/} directory.
 *
 * <p>After multiple PDF renderers produce output in their own subdirectories
 * (e.g., {@code pdf-prince/}, {@code pdf-prawn/}), this goal copies the
 * preferred renderer's PDF to {@code pdf/} as the canonical output.
 *
 * <p>Replaces the {@code cp} exec-maven-plugin call with a cross-platform
 * Java goal. Gracefully skips if the source PDF does not exist (e.g., when
 * the renderer failed or was not enabled).
 *
 * <p>Usage:
 * <pre>{@code
 * <execution>
 *     <id>copy-default-pdf</id>
 *     <phase>verify</phase>
 *     <goals><goal>copy-default-pdf</goal></goals>
 * </execution>
 * }</pre>
 */
@Mojo(name = "copy-default-pdf",
      defaultPhase = LifecyclePhase.VERIFY,
      threadSafe = true)
public class CopyDefaultPdfMojo extends AbstractMojo {

    /** Root output directory (e.g., target/ike-doc). */
    @Parameter(property = "ike.doc.output.directory",
               defaultValue = "${project.build.directory}/ike-doc",
               required = true)
    File outputDirectory;

    /** Which renderer to use as the default PDF source. */
    @Parameter(property = "ike.pdf.default", defaultValue = "prince")
    String defaultRenderer;

    /** Output document name (without extension). */
    @Parameter(property = "ike.document.name",
               defaultValue = "${project.artifactId}")
    String documentName;

    /** Skip execution. */
    @Parameter(property = "ike.skip.pdf-default", defaultValue = "false")
    boolean skip;

    /** Creates this goal instance. */
    public CopyDefaultPdfMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().debug("copy-default-pdf: skipped");
            return;
        }

        Path source = outputDirectory.toPath()
                .resolve("pdf-" + defaultRenderer)
                .resolve(documentName + ".pdf");
        Path destDir = outputDirectory.toPath().resolve("pdf");
        Path dest = destDir.resolve(documentName + ".pdf");

        if (!Files.isRegularFile(source)) {
            getLog().warn("copy-default-pdf: source not found, skipping — " + source);
            return;
        }

        try {
            Files.createDirectories(destDir);
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            getLog().info("copy-default-pdf: " + source.getFileName()
                    + " → pdf/ (from " + defaultRenderer + ")");
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to copy default PDF", e);
        }
    }
}
