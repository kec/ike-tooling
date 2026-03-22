package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Inject navigation breadcrumbs and theme overrides into JaCoCo HTML reports.
 *
 * <p>Finds all HTML files in the target directory and:
 * <ul>
 *   <li>Prepends a "back to project site" link in the breadcrumb div</li>
 *   <li>Injects a CSS link to {@code ike-theme.css} for visual alignment
 *       with the project's Maven site skin</li>
 *   <li>Writes {@code ike-theme.css} into the JaCoCo resources directory</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * mvn ike:inject-breadcrumb -DtargetDir=target/site/jacoco
 * </pre>
 */
@Mojo(name = "inject-breadcrumb",
      defaultPhase = LifecyclePhase.VERIFY,
      threadSafe = true)
public class InjectBreadcrumbMojo extends AbstractMojo {

    /** Directory containing JaCoCo HTML reports. */
    @Parameter(property = "targetDir",
               defaultValue = "${project.build.directory}/site/jacoco")
    File targetDir;

    /** Relative URL for the breadcrumb link. */
    @Parameter(property = "breadcrumb.link", defaultValue = "../index.html")
    String link;

    /** Display label for the breadcrumb link. */
    @Parameter(property = "breadcrumb.label", defaultValue = "\u2190 Project Site")
    String label;

    /** Creates this goal instance. */
    public InjectBreadcrumbMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (!targetDir.isDirectory()) {
            getLog().info("inject-breadcrumb: directory does not exist, "
                    + "skipping — " + targetDir);
            return;
        }

        // Write the theme CSS into the jacoco-resources directory
        try {
            writeThemeCss(targetDir.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to write theme CSS in " + targetDir, e);
        }

        int patched = 0;
        try {
            patched = processDirectory(targetDir.toPath(), link, label);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to inject breadcrumbs in " + targetDir, e);
        }

        if (patched > 0) {
            getLog().info("inject-breadcrumb: patched " + patched
                    + " HTML file(s) in " + targetDir);
        } else {
            getLog().info("inject-breadcrumb: no breadcrumb divs found in "
                    + targetDir);
        }
    }

