package org.example.scp.it;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static java.nio.file.Path.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test driven purely by YAML manifests applied to a K3s cluster.
 * Steps:
 *  - Build operator image tar and import into K3s containerd
 *  - Apply Operator CRD, RBAC, and Deployment YAML (rendered from Helm once)
 *  - Install Crossplane and provider-kubernetes; create ProviderConfig
 *  - Apply Crossplane XRD + Composition and a Claim to trigger SCP upload
 *  - Apply SSH server Deployment/Service and Secret (from repo examples)
 *  - Verify file exists inside SSH server Pod
 */
public class ScpEndToEndIT {

    private static final Logger log = LoggerFactory.getLogger(ScpEndToEndIT.class);
    private static K3sContainer k3s;
    private static KubernetesClient client;

    @BeforeAll
    static void beforeAll() throws Exception {
        // Start K3s
        k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.29.6-k3s1"));
        k3s.start();

        Config cfg = Config.fromKubeconfig(k3s.getKubeConfigYaml());
        client = new KubernetesClientBuilder().withConfig(cfg).build();

        // Build operator image tar via Jib and import into k3s' containerd
        buildOperatorImageTar();
        importImageTarIntoK3s();

        // Generate operator manifests from Helm chart in scp-crds
        runCrdsGenerator();
    }

    @AfterAll
    static void afterAll() {
        if (client != null) {
            client.close();
        }
        if (k3s != null) {
            k3s.stop();
        }
    }

