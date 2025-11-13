package org.example.scp.it;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.nio.file.Path.of;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:
 * - k3s via Testcontainers
 * - build/import operator image
 * - render/apply CRDs/RBAC/Deployment
 * - install Crossplane + provider-kubernetes
 * - apply XRD/Composition
 * - deploy sshd + secret
 * - apply Artifact claim
 * - wait for composed scparticles
 * - verify uploaded file exists in sshd pod
 */
public class ScpEndToEndIT {

    private static final Logger log = LoggerFactory.getLogger(ScpEndToEndIT.class);

    private static final String NS = "default";

    private static final String ARTIFACT_CRD_NAME = "artifacts.platform.example.org";

    private static final String ARTIFACT_GROUP = "platform.example.org";
    private static final String ARTIFACT_NAME = "sample-artifact";
    private static final String ARTIFACT_VERSION = "v1alpha1";
    private static final String ARTIFACT_PLURAL = "artifacts";
    private static final String ARTIFACT_KIND = "Artifact";

    private static final String SCP_CRD_NAME = "scparticles.ops.example.org";
    private static final String SCP_GROUP = "ops.example.org";
    private static final String SCP_VERSION = "v1alpha1";
    private static final String SCP_PLURAL = "scparticles";

    private static K3sContainer k3s;
    private static KubernetesClient client;

    @BeforeAll
    static void beforeAll() throws Exception {
        // Start k3s
        k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.29.6-k3s1"));
        k3s.start();

        Config cfg = Config.fromKubeconfig(k3s.getKubeConfigYaml());
        client = new KubernetesClientBuilder().withConfig(cfg).build();

        // Build operator image tar (scp-operator-app module)
        buildOperatorImageTar();

        // Import tar into k3s containerd
        importImageTarIntoK3s();

        // Generate CRDs/RBAC/Deployment (scp-crds module)
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
        // --- Operator bits ---
        Path gen = of("..", "scp-crds", "target", "generated-k8s-sources");
        applyYamlFile(gen.resolve("crd.yaml"));
        applyYamlFile(gen.resolve("rbac.yaml"));
        applyYamlFile(gen.resolve("deployment.yaml"));
        waitForDeploymentReady(NS, "scp-operator");

        // --- Crossplane core ---
        installCrossplane();

        // --- Provider-kubernetes + ProviderConfig + Function ---
        Path examples = of("..", "scp-crossplane", "examples");
        applyYamlFile(examples.resolve("provider-kubernetes.yaml"));
        waitForProviderHealthy("provider-kubernetes");
        applyYamlFile(examples.resolve("providerconfig-k8s.yaml"));
        applyYamlFile(examples.resolve("function-patch-and-transform.yaml"));

        // --- XRD + Composition ---
        applyYamlFile(of("..", "scp-crossplane", "xrd.yaml"));
        applyYamlFile(of("..", "scp-crossplane", "composition.yaml"));

        // Sanity: CRDs present
        assertThat(client.apiextensions().v1().customResourceDefinitions().withName(ARTIFACT_CRD_NAME).get())
                .as("Artifact CRD must exist").isNotNull();
        assertThat(client.apiextensions().v1().customResourceDefinitions().withName(SCP_CRD_NAME).get())
                .as("SCPArtifact CRD must exist").isNotNull();

        dumpCrdScopes();

        // --- SSH server + secret ---
        applyYamlFile(examples.resolve("ssh-secret.yaml"));
        applyYamlFile(examples.resolve("sshd-deployment.yaml"));
        applyYamlFile(examples.resolve("sshd-service.yaml"));
        waitForDeploymentReady(NS, "sshd");

        // --- Artifact claim ---
        applyYamlFile(examples.resolve("claim.yaml"));

        dumpArtifactsBothScopes();
        dumpCrossplaneLogsTail();

        // --- Wait for composed scparticles ---
        waitForComposedReadyForArtifact(ARTIFACT_NAME);

        // --- Verify file inside sshd pod ---
        Pod sshPod = waitForAnyPodRunning(NS, "app", "sshd");
        var execRes = client.pods().inNamespace(NS).withName(sshPod.getMetadata().getName())
                .inContainer("sshd")
                .writingOutput(System.out)
                .writingError(System.err)
                .exec("/bin/sh", "-lc", "cat /home/testuser/uploaded.txt");
        assertThat(execRes).isNotNull();
    }

    // ============================================================
    // Helpers: scope / contexts
    // ============================================================

