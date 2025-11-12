package org.example.scp.app;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ScpUploadSpringBootTest {

    private static final int SSH_PORT = 2222;
    private static final Logger log = LoggerFactory.getLogger(ScpUploadSpringBootTest.class);

    @Container
    static GenericContainer<?> sshd = new GenericContainer<>("lscr.io/linuxserver/openssh-server:latest")
            .withEnv("PASSWORD_ACCESS", "true")
            .withEnv("USER_NAME", "test")
            .withEnv("USER_PASSWORD", "test")
            .withExposedPorts(SSH_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void canUploadAndVerifyFile() throws Exception {
        // given
        log.info("Starting SCP upload test");
        String host = sshd.getHost();
        int port = sshd.getMappedPort(SSH_PORT);
        var auth = new SshScpService.Auth("test", "test", null);
        String expected = "hello-testcontainers";
        byte[] content = expected.getBytes(StandardCharsets.UTF_8);

        // when
        log.info("Uploading content");
        SshScpService.uploadBytes(host, port, "/config/hello.txt", auth, content, 10_000, 10_000);

        // then - verify that file exists and contents match
        String remoteContent = execOverSsh(host, port, auth, "cat /config/hello.txt");
        log.info("Got content: {}", remoteContent);

        Assertions.assertThat(remoteContent)
                .as("remote file content")
                .isNotNull()
                .isEqualTo(expected);
    }

    private String execOverSsh(String host, int port, SshScpService.Auth auth, String command) throws Exception {
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.start();
            try (ClientSession session = client.connect(auth.username(), host, port)
                    .verify(Duration.ofSeconds(10))
                    .getSession()) {

                session.addPasswordIdentity(auth.password());
                session.auth().verify(Duration.ofSeconds(5));

                try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                     ClientChannel channel = session.createExecChannel(command)) {

                    channel.setOut(out);
                    channel.setErr(System.err);
                    channel.open().verify(Duration.ofSeconds(5));
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 10_000);

                    return out.toString(StandardCharsets.UTF_8);
                }
            } finally {
                client.stop();
            }
        }
    }
}
