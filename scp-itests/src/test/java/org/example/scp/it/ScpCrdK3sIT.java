package org.example.scp.it;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.client.Config;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.awaitility.Awaitility;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.FileInputStream;
import java.time.Duration;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

public class ScpCrdK3sIT {

    private static K3sContainer k3s;
    private static KubernetesClient client;

    @BeforeAll
    static void startCluster() {
        k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.29.6-k3s1"));
        k3s.start();

        String kubeconfig = k3s.getKubeConfigYaml();
        Config cfg = Config.fromKubeconfig(kubeconfig);
        client = new KubernetesClientBuilder().withConfig(cfg).build();
    }

    @AfterAll
    static void stopCluster() {
        if (client != null) {
            client.close();
        }
        if (k3s != null) {
            k3s.stop();
        }
    }

    @Test
    void installScpArtifactCRD() throws Exception {
        // Path to CRD in the repository
        File crdFile = Paths.get("..", "scp-crds", "crd", "scpartifact-crd.yaml").toFile();
        assertThat(crdFile)
                .withFailMessage("CRD file not found: %s", crdFile.getAbsolutePath())
                .isFile();

        try (FileInputStream fis = new FileInputStream(crdFile)) {
            CustomResourceDefinition crd = Serialization.unmarshal(fis, CustomResourceDefinition.class);
            client.apiextensions().v1().customResourceDefinitions().resource(crd).createOrReplace();
        }

        // Wait up to 30s for the CRD to be observable by the API server
        String crdName = "scparticles.ops.example.org"; // must match metadata.name in CRD file
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> client.apiextensions().v1().customResourceDefinitions()
                        .withName(crdName)
                        .get() != null);

        CustomResourceDefinition got = client.apiextensions().v1().customResourceDefinitions()
                .withName(crdName)
                .get();

        assertThat(got).as("CRD should be installed").isNotNull();
        assertThat(got.getSpec().getNames().getPlural()).isEqualTo("scparticles");
    }
}