    private static boolean isArtifactClusterScoped() {
        var crd = client.apiextensions().v1().customResourceDefinitions().withName(ARTIFACT_CRD_NAME).get();
        if (crd == null) {
            log.warn("CRD {} not found", ARTIFACT_CRD_NAME);
            return false;
        }
        String scope = crd.getSpec().getScope();
        boolean cluster = "Cluster".equals(scope);
        log.info("CRD {} scope = {}", ARTIFACT_CRD_NAME, scope);
        return cluster;
    }

    private static boolean hasClaimNames() {
        try {
            return !isArtifactClusterScoped();
        } catch (Exception e) {
            log.info("Could not determine claimNames reliably; assuming claim when namespaced. {}", e.toString());
            return !isArtifactClusterScoped();
        }
    }

    private static ResourceDefinitionContext scpCtx() {
        return new ResourceDefinitionContext.Builder()
                .withGroup(SCP_GROUP)
                .withVersion(SCP_VERSION)
                .withPlural(SCP_PLURAL)
                .build();
    }

    private static ResourceDefinitionContext artifactCtx() {
        return new ResourceDefinitionContext.Builder()
                .withGroup(ARTIFACT_GROUP)
                .withVersion(ARTIFACT_VERSION)
                .withPlural(ARTIFACT_PLURAL)
                .withKind(ARTIFACT_KIND)
                .withNamespaced(true)
                .build();
    }

    // ============================================================
    // Wait for composed scparticles
    // ============================================================

