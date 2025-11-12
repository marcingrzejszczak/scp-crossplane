# Crossplane + Java Operator (SCP): How Java, Helm, and Crossplane fit together

## High‑level introduction

In this repo:

- We build a Java "operator" (a controller plus a Custom Resource) that can upload a file over SCP when you create a special object in Kubernetes.
- We use Helm only to install that operator (CRD + RBAC + Deployment) into the cluster.
- We use Crossplane (a higher‑level control plane on top of K8s) to let you ask for “an Artifact” and have Crossplane create the operator’s Custom Resource for you, which triggers the upload.

### Quick glossary (what the pieces mean)

- Cluster/Node: A cluster is the whole system; nodes are the machines (real or virtual) in it.
- Container/Image: A packaged filesystem + process. We build an image for the operator using Maven Jib.
- Pod: The smallest thing K8s runs (one or more containers that share a network and storage).
- Deployment: A controller that keeps a desired number of identical Pods running and updates them safely.
- Service: A stable network name that points to matching Pods (load‑balancing within the cluster).
- Namespace: A logical folder for grouping resources in a cluster (e.g., `default`).
- Secret/ConfigMap: Key‑value configs. Secrets are for sensitive data (e.g., passwords, keys).
- RBAC/ServiceAccount: Permissions. RBAC says “who can do what.” A Deployment’s pods run as a ServiceAccount.
- CRD (CustomResourceDefinition): Extends Kubernetes with a new type. Here we add `SCPArtifact`.
- Custom Resource: A concrete object of a CRD type (e.g., `SCPArtifact from-xrd`).
- Controller/Operator: Software that watches resources and acts to make reality match their spec. Our Java app is the operator.
- Reconciler (in our Java operator): The piece of code that reacts to changes in a Custom Resource and does work until the resource reaches the desired state. Here it performs the SCP upload and updates `status`.
- Conditions/Status: Read‑only fields set by controllers to explain what’s happening (e.g., `Ready`, error messages).

- Helm: A package manager for K8s. A Helm chart is a bundle of YAML templates plus `values.yaml`. You can render charts to plain YAML (`helm template`) or install them (`helm install`). In this repo we only render/apply manifests for the operator install (CRD, RBAC, Deployment).

- Crossplane: A framework to build platform APIs on top of K8s. Key terms:
  - Provider: A plugin that lets Crossplane manage something (clouds, K8s itself). We use `provider-kubernetes` to create K8s objects from Crossplane.
  - ProviderConfig: How a Provider authenticates. Our tests use `InjectedIdentity` in‑cluster.
  - XRD (CompositeResourceDefinition): Defines a higher‑level type (e.g., `CompositeArtifact`) and an optional Claim name (`Artifact`).
  - Composition: Explains how a high‑level resource should be built from lower‑level pieces by patching fields. Here it creates an `SCPArtifact`.
  - Claim: A user‑facing object that requests a composite resource (e.g., `Artifact`).

### How they work together here (big picture)

1) Java operator image
   - You build the Java operator with Maven + Jib → produces a container image.

2) Install the operator into K8s (via Helm‑rendered manifests)
   - Helm chart renders three YAML files: CRD, RBAC, Deployment.
   - Apply them so the operator Pod runs and watches `SCPArtifact` resources.

3) Crossplane as the UX layer
   - Crossplane and `provider-kubernetes` run in the cluster.
   - You apply an XRD and a Composition that say: when someone creates an `Artifact` claim, produce an `SCPArtifact` with fields patched from that claim.
   - You create an `Artifact` claim with the SCP details (host, username, destination, source bytes, secret ref).

4) Reconciliation (the action)
   - Crossplane uses `provider-kubernetes` to create the actual `SCPArtifact` resource in the cluster.
   - Our operator’s Reconciler sees the `SCPArtifact`, performs the SCP upload, and updates `status.phase=Ready` when done.

Flow summary:

Java code → container image (Jib) → Helm renders manifests → Operator running → Crossplane (XRD/Composition/Claim) → provider‑kubernetes creates `SCPArtifact` → Operator reconciler uploads file → `status: Ready`.

—

This repository shows how to package a Java operator that uploads files via SCP and how to drive it through Crossplane. The moving parts are:

- Java operator code built into a container image with Maven + Jib
- A small Helm chart that renders the operator’s install manifests (CRD, RBAC, Deployment)
- Crossplane definitions (XRD + Composition + Claim) that create the operator’s CR (`SCPArtifact`) via `provider-kubernetes`
- An E2E test that starts a disposable K3s cluster (Testcontainers), installs everything, and verifies the SCP upload inside Kubernetes

The key to remember: Crossplane does not call Helm here. Helm is used only to render/apply the operator’s installation manifests. Crossplane then creates instances of the operator’s CRD to make it do work.

## Repository modules at a glance

1) scp-operator-app
   - Java operator (Spring Boot + Java Operator SDK, Fabric8).
   - Custom Resource: `SCPArtifact` in group `ops.example.org`, version `v1alpha1`.
   - Reconciler (`ScpArtifactReconciler`) reads a payload (inline/http/ConfigMap), credentials from a Secret, and SCP-uploads bytes to a target host.
   - Built into a container image via Jib.
   - Important files:
     - `src/main/java/org/example/scp/app/ScpOperatorApplication.java`
     - `src/main/java/org/example/scp/app/SCPArtifact.java` (Spec/Status types)
     - `src/main/java/org/example/scp/app/ScpArtifactReconciler.java` (business logic)

