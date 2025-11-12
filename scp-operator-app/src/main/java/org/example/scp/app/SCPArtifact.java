package org.example.scp.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

import java.util.Map;

@Group("platform.example.org")
@Version("v1alpha1")
@Kind("Artifact")
@Plural("artifacts")
public class SCPArtifact extends CustomResource<SCPArtifactSpec, SCPArtifactStatus> implements Namespaced {}

@JsonInclude(JsonInclude.Include.NON_NULL)
class SCPArtifactSpec {
    public String host;
    public Integer port;
    public String username;
    public SecretKeyRef authRef;
    public Source source;
    public String destinationPath;
    public Boolean strictHostKeyChecking;
    public SecretKeyRef knownHostsRef;
    public Integer connectTimeoutMillis;
    public Integer sessionTimeoutMillis;
}

@JsonInclude(JsonInclude.Include.NON_NULL)
class SecretKeyRef {
    public String name;
    public String key;
}

@JsonInclude(JsonInclude.Include.NON_NULL)
class Source {
    public String httpUrl;
    public Inline inline;
    public ConfigMapKeyRef configMapRef;
    public static class Inline { public String base64; }
    public static class ConfigMapKeyRef { public String name; public String key; }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
class SCPArtifactStatus {
    public String phase;
    public String message;
    public String lastTransferTime;
    public Long bytesTransferred;
    public String checksum;
    public Map<String, String> conditions;
}