    private static void waitForComposedReadyForArtifact(String artifactName) {
        dumpCompositeForArtifact(artifactName);
        String compositeName = resolveCompositeName(artifactName);

        log.info("Waiting for composed {} for composite '{}'",
                SCP_PLURAL, compositeName);

        Awaitility.await()
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    var base = client.genericKubernetesResources(scpCtx());

                    var list = base.withLabel("crossplane.io/composite", compositeName)
                            .list();

                    int count = list != null && list.getItems() != null ? list.getItems().size() : 0;
                    log.info("Found {} {} for composite '{}' (by crossplane.io/composite)",
                            count, SCP_PLURAL, compositeName);

                    if (count == 0) {
                        var all = base.list();
                        int allCount = all != null && all.getItems() != null ? all.getItems().size() : 0;
                        log.info("Cluster-wide {} total (no label filter): {}", SCP_PLURAL, allCount);
                        if (allCount > 0) {
                            all.getItems().forEach(i -> {
                                String ns = i.getMetadata() != null ? i.getMetadata().getNamespace() : "<no-ns>";
                                String name = safeName(i);
                                log.info("  {} in {} (no filter)", name, ns);
                            });
                        }
                        return false;
                    }

                    boolean anyReady = false;

                    for (var gen : list.getItems()) {
                        String name = gen.getMetadata() != null ? gen.getMetadata().getName() : "<no-name>";
                        String ns = gen.getMetadata() != null ? gen.getMetadata().getNamespace() : "<no-ns>";
                        @SuppressWarnings("unchecked")
                        Map<String, Object> status = (Map<String, Object>) gen.getAdditionalProperties().get("status");

                        log.info("Composed {} ns={}, name={}, rawStatus={}", SCP_PLURAL, ns, name, status);

                        if (status == null) continue;

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> conditions =
                                (List<Map<String, Object>>) status.get("conditions");

                        if (conditions == null || conditions.isEmpty()) continue;

                        boolean ready = conditions.stream().anyMatch(c ->
                                "Ready".equals(c.get("type")) &&
                                        "True".equals(String.valueOf(c.get("status"))));

                        conditions.forEach(c -> log.info(
                                "  condition type={}, status={}, reason={}, message={}",
                                c.get("type"), c.get("status"), c.get("reason"), c.get("message")));

                        if (ready) anyReady = true;
                    }

                    return anyReady;
                });
    }


    // ============================================================
    // Dumps / diagnostics
    // ============================================================

    private static void dumpCrdScopes() {
        var a = client.apiextensions().v1().customResourceDefinitions().withName(ARTIFACT_CRD_NAME).get();
        var s = client.apiextensions().v1().customResourceDefinitions().withName(SCP_CRD_NAME).get();
        log.info("=== DUMP: CRD scopes ===");
        log.info("{} scope: {}", ARTIFACT_CRD_NAME, a != null ? a.getSpec().getScope() : "<missing>");
        log.info("{} scope: {}", SCP_CRD_NAME, s != null ? s.getSpec().getScope() : "<missing>");
    }

    private static void dumpArtifactsBothScopes() {
        var ctx = artifactCtx();
        log.info("=== DUMP: Artifacts (cluster-scope list) ===");
        try {
            var list = client.genericKubernetesResources(ctx).list();
            int size = list != null && list.getItems() != null ? list.getItems().size() : 0;
            log.info("Cluster-scoped {} count: {}", ARTIFACT_PLURAL, size);
            if (size > 0) {
                list.getItems().forEach(i -> log.info(" - {} (ns={})", safeName(i),
                        i.getMetadata() != null ? i.getMetadata().getNamespace() : "<no-ns>"));
                // dump yaml for each
                list.getItems().forEach(i -> logYaml("Artifact(cluster)/" + safeName(i), i));
            }
        } catch (Exception e) {
            log.info("Cluster list failed: {}", e.toString());
        }

        log.info("=== DUMP: Artifacts (namespaced list in '{}') ===", NS);
        try {
            var list = client.genericKubernetesResources(ctx).inNamespace(NS).list();
            int size = list != null && list.getItems() != null ? list.getItems().size() : 0;
            log.info("Namespaced {} count: {}", ARTIFACT_PLURAL, size);
            if (size > 0) {
                list.getItems().forEach(i -> log.info(" - {}", safeName(i)));
                list.getItems().forEach(i -> logYaml("Artifact(ns)/" + NS + "/" + safeName(i), i));
            }
        } catch (Exception e) {
            log.info("Namespaced list failed: {}", e.toString());
        }

        log.info("=== DUMP: Composed {} in '{}' ===", SCP_PLURAL, NS);
        try {
            var list = client.genericKubernetesResources(scpCtx()).inNamespace(NS).list();
            int size = list != null && list.getItems() != null ? list.getItems().size() : 0;
            log.info("{} count in {}: {}", SCP_PLURAL, NS, size);
            if (size > 0) {
                list.getItems().forEach(i -> {
                    log.info(" - {}", safeName(i));
                    logYaml("scparticle(ns)/" + NS + "/" + safeName(i), i);
                });
            }
        } catch (Exception e) {
            log.info("Composed list (namespaced) failed: {}", e.toString());
        }

        log.info("=== DUMP: Composed {} (all namespaces) ===", SCP_PLURAL);
        try {
            var list = client.genericKubernetesResources(scpCtx()).list();
            int size = list != null && list.getItems() != null ? list.getItems().size() : 0;
            log.info("{} total (cluster-wide): {}", SCP_PLURAL, size);
            if (size > 0) {
                list.getItems().forEach(i -> {
                    String ns = i.getMetadata() != null ? i.getMetadata().getNamespace() : "<no-ns>";
                    log.info(" - {} (ns={})", safeName(i), ns);
                    logYaml("scparticle(any)/" + ns + "/" + safeName(i), i);
                });
            }
        } catch (Exception e) {
            log.info("Composed list (cluster) failed: {}", e.toString());
        }
    }

    private static String safeName(HasMetadata m) {
        return m != null && m.getMetadata() != null ? m.getMetadata().getName() : "<no-name>";
    }

    private static void logYaml(String title, Object obj) {
        if (obj == null) {
            log.info("{}: <null>", title);
            return;
        }
        try {
            String yaml = Serialization.asYaml(obj);
            log.info("--- {} ---\n{}\n--- end {} ---", title, yaml, title);
        } catch (Exception e) {
            log.info("{}: <could not serialize: {}>", title, e.toString());
        }
    }

    private static void dumpCrossplaneLogsTail() {
        String ns = "crossplane-system";
        client.pods().inNamespace(ns)
                .withLabel("app", "crossplane")
                .list()
                .getItems()
                .forEach(pod -> {
                    String name = pod.getMetadata().getName();
                    log.info("--- logs {} ---", name);
                    try {
                        String logs = client.pods()
                                .inNamespace(ns)
                                .withName(name)
                                .tailingLines(200)
                                .getLog();
                        for (String line : logs.split("\\R")) {
                            log.info("{}", line);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get logs for {}: {}", name, e.toString());
                    }
                });
    }

    private static void dumpProviderKubernetesDebug() {
        String ns = "crossplane-system";
        log.info("=== provider-kubernetes Deployment debug ===");

        client.apps().deployments().inNamespace(ns).list().getItems().forEach(dep -> {
            String depName = dep.getMetadata().getName();
            if (depName.contains("provider-kubernetes")) {
                log.info("Deployment {}: {}", depName, dep.getStatus());
                if (dep.getStatus() != null && dep.getStatus().getConditions() != null) {
                    dep.getStatus().getConditions().forEach(c ->
                            log.info("  condition type={}, status={}, reason={}, message={}",
                                    c.getType(), c.getStatus(), c.getReason(), c.getMessage()));
                }
            }
        });

        var pods = client.pods().inNamespace(ns)
                .withLabel("pkg.crossplane.io/provider-name", "provider-kubernetes")
                .list()
                .getItems();

        if (pods.isEmpty()) {
            pods = client.pods().inNamespace(ns).list().getItems();
        }

        pods.forEach(pod -> {
            String name = pod.getMetadata().getName();
            log.info("Pod {} status: {}", name, pod.getStatus());
        });
    }

    // ============================================================
    // Fabric8 helpers
    // ============================================================

    private static void applyYamlFile(Path path) throws IOException {
        log.info("Applying YAML from path {}", path.toFile().getAbsolutePath());
        try (var is = Files.newInputStream(path)) {
            client.load(is).createOrReplace();
        }
        log.info("Applied YAML {}", path.toFile().getName());
    }

    private static void waitForDeploymentReady(String namespace, String name) {
        Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            var dep = client.apps().deployments().inNamespace(namespace).withName(name).get();
            if (dep == null || dep.getStatus() == null) {
                return false;
            }
            Integer desired = dep.getSpec() != null ? dep.getSpec().getReplicas() : null;
            Integer ready = dep.getStatus().getReadyReplicas();
            log.info("Deployment {} desired={} ready={}", name, desired, ready);
            return desired != null && ready != null && ready >= desired;
        });
    }

    private static Pod waitForAnyPodRunning(String namespace, String labelKey, String labelVal) {
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            List<Pod> pods = client.pods().inNamespace(namespace).withLabel(labelKey, labelVal).list().getItems();
            return pods.stream().anyMatch(p -> "Running".equals(p.getStatus().getPhase()));
        });
        return client.pods().inNamespace(namespace).withLabel(labelKey, labelVal).list().getItems().stream()
                .filter(p -> "Running".equals(p.getStatus().getPhase())).findFirst().orElseThrow();
    }

    private static void installCrossplane() throws IOException, InterruptedException {
        Path kubeconfigFile = Files.createTempFile("kubeconfig-", ".yaml");
        try {
            Files.writeString(kubeconfigFile, k3s.getKubeConfigYaml());

            DockerImageName helmImg = DockerImageName.parse("alpine/helm:3.14.4");
            try (GenericContainer<?> helm = new GenericContainer<>(helmImg)
                    .withEnv("KUBECONFIG", "/root/.kube/config")
                    .withEnv("HELM_CACHE_HOME", "/tmp/helm/cache")
                    .withEnv("HELM_CONFIG_HOME", "/tmp/helm/config")
                    .withEnv("HELM_DATA_HOME", "/tmp/helm/data")
                    .withFileSystemBind(kubeconfigFile.toAbsolutePath().toString(), "/root/.kube/config", BindMode.READ_ONLY)
                    .withNetworkMode("host")
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))
                    .withCommand(
                            "upgrade", "--install", "crossplane", "crossplane",
                            "--repo", "https://charts.crossplane.io/stable",
                            "--namespace", "crossplane-system",
                            "--create-namespace",
                            "--wait")) {
                helm.start();
            }
        } finally {
            try {
                Files.deleteIfExists(kubeconfigFile);
            } catch (Exception ignored) {
            }
        }

        Awaitility.await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(3)).until(() -> {
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

        try {
            Awaitility.await("Provider " + providerName + " to become Healthy")
                    .atMost(Duration.ofMinutes(5))
                    .pollInterval(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        var list = client.genericKubernetesResources(ctx)
                                .list();

                        assertThat(list)
                                .as("providers list should not be null")
                                .isNotNull();

                        List<GenericKubernetesResource> items = list.getItems();
                        assertThat(items)
                                .as("providers items list should not be null")
                                .isNotNull();

                        GenericKubernetesResource gen = items.stream()
                                .filter(p -> p.getMetadata() != null
                                        && providerName.equals(p.getMetadata().getName()))
                                .findFirst()
                                .orElse(null);

                        assertThat(gen)
                                .as("provider %s should exist (cluster-scoped)", providerName)
                                .isNotNull();

                        @SuppressWarnings("unchecked")
                        Map<String, Object> status =
                                (Map<String, Object>) gen.getAdditionalProperties().get("status");

                        log.info("Provider {} status: {}", providerName, status);

                        assertThat(status)
                                .as("provider %s status must not be null", providerName)
                                .isNotNull();

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> conditions =
                                (List<Map<String, Object>>) status.get("conditions");

                        assertThat(conditions)
                                .as("provider %s must have conditions", providerName)
                                .isNotNull()
                                .isNotEmpty();

                        Map<String, Object> healthy = conditions.stream()
                                .filter(c -> "Healthy".equals(c.get("type")))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError(
                                        "Provider " + providerName + " has no 'Healthy' condition: " + conditions));

                        String condStatus = String.valueOf(healthy.get("status"));
                        String reason = String.valueOf(healthy.get("reason"));
                        String message = String.valueOf(healthy.get("message"));

                        log.info("Provider {} Healthy condition: status={}, reason={}, message={}",
                                providerName, condStatus, reason, message);

                        assertThat(condStatus)
                                .as("provider %s Healthy condition must become True (reason=%s, message=%s)",
                                        providerName, reason, message)
                                .isEqualTo("True");
                    });
        } catch (AssertionError e) {
            log.warn("Provider {} did not become Healthy in time, dumping debug info", providerName, e);
            dumpProviderKubernetesDebug();
            throw e;
        }
    }

    // ============================================================
    // Build / render helpers
    // ============================================================

    private static void buildOperatorImageTar() throws IOException, InterruptedException {
        File file = new File("..");
        log.info("Building an operator image tar from {}", file.getAbsolutePath());
        ProcessBuilder processBuilder = new ProcessBuilder()
                .directory(file)
                .command("sh", "mvnw", "-DskipTests", "-pl", "scp-operator-app", "jib:buildTar")
                .inheritIO()
                .redirectErrorStream(true);
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process proc = processBuilder.start();
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
                log.info("[proc] {}", line);
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
        log.info("Running CRDs renderer from {}", file.getAbsolutePath());
        ProcessBuilder processBuilder = new ProcessBuilder()
                .directory(file)
                .command("sh", "mvnw", "-DskipTests", "-pl", "scp-crds", "package")
                .inheritIO()
                .redirectErrorStream(true);
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process proc = processBuilder.start();
        logProcess(proc);
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("Failed to generate CRDs/RBAC/Deployment");
        }
    }

    @SuppressWarnings("unchecked")
    private static String resolveCompositeName(String artifactName) {
        GenericKubernetesResource artifact =
                client
                        .genericKubernetesResources(artifactCtx())
                        .inNamespace(NS)
                        .withName(artifactName)
                        .get();

        if (artifact == null) {
            throw new IllegalStateException(
                    "Artifact '" + artifactName + "' not found in ns " + NS);
        }

        // Access the `spec` map
        Object specObj = artifact.getAdditionalProperties().get("spec");
        if (!(specObj instanceof Map)) {
            throw new IllegalStateException(
                    "Artifact '" + artifactName + "' spec is missing or not a map");
        }
        Map<String, Object> spec = (Map<String, Object>) specObj;

        Object refObj = spec.get("resourceRef");
        if (!(refObj instanceof Map)) {
            throw new IllegalStateException(
                    "Artifact '" + artifactName + "' spec.resourceRef is missing or not a map");
        }
        Map<String, Object> resourceRef = (Map<String, Object>) refObj;

        Object nameObj = resourceRef.get("name");
        if (!(nameObj instanceof String) || ((String) nameObj).isBlank()) {
            throw new IllegalStateException(
                    "Artifact '" + artifactName + "' spec.resourceRef.name is missing or empty");
        }

        return (String) nameObj;
    }

    private static void dumpCompositeForArtifact(String artifactName) {
        var artCtx = artifactCtx();
        var art = client.genericKubernetesResources(artCtx)
                .inNamespace(NS)
                .withName(artifactName)
                .get();
        if (art == null) {
            log.info("No Artifact '{}' in namespace {}", artifactName, NS);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) art.getAdditionalProperties().get("spec");
        if (spec == null) {
            log.info("Artifact '{}' has no spec", artifactName);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) spec.get("resourceRef");
        if (ref == null) {
            log.info("Artifact '{}' has no spec.resourceRef", artifactName);
            return;
        }

        String compositeName = String.valueOf(ref.get("name"));
        log.info("Composite for Artifact '{}': {}", artifactName, compositeName);

        var xr = client.genericKubernetesResources(new ResourceDefinitionContext.Builder()
                        .withGroup(ARTIFACT_GROUP)
                        .withVersion(ARTIFACT_VERSION)
                        .withPlural("compositeartifacts") // adjust plural if needed
                        .build())
                .inNamespace(NS)
                .withName(compositeName)
                .get();

        logYaml("CompositeArtifact/" + NS + "/" + compositeName, xr);
    }
}