2) scp-crds
   - Purpose: provide/install manifests for the operator using a Helm chart.
   - Chart path: `scp-crds/helm/scp-operator` with three templates:
     - `templates/crd.yaml` — CRD for `SCPArtifact`
     - `templates/rbac.yaml` — Namespace/ServiceAccount/ClusterRole/ClusterRoleBinding
     - `templates/deployment.yaml` — operator Deployment using the Jib image
   - Values: `values.yaml` (image repository/tag, namespace).
   - Renderer tool: `src/main/java/org/example/scp/crds/HelmRenderMain.java` runs a Helm container and executes `helm template --output-dir`, copying the rendered files to:
     - `scp-crds/target/generated-k8s-sources/{crd.yaml, rbac.yaml, deployment.yaml}`

3) scp-crossplane
   - Purpose: define a higher-level Crossplane API and map it to the operator’s CR.
   - Files:
     - `xrd.yaml` — CompositeResourceDefinition for `CompositeArtifact` with claim `Artifact`.
     - `composition.yaml` — uses `provider-kubernetes` Object to create an `SCPArtifact` in the cluster. Patches claim fields (host, port, username, destinationPath, credentials, source) into the composed `SCPArtifact` manifest.
     - `examples/` — runnable manifests used by tests:
       - `provider-kubernetes.yaml` — installs `provider-kubernetes`
       - `providerconfig-k8s.yaml` — `ProviderConfig` with `InjectedIdentity`
       - `ssh-secret.yaml`, `sshd-deployment.yaml`, `sshd-service.yaml` — in-cluster SSH server and Secret
       - `claim.yaml` — Crossplane `Artifact` claim that, via the Composition, creates an `SCPArtifact` named `from-xrd`

4) scp-itests
   - Purpose: end-to-end test using Testcontainers + K3s and only YAML (no imperative K8s API calls beyond `kubectl`-equivalent applies via Fabric8).
   - Main test: `src/test/java/org/example/scp/it/ScpEndToEndIT.java`.
   - What it does:
     1) Starts K3s in a disposable container
     2) Builds the operator image tar with Jib and imports it into K3s containerd
     3) Renders Helm chart to YAML (CRD/RBAC/Deployment) and applies them to install the operator
     4) Installs Crossplane core, installs `provider-kubernetes`, creates `ProviderConfig`
     5) Applies the Crossplane XRD + Composition
     6) Applies the SSH stack and a Crossplane `Artifact` claim
     7) Waits for the composed `SCPArtifact` to become Ready, then execs into the SSH pod to verify the file exists

## End-to-end flow (who does what)

1) Build and install the operator
   - Java code → container image via Jib (`scp-operator-app`).
   - Helm chart (`scp-crds`) → renders CRD/RBAC/Deployment YAML pointing to that image.
   - Apply those YAMLs to the cluster so the operator is running and watching `SCPArtifact`.

2) Crossplane drives the operator
   - Crossplane core + `provider-kubernetes` are installed.
   - You apply `xrd.yaml` + `composition.yaml` to define how a high-level `Artifact` maps into an `SCPArtifact`.
   - You (or the test) apply an `Artifact` claim (from `scp-crossplane/examples/claim.yaml`).
   - The Composition uses the `provider-kubernetes` Object to create a composed resource in the cluster: an `SCPArtifact` (the operator’s CR) with the right spec.
   - The operator reconciles that `SCPArtifact` and performs the SCP upload.

### How does Crossplane use the Helm stuff?

It doesn’t, directly:
- Helm is used only to produce/apply the operator’s install manifests (CRD/RBAC/Deployment). This step is outside Crossplane and is analogous to “how do you install any operator”.
- Crossplane’s role is to create and manage instances of CRDs. In our case, it creates an `SCPArtifact` resource using `provider-kubernetes` based on your `Artifact` claim and the `Composition` patching rules.

If desired, you could make Crossplane install the operator chart by adding `provider-helm` and a `Release` resource — but the E2E test intentionally keeps Helm separate, using it only to render the operator’s manifests while Crossplane focuses on composing and creating the operator’s CR.

## Useful file paths

- Operator code: `scp-operator-app/src/main/java/org/example/scp/app/*`
- Helm chart: `scp-crds/helm/scp-operator/{Chart.yaml, values.yaml, templates/*.yaml}`
- Helm renderer: `scp-crds/src/main/java/org/example/scp/crds/HelmRenderMain.java`
- Crossplane: `scp-crossplane/{xrd.yaml, composition.yaml}` and `scp-crossplane/examples/*`
- E2E: `scp-itests/src/test/java/org/example/scp/it/ScpEndToEndIT.java`

## Commands (local dev)

- Build operator image tar with Jib:
  - `mvn -q -DskipTests -pl scp-operator-app jib:buildTar`

- Render operator manifests from Helm to `scp-crds/target/generated-k8s-sources`:
  - `mvn -q -DskipTests -pl :scp-crds exec:java`

- Run the E2E test (starts K3s and validates Crossplane → operator → SCP upload):
  - `mvn -q -pl :scp-itests -Dtest=org.example.scp.it.ScpEndToEndIT test`

## Troubleshooting

- Image tag mismatch: Ensure `scp-crds/helm/scp-operator/values.yaml` image repo/tag matches the Jib-built image tag (`${project.version}` by default). If the operator pod can’t pull the image, the Deployment won’t become Ready.
- Provider health: Wait for `provider-kubernetes` to reach Healthy before expecting the Composition to create the `SCPArtifact`.
- Namespaces: The chart and examples assume `default`. Adjust values/manifests if you change namespaces.
- Timeouts: The test uses Awaitility with generous timeouts; adjust if your environment is slower/faster.