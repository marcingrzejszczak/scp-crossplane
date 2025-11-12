package org.example.scp.app;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

@Component
// If your JOSDK has a 'namespaces' attribute you can add it back later.
// @ControllerConfiguration(namespaces = Constants.WATCH_CURRENT_NAMESPACE)
@ControllerConfiguration
@ConditionalOnProperty(value = "javaoperatorsdk.enabled", havingValue = "true", matchIfMissing = true)
public class ScpArtifactReconciler implements Reconciler<SCPArtifact> {

    private static final Logger log = LoggerFactory.getLogger(ScpArtifactReconciler.class);
    private final KubernetesClient client;

    public ScpArtifactReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public UpdateControl<SCPArtifact> reconcile(SCPArtifact res, Context<SCPArtifact> context) {
        var spec = res.getSpec();
        var status = Optional.ofNullable(res.getStatus()).orElse(new SCPArtifactStatus());
        String ns = res.getMetadata().getNamespace();

        try {
            byte[] payload = resolvePayload(ns, spec.source);
            String checksum = SshScpService.sha256(payload);
            int port = spec.port != null ? spec.port : 22;
            int cto = spec.connectTimeoutMillis != null ? spec.connectTimeoutMillis : 10000;
            int sto = spec.sessionTimeoutMillis != null ? spec.sessionTimeoutMillis : 10000;
            SshScpService.Auth auth = readAuth(ns, spec.username, spec.authRef);
            SshScpService.uploadBytes(spec.host, port, spec.destinationPath, auth, payload, cto, sto);
            status.phase = "Ready";
            status.message = "Uploaded";
            status.bytesTransferred = (long) payload.length;
            status.checksum = checksum;
            status.lastTransferTime = OffsetDateTime.now().toString();
            res.setStatus(status);
            // Version-agnostic: update status directly via Fabric8, then tell JOSDK no spec update is needed.
            client.resource(res).inNamespace(ns).updateStatus();
            log.info("Uploaded {} bytes to {}:{}", payload.length, spec.host, spec.destinationPath);
            return UpdateControl.noUpdate();

        } catch (Exception e) {
            status.phase = "Failed";
            status.message = e.getMessage();
            res.setStatus(status);
            // Update status subresource regardless of JOSDK UpdateControl factories
            client.resource(res).inNamespace(ns).updateStatus();
            log.error("Reconcile failed", e);
            // Ask JOSDK to requeue via a simple reschedule (works across versions)
            return UpdateControl.<SCPArtifact>noUpdate().rescheduleAfter(java.time.Duration.ofSeconds(30));
        }
    }

    private SshScpService.Auth readAuth(String ns, String username, SecretKeyRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("spec.authRef is required");
        }
        Secret s = client.secrets().inNamespace(ns).withName(ref.name).get();
        if (s == null || s.getData() == null) {
            throw new IllegalArgumentException("Secret " + ref.name + " not found");
        }
        String pass = null;
        String key = null;
        if (ref.key != null && s.getData().containsKey(ref.key)) {
            pass = new String(java.util.Base64.getDecoder().decode(s.getData().get(ref.key)), StandardCharsets.UTF_8);
        }
        if (s.getData().containsKey("privateKey")) {
            key = new String(java.util.Base64.getDecoder().decode(s.getData().get("privateKey")), StandardCharsets.UTF_8);
        }
        return new SshScpService.Auth(username, pass, key);
    }

    private byte[] resolvePayload(String ns, Source source) throws Exception {
        if (source == null) {
            throw new IllegalArgumentException("spec.source is required");
        }
        if (source.httpUrl != null) {
            return SshScpService.download(source.httpUrl);
        }
        if (source.inline != null && source.inline.base64 != null) {
            return Base64.getDecoder().decode(source.inline.base64);
        }
        if (source.configMapRef != null) {
            var cm = client.configMaps().inNamespace(ns).withName(source.configMapRef.name).get();
            if (cm == null || cm.getData() == null) {
                throw new IllegalArgumentException("ConfigMap not found");
            }
            String v = cm.getData().get(source.configMapRef.key);
            if (v == null) {
                throw new IllegalArgumentException("Key not found in ConfigMap");
            }
            return v.getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unsupported source");
    }
}
