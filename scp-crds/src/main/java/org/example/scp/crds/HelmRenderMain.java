package org.example.scp.crds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static java.nio.file.StandardCopyOption.*;

/**
 * Renders the Helm chart (scp-crds/helm/scp-operator) using a Helm Docker image
 * and writes Kubernetes manifests to scp-crds/target/generated-k8s-sources without any post-processing.
 *
 * The chart now provides dedicated template files:
 *  - templates/crd.yaml
 *  - templates/rbac.yaml
 *  - templates/deployment.yaml
 *
 * We leverage `helm template --output-dir` so Helm writes rendered files with the same names and we just copy them out.
 */
public class HelmRenderMain {
    private static final Logger log = LoggerFactory.getLogger(HelmRenderMain.class);

    public static void main(String[] args) throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path moduleDir = projectRoot.resolve("scp-crds");
        Path chartDir = moduleDir.resolve("helm/scp-operator");
        Path outDir = moduleDir.resolve("target/generated-k8s-sources");
        Files.createDirectories(outDir);

        log.info("Rendering Helm chart from {} using --output-dir and copying files", chartDir);

        renderWithOutputDirAndCopy(chartDir, outDir);

        log.info("Generated manifests under {} (crd.yaml, rbac.yaml, deployment.yaml)", outDir);
        System.exit(0);
    }

    private static void renderWithOutputDirAndCopy(Path chartDir, Path hostOutDir) throws IOException {
        DockerImageName helmImg = DockerImageName.parse("alpine/helm:3.14.4");

        Path chartAbs = chartDir.toAbsolutePath();
        if (!Files.exists(chartAbs.resolve("Chart.yaml"))) {
            throw new IOException("Chart.yaml not found at " + chartAbs);
        }

        // Temporary host directory where Helm will render files directly (via bind mount)
        Path tmpRender = Files.createTempDirectory("helm-render-out-");

        try (GenericContainer<?> c = new GenericContainer<>(helmImg)
                // Writable helm dirs inside the container
                .withEnv("HELM_CACHE_HOME", "/tmp/helm/cache")
                .withEnv("HELM_CONFIG_HOME", "/tmp/helm/config")
                .withEnv("HELM_DATA_HOME", "/tmp/helm/data")
                // Bind mounts: chart in RO, render dir in RW
                .withFileSystemBind(chartAbs.toString(), "/work/chart", BindMode.READ_ONLY)
                .withFileSystemBind(tmpRender.toAbsolutePath().toString(), "/work/render", BindMode.READ_WRITE)
                // Run helm as a one-shot container and check exit code
                .withCommand("template", "scp-operator", "/work/chart", "--output-dir", "/work/render")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {

            log.info("Running helm template as one-shot container with bind mounts");
            c.start();
            // Container exits automatically after running helm; OneShot strategy ensures non-zero exits fail start()
        } catch (Exception e) {
            // Include tmpRender path for debugging
            throw new IOException("helm template container failed. Render dir: " + tmpRender.toAbsolutePath(), e);
        }

        // After the container finishes, expected files should be present on host in tmpRender
        Path renderedBase = tmpRender.resolve("scp-operator").resolve("templates");
        Path crd = renderedBase.resolve("crd.yaml");
        Path rbac = renderedBase.resolve("rbac.yaml");
        Path dep = renderedBase.resolve("deployment.yaml");

        if (!Files.isRegularFile(crd) || !Files.isRegularFile(rbac) || !Files.isRegularFile(dep)) {
            String listing;
            try {
                listing = Files.walk(tmpRender).map(Path::toString).reduce((a,b) -> a + "\n" + b).orElse("<empty>");
            } catch (Exception ex) {
                listing = "<error listing tmpRender>";
            }
            throw new IOException("Expected rendered files not found under " + renderedBase.toAbsolutePath() + 
                    ". Current contents under temp dir:\n" + listing);
        }

        // Copy to target output directory
        Files.copy(crd, hostOutDir.resolve("crd.yaml"), REPLACE_EXISTING);
        Files.copy(rbac, hostOutDir.resolve("rbac.yaml"), REPLACE_EXISTING);
        Files.copy(dep, hostOutDir.resolve("deployment.yaml"), REPLACE_EXISTING);

        // Cleanup temp render directory
        try {
            Files.walk(tmpRender)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount()) // delete children first
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
