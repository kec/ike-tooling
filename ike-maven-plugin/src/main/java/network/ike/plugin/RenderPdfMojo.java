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
import java.util.ArrayList;
import java.util.List;

/**
 * Render PDFs from HTML or XSL-FO using external renderers.
 *
 * <p>Invokes Prince, Antenna House, WeasyPrint, XEP, and FOP as external
 * processes, writing output to renderer-specific subdirectories under the
 * output root. Each renderer is controlled by its own skip flag.
 *
 * <p>HTML-to-PDF renderers (Prince, AH, WeasyPrint) all consume the shared
 * {@code html/} directory. XSL-FO renderers (XEP, FOP) consume their
 * respective {@code fo-xep/} and {@code fo-fop/} directories.
 *
 * <p>Replaces the per-renderer exec-maven-plugin invocations with a single
 * cross-platform Java goal. Renderer logs are captured through Maven's
 * logger and also written to {@code target/logs/}.
 *
 * <p>Usage:
 * <pre>{@code
 * <execution>
 *     <id>render-pdf</id>
 *     <phase>package</phase>
 *     <goals><goal>render-pdf</goal></goals>
 * </execution>
 * }</pre>
 */
@Mojo(name = "render-pdf",
      defaultPhase = LifecyclePhase.PACKAGE,
      threadSafe = true)
public class RenderPdfMojo extends AbstractMojo {

    // ── Paths ─────────────────────────────────────────────────────────

    /** Root output directory (e.g., target/ike-doc). */
    @Parameter(property = "ike.doc.output.directory",
               defaultValue = "${project.build.directory}/ike-doc",
               required = true)
    File outputDirectory;

    /** Project build directory for log files. */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    File buildDirectory;

    /** Source document base name (without extension). */
    @Parameter(property = "pdf.source.document", defaultValue = "index")
    String sourceDocument;

    /** Output document name (without extension). */
    @Parameter(property = "ike.document.name",
               defaultValue = "${project.artifactId}")
    String documentName;

    /** Print CSS stylesheet path. */
    @Parameter(property = "ike.print.css",
               defaultValue = "${project.build.directory}/ike-doc/html/ike-print.css")
    File printCss;

    // ── Renderer executables ──────────────────────────────────────────

    /** Prince executable. */
    @Parameter(property = "prince.executable", defaultValue = "prince")
    String princeExecutable;

    /** Antenna House executable. */
    @Parameter(property = "antennahouse.executable", defaultValue = "AHFCmd")
    String ahExecutable;

    /** WeasyPrint executable. */
    @Parameter(property = "weasyprint.executable", defaultValue = "weasyprint")
    String weasyprintExecutable;

    /** XEP home directory. */
    @Parameter(property = "xep.home", defaultValue = "/Library/Java/xep")
    File xepHome;

    /** FOP configuration file. */
    @Parameter(property = "fop.config",
               defaultValue = "${project.build.directory}/fop-config/fop-ike.xconf")
    File fopConfig;

    /** XEP configuration file. */
    @Parameter(property = "xep.config",
               defaultValue = "${project.build.directory}/xep-config/xep-ike.xml")
    File xepConfig;

    // ── Skip flags ────────────────────────────────────────────────────

    /** Skip all rendering. */
    @Parameter(property = "ike.render.skip", defaultValue = "false")
    boolean skip;

    /** Skip Prince. */
    @Parameter(property = "ike.skip.prince", defaultValue = "false")
    boolean skipPrince;

    /** Skip Antenna House. */
    @Parameter(property = "ike.skip.ah", defaultValue = "true")
    boolean skipAh;

    /** Skip WeasyPrint. */
    @Parameter(property = "ike.skip.weasyprint", defaultValue = "true")
    boolean skipWeasyprint;

    /** Skip XEP. */
    @Parameter(property = "ike.skip.xep", defaultValue = "true")
    boolean skipXep;

    /** Skip FOP. */
    @Parameter(property = "ike.skip.fop", defaultValue = "true")
    boolean skipFop;

    /** Creates this goal instance. */
    public RenderPdfMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("render-pdf: skipped");
            return;
        }

        File logsDir = new File(buildDirectory, "logs");
        logsDir.mkdirs();

        boolean hadErrors = false;
        if (!skipPrince) hadErrors |= !renderPrince(logsDir);
        if (!skipAh) hadErrors |= !renderAh(logsDir);
        if (!skipWeasyprint) hadErrors |= !renderWeasyprint(logsDir);
        if (!skipXep) hadErrors |= !renderXep(logsDir);
        if (!skipFop) hadErrors |= !renderFop(logsDir);

        if (hadErrors) {
            getLog().warn("render-pdf: one or more renderers failed");
        }
    }

    // ── HTML-to-PDF renderers ─────────────────────────────────────────

    private boolean renderPrince(File logsDir) {
        Path html = htmlInput();
        if (!Files.isRegularFile(html)) {
            getLog().warn("render-pdf [prince]: source not found — " + html);
            return false;
        }

        File outDir = mkdirRenderer("pdf-prince");
        Path output = outDir.toPath().resolve(documentName + ".pdf");

        getLog().info("  prince: " + html.getFileName() + " → " + output.getFileName());

        return exec("prince", logsDir, "ike-renderer-prince.log",
                princeExecutable, "--silent",
                html.toString(),
                "--style", printCss.getAbsolutePath(),
                "--output", output.toString(),
                "--pdf-profile=PDF/UA-1");
    }

    private boolean renderAh(File logsDir) {
        Path html = htmlInput();
        if (!Files.isRegularFile(html)) {
            getLog().warn("render-pdf [ah]: source not found — " + html);
            return false;
        }

        File outDir = mkdirRenderer("pdf-ah");
        Path output = outDir.toPath().resolve(documentName + ".pdf");

        getLog().info("  ah: " + html.getFileName() + " → " + output.getFileName());

        return exec("ah", logsDir, "ike-renderer-ah.log",
                ahExecutable, "-cssmode",
                "-css", printCss.getAbsolutePath(),
                "-d", html.toString(),
                "-o", output.toString(),
                "-p", "@PDF/UA-1");
    }

    private boolean renderWeasyprint(File logsDir) {
        Path html = htmlInput();
        if (!Files.isRegularFile(html)) {
            getLog().warn("render-pdf [weasyprint]: source not found — " + html);
            return false;
        }

        File outDir = mkdirRenderer("pdf-weasyprint");
        Path output = outDir.toPath().resolve(documentName + ".pdf");

        getLog().info("  weasyprint: " + html.getFileName() + " → " + output.getFileName());

        return exec("weasyprint", logsDir, "ike-renderer-weasyprint.log",
                weasyprintExecutable,
                html.toString(),
                output.toString(),
                "--stylesheet", printCss.getAbsolutePath());
    }

    // ── XSL-FO renderers ─────────────────────────────────────────────

    private boolean renderXep(File logsDir) {
        Path fo = outputDirectory.toPath()
                .resolve("fo-xep")
                .resolve(sourceDocument + ".fo");
        if (!Files.isRegularFile(fo)) {
            getLog().warn("render-pdf [xep]: FO source not found — " + fo);
            return false;
        }

        File outDir = mkdirRenderer("pdf-xep");
        Path output = outDir.toPath().resolve(documentName + ".pdf");

        getLog().info("  xep: " + fo.getFileName() + " → " + output.getFileName());

        return exec("xep", logsDir, "ike-renderer-xep.log",
                "java",
                "-classpath",
                xepHome + "/lib/xep.jar:" + xepHome + "/lib/saxon.jar:" + xepHome + "/lib/saxon-xml-apis.jar",
                "-Dcom.renderx.xep.CONFIG=" + xepConfig.getAbsolutePath(),
                "com.renderx.xep.XSLDriver",
                "-fo", fo.toString(),
                "-pdf", output.toString());
    }

    private boolean renderFop(File logsDir) {
        Path fo = outputDirectory.toPath()
                .resolve("fo-fop")
                .resolve(sourceDocument + ".fo");
        if (!Files.isRegularFile(fo)) {
            getLog().warn("render-pdf [fop]: FO source not found — " + fo);
            return false;
        }

        File outDir = mkdirRenderer("pdf-fop");
        Path output = outDir.toPath().resolve(documentName + ".pdf");

        getLog().info("  fop: " + fo.getFileName() + " → " + output.getFileName());

        return exec("fop", logsDir, "ike-renderer-fop.log",
                "java",
                "-classpath", buildFopClasspath(),
                "org.apache.fop.cli.Main",
                "-r",
                "-c", fopConfig.getAbsolutePath(),
                "-fo", fo.toString(),
                "-pdf", output.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Path htmlInput() {
        return outputDirectory.toPath()
                .resolve("html")
                .resolve(sourceDocument + ".html");
    }

    private File mkdirRenderer(String subdir) {
        File dir = new File(outputDirectory, subdir);
        dir.mkdirs();
        return dir;
    }

    /**
     * Execute an external process, logging output through Maven's logger.
     *
     * @return true if process exited with code 0
     */
    private boolean exec(String renderer, File logsDir, String logFileName,
                         String... command) {
        Path logFile = logsDir.toPath().resolve(logFileName);
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();

            // Write log file
            Files.writeString(logFile, output);

            // Log non-empty output
            if (!output.isBlank()) {
                for (String line : output.split("\n")) {
                    if (exitCode != 0) {
                        getLog().error("[" + renderer + "] " + line);
                    } else {
                        getLog().debug("[" + renderer + "] " + line);
                    }
                }
            }

            if (exitCode != 0) {
                getLog().error("render-pdf [" + renderer + "]: exit code " + exitCode
                        + " — see " + logFile);
                return false;
            }
            return true;

        } catch (IOException e) {
            getLog().error("render-pdf [" + renderer + "]: failed to execute — "
                    + e.getMessage());
            try {
                Files.writeString(logFile, "Execution failed: " + e.getMessage());
            } catch (IOException ignored) {}
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLog().error("render-pdf [" + renderer + "]: interrupted");
            return false;
        }
    }

    /**
     * Build the FOP classpath from the project's runtime classpath.
     * FOP must run in a separate JVM (it calls System.exit).
     */
    private String buildFopClasspath() {
        // FOP JARs are provided as runtime-scope dependencies in the
        // pdf-fop profile. For now, use a simple classpath construction.
        // This matches the old exec-maven-plugin's <classpath/> element.
        return System.getProperty("java.class.path");
    }
}
