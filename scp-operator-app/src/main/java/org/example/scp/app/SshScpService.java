package org.example.scp.app;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceLoader;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

public class SshScpService {

    public record Auth(String username, String password, String privateKeyPem) {}

    public static byte[] download(String httpUrl) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            var req = new HttpGet(httpUrl);
            return client.execute(req, resp -> {
                int code = resp.getCode();
                if (code >= 200 && code < 300) {
                    HttpEntity e = resp.getEntity();
                    return e != null ? EntityUtils.toByteArray(e) : new byte[0];
                }
                throw new IOException("HTTP " + code + " for " + httpUrl);
            });
        }
    }

    public static void uploadBytes(String host, int port, String destPath, Auth auth,
                                   byte[] content, int connectTimeoutMillis, int sessionTimeoutMillis) throws Exception {
        Objects.requireNonNull(auth, "auth");
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = client.connect(auth.username(), host, port)
                    .verify(Duration.ofMillis(connectTimeoutMillis))
                    .getSession()) {

                // --- Key auth from PEM string (2.12.x way)
                if (auth.privateKeyPem() != null && !auth.privateKeyPem().isBlank()) {
                    KeyPairResourceLoader loader = SecurityUtils.getKeyPairResourceParser();
                    Collection<KeyPair> keys = loader.loadKeyPairs(
                            null,                                  // SessionContext
                            NamedResource.ofName("inline-key"),    // just a label
                            null,                                  // FilePasswordProvider (null = no passphrase)
                            new StringReader(auth.privateKeyPem()));
                    if (!keys.isEmpty()) {
                        session.addPublicKeyIdentity(keys.iterator().next());
                    } else {
                        throw new IllegalArgumentException("No keypair parsed from provided PEM");
                    }
                }

                if (auth.password() != null && !auth.password().isBlank()) {
                    session.addPasswordIdentity(auth.password());
                }

                session.auth().verify(Duration.ofMillis(sessionTimeoutMillis));

                // --- SCP client (new package)
                ScpClient scp = ScpClientCreator.instance().createScpClient(session);

                // Use the Path-based upload (stable across versions)
                Path tmp = Files.createTempFile("scp-", ".bin");
                try {
                    Files.write(tmp, content);
                    scp.upload(tmp, destPath); // or add options like ScpClient.Option.PreserveAttributes
                } finally {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                }
            } finally {
                client.stop();
            }
        }
    }

    public static String sha256(byte[] data) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