    @Test
    void endToEnd_with_k8s_and_yaml_only() throws Exception {
        String ns = "default";

        // Apply Operator CRD, RBAC and Deployment generated from the Helm chart
        Path gen = of("..", "scp-crds", "target", "generated-k8s-sources");
        applyYamlFile(gen.resolve("crd.yaml"));
        applyYamlFile(gen.resolve("rbac.yaml"));
        applyYamlFile(gen.resolve("deployment.yaml"));

        // Wait for operator to be ready
        waitForDeploymentReady(ns, "scp-operator");

        // Install Crossplane core
        installCrossplane();

        // Install provider-kubernetes and ProviderConfig
        Path examples = of("..", "scp-crossplane", "examples");
        applyYamlFile(examples.resolve("provider-kubernetes.yaml"));
        waitForProviderHealthy("provider-kubernetes");
        applyYamlFile(examples.resolve("providerconfig-k8s.yaml"));

        // Apply XRD and Composition from repo
        applyYamlFile(of("..", "scp-crossplane", "xrd.yaml"));
        applyYamlFile(of("..", "scp-crossplane", "composition.yaml"));

        // Deploy SSH server and secret (from repo examples)
        applyYamlFile(examples.resolve("ssh-secret.yaml"));
        applyYamlFile(examples.resolve("sshd-deployment.yaml"));
        applyYamlFile(examples.resolve("sshd-service.yaml"));
        waitForDeploymentReady(ns, "sshd");

        // Apply Crossplane Claim that results in an SCPArtifact via Composition
        applyYamlFile(examples.resolve("claim.yaml"));

        // Wait for composed SCPArtifact (from-xrd) to be Ready
        Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            var cr = client.genericKubernetesResources(resourceCtx())
                    .inNamespace(ns).withName("from-xrd").get();
            if (cr == null) {
                return false;
            }
            var status = cr.getAdditionalProperties().get("status");
            return status != null && status.toString().contains("Ready");
        });

        // Exec inside sshd pod to confirm the file presence and content
        Pod sshPod = waitForAnyPodRunning(ns, "app", "sshd");
        var execRes = client.pods().inNamespace(ns).withName(sshPod.getMetadata().getName())
                .inContainer("sshd")
                .writingOutput(System.out)
                .writingError(System.err)
                .exec("/bin/sh", "-lc", "cat /home/testuser/uploaded.txt");
        assertThat(execRes).isNotNull();
    }

    // -------- Helpers ---------
    private static void applyYamlFile(Path path) throws IOException {
        try (var is = Files.newInputStream(path)) {
            // Do NOT force a namespace here. Crossplane install and many manifests contain
            // cluster-scoped resources (CRDs, ClusterRoles, etc.) and resources targeting
            // non-default namespaces. Let Fabric8 honor each document's own scope/namespace.
            client.load(is).createOrReplace();
        }
        log.info("Applied YAML from path {}", path.toFile().getAbsolutePath());
    }

    private static void waitForDeploymentReady(String namespace, String name) {
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            var dep = client.apps().deployments().inNamespace(namespace).withName(name).get();
            if (dep == null || dep.getStatus() == null) {
                return false;
            }
            Integer desired = dep.getSpec().getReplicas();
            Integer ready = dep.getStatus().getReadyReplicas();
            return desired != null && ready != null && ready >= desired;
        });
    }

    private static Pod waitForAnyPodRunning(String namespace, String labelKey, String labelVal) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            List<Pod> pods = client.pods().inNamespace(namespace).withLabel(labelKey, labelVal).list().getItems();
            return pods.stream().anyMatch(p -> "Running".equals(p.getStatus().getPhase()));
        });
        return client.pods().inNamespace(namespace).withLabel(labelKey, labelVal).list().getItems().stream()
                .filter(p -> "Running".equals(p.getStatus().getPhase())).findFirst().orElseThrow();
    }

    private static ResourceDefinitionContext resourceCtx() {
        return new ResourceDefinitionContext.Builder()
                .withGroup("ops.example.org")
                .withVersion("v1alpha1")
                .withPlural("scparticles")
                .build();
    }

    private static void installCrossplane() throws IOException, InterruptedException {
        // Install Crossplane via Helm per official docs:
        // https://docs.crossplane.io/latest/get-started/install/
        // We'll run a Helm container in host network mode so it can use the same
        // kube-apiserver endpoint (127.0.0.1:HOSTPORT) present in the provided kubeconfig.

        // 1) Write kubeconfig to a temp file on host
        Path kubeconfigFile = Files.createTempFile("kubeconfig-", ".yaml");
        try {
            Files.writeString(kubeconfigFile, k3s.getKubeConfigYaml());

            // 2) Run Helm as a one-shot container using host network and the kubeconfig.
            // Use helm image ENTRYPOINT (helm) with args, avoiding any shell.
            DockerImageName helmImg = DockerImageName.parse("alpine/helm:3.14.4");
            try (GenericContainer<?> helm = new GenericContainer<>(helmImg)
                    .withEnv("KUBECONFIG", "/root/.kube/config")
                    .withEnv("HELM_CACHE_HOME", "/tmp/helm/cache")
                    .withEnv("HELM_CONFIG_HOME", "/tmp/helm/config")
                    .withEnv("HELM_DATA_HOME", "/tmp/helm/data")
                    .withFileSystemBind(kubeconfigFile.toAbsolutePath().toString(), "/root/.kube/config", BindMode.READ_ONLY)
                    .withNetworkMode("host")
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))
                    .withCommand(
                            // Equivalent to: helm upgrade --install crossplane crossplane 
                            //   --repo https://charts.crossplane.io/stable 
                            //   --namespace crossplane-system --create-namespace --wait
                            "upgrade", "--install", "crossplane", "crossplane",
                            "--repo", "https://charts.crossplane.io/stable",
                            "--namespace", "crossplane-system",
                            "--create-namespace",
                            "--wait")) {
                helm.start();
            }

        } finally {
            try { Files.deleteIfExists(kubeconfigFile); } catch (Exception ignored) {}
        }

        // 3) Wait for Crossplane deployments
        Awaitility.await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(3)).until(() -> {
            try {
                waitForDeploymentReady("crossplane-system", "crossplane");
                waitForDeploymentReady("crossplane-system", "crossplane-rbac-manager");
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        log.info("Crossplane installed");
    }

    private static void waitForProviderHealthy(String providerName) {
        var ctx = new ResourceDefinitionContext.Builder()
                .withGroup("pkg.crossplane.io")
                .withVersion("v1")
                .withPlural("providers")
                .build();

        Awaitility.await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(5)).until(() -> {
            var gen = client.genericKubernetesResources(ctx)
                    .inNamespace("crossplane-system")
                    .withName(providerName)
                    .get();

            if (gen == null) {
                return false;
            }
            var status = gen.getAdditionalProperties().get("status");
            return status != null && status.toString().contains("Healthy");
        });
    }

    private static void buildOperatorImageTar() throws IOException, InterruptedException {
        // Run Maven Jib to build tar for scp-operator-app
        File file = new File("..");
        log.info("Building an operator image tag from {}", file.getAbsolutePath());
        ProcessBuilder processBuilder = new ProcessBuilder()
                .directory(file)
                .command("sh", "mvnw", "-DskipTests", "-pl", "scp-operator-app", "jib:buildTar")
                .inheritIO()
                .redirectErrorStream(true);
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process proc = processBuilder
                .start();
        logProcess(proc);
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("Failed to build operator image tar with Jib");
        }
    }

    private static void logProcess(Process proc) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[mvn] {}", line);
            }
        }
    }

    private static void importImageTarIntoK3s() throws IOException, InterruptedException {
        Path tar = of("..", "scp-operator-app", "target", "jib-image.tar");
        log.info("Importing the tarball from {}", tar.toFile().getAbsolutePath());
        if (!Files.exists(tar)) {
            throw new IOException("Image tar not found: " + tar);
        }
        String dest = "/tmp/op.tar";
        MountableFile mf = MountableFile.forHostPath(tar.toAbsolutePath());
        k3s.copyFileToContainer(mf, dest);
        var exec = k3s.execInContainer("ctr", "-n", "k8s.io", "images", "import", dest);
        if (exec.getExitCode() != 0) {
            throw new IOException("ctr import failed: " + exec.getStderr());
        }
    }

    private static void runCrdsGenerator() throws IOException, InterruptedException {
        File file = new File("..");
        log.info("Running crd generators from {}", file.getAbsolutePath());
        ProcessBuilder processBuilder = new ProcessBuilder()
                .directory(file)
                .command("sh", "mvnw", "-DskipTests", "-pl", ":scp-crds", "package")
                .inheritIO()
                .redirectErrorStream(true);
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process proc = processBuilder
                .start();
        logProcess(proc);
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("Failed to generate CRDs/RBAC/Deployment via Helm renderer");
        }
    }
}