    /**
     * Write the IKE theme override CSS into the JaCoCo resources directory.
     * Also writes into subdirectory resource dirs so source-file pages
     * can find it.
     */
    private void writeThemeCss(Path jacocoDir) throws IOException {
        String css = generateThemeCss();

        // Root jacoco-resources/
        Path rootResources = jacocoDir.resolve("jacoco-resources");
        if (Files.isDirectory(rootResources)) {
            Files.writeString(rootResources.resolve("ike-theme.css"), css);
        }

        // Subdirectory jacoco-resources/ (package-level pages)
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jacocoDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path subResources = entry.resolve("jacoco-resources");
                    if (Files.isDirectory(subResources)) {
                        Files.writeString(
                                subResources.resolve("ike-theme.css"), css);
                    }
                }
            }
        }
    }

    /**
     * Recursively process all HTML files in a directory tree.
     *
     * @return number of files that were modified
     */
    private int processDirectory(Path dir, String breadcrumbLink,
                                 String breadcrumbLabel) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    count += processDirectory(entry, breadcrumbLink,
                            breadcrumbLabel);
                } else if (entry.toString().endsWith(".html")) {
                    String html = Files.readString(entry);
                    String patched = injectBreadcrumb(html, breadcrumbLink,
                            breadcrumbLabel);
                    patched = injectThemeCssLink(patched);
                    if (!html.equals(patched)) {
                        Files.writeString(entry, patched);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // ── Pure testable functions ──────────────────────────────────────

    /**
     * Inject a breadcrumb navigation link into JaCoCo's breadcrumb div.
     *
     * @param html  the HTML content
     * @param link  relative URL for the breadcrumb link
     * @param label display label for the link
     * @return HTML with the breadcrumb injected, or unchanged if the
     *         breadcrumb div is not present
     */
    public static String injectBreadcrumb(String html, String link,
                                          String label) {
        return html.replace(
                "<div class=\"breadcrumb\" id=\"breadcrumb\">",
                "<div class=\"breadcrumb\" id=\"breadcrumb\">"
                        + "<a href=\"" + link
                        + "\" style=\"font-weight:bold;margin-right:8px\">"
                        + label + "</a> | ");
    }

    /**
     * Inject a CSS link to the IKE theme override after the existing
     * report.css link.
     *
     * @param html the HTML content
     * @return HTML with the theme CSS link injected
     */
    public static String injectThemeCssLink(String html) {
        return html.replace(
                "report.css\" type=\"text/css\"/>",
                "report.css\" type=\"text/css\"/>"
                        + "<link rel=\"stylesheet\" "
                        + "href=\"jacoco-resources/ike-theme.css\" "
                        + "type=\"text/css\"/>");
    }

    /**
     * Generate the IKE theme override CSS for JaCoCo reports.
     *
     * <p>Overrides JaCoCo's default styling to approximate the Sentry
     * Maven Skin purple theme: dark header bar, consistent font stack,
     * purple accent colors, and improved table readability.
     *
     * @return CSS content as a string
     */
    public static String generateThemeCss() {
        return """
                /* IKE Theme Override for JaCoCo Reports */
                /* Aligns JaCoCo's default styling with the Sentry Maven Skin */

                body, td {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI",
                                 Roboto, "Helvetica Neue", Arial, sans-serif;
                    font-size: 14px;
                    color: #333;
                    margin: 0;
                    padding: 0;
                    background: #fafafa;
                }

                body {
                    padding: 20px 40px;
                    max-width: 1400px;
                    margin: 0 auto;
                }

                h1 {
                    font-size: 24px;
                    font-weight: 600;
                    color: #2d2d2d;
                    margin: 16px 0;
                    padding-bottom: 8px;
                    border-bottom: 2px solid #6f42c1;
                }

                a {
                    color: #6f42c1;
                    text-decoration: none;
                }

                a:hover {
                    color: #553098;
                    text-decoration: underline;
                }

                /* Breadcrumb bar */
                .breadcrumb {
                    background: #343a40;
                    color: #fff;
                    padding: 10px 16px;
                    border: none;
                    border-radius: 4px;
                    margin-bottom: 16px;
                    font-size: 13px;
                }

                .breadcrumb a {
                    color: #c9b3f5;
                }

                .breadcrumb a:hover {
                    color: #fff;
                }

                .breadcrumb .info {
                    float: right;
                }

                .breadcrumb .info a {
                    color: #adb5bd;
                    margin-left: 12px;
                }

                .breadcrumb .info a:hover {
                    color: #fff;
                }

                /* Coverage table */
                table.coverage {
                    width: 100%;
                    border-collapse: collapse;
                    background: #fff;
                    border: 1px solid #dee2e6;
                    border-radius: 4px;
                    overflow: hidden;
                }

                table.coverage thead {
                    background: #6f42c1;
                    color: #fff;
                }

                table.coverage thead td {
                    padding: 8px 14px 8px 8px;
                    border-bottom: 2px solid #553098;
                    font-weight: 600;
                    font-size: 13px;
                }

                table.coverage thead td.bar {
                    border-left: 1px solid #8057d4;
                }

                table.coverage thead td.ctr1,
                table.coverage thead td.ctr2 {
                    border-left: 1px solid #8057d4;
                }

                table.coverage tbody td {
                    padding: 6px 8px;
                    border-bottom: 1px solid #e9ecef;
                }

                table.coverage tbody tr:hover {
                    background: #f3effc !important;
                }

                table.coverage tbody td.bar {
                    border-left: 1px solid #f0f0f0;
                }

                table.coverage tbody td.ctr1,
                table.coverage tbody td.ctr2 {
                    border-left: 1px solid #f0f0f0;
                    padding-right: 14px;
                }

                table.coverage tfoot td {
                    padding: 8px;
                    font-weight: 600;
                    background: #f8f9fa;
                    border-top: 2px solid #dee2e6;
                }

                table.coverage tfoot td.bar {
                    border-left: 1px solid #e9ecef;
                }

                table.coverage tfoot td.ctr1,
                table.coverage tfoot td.ctr2 {
                    border-left: 1px solid #e9ecef;
                    padding-right: 14px;
                }

                /* Source code view */
                pre.source {
                    border: 1px solid #dee2e6;
                    border-radius: 4px;
                    background: #fff;
                    font-family: "SF Mono", "Fira Code", "Fira Mono",
                                 "Roboto Mono", monospace;
                    font-size: 13px;
                }

                pre.source li {
                    border-left: 1px solid #dee2e6;
                    padding-left: 4px;
                }

                /* Footer */
                .footer {
                    margin-top: 24px;
                    border-top: 1px solid #dee2e6;
                    padding-top: 8px;
                    font-size: 12px;
                    color: #6c757d;
                }

                .footer a {
                    color: #6c757d;
                }
                """;
    }
}
