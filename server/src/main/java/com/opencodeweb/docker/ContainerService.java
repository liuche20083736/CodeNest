package com.opencodeweb.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.opencodeweb.auth.User;
import com.opencodeweb.auth.UserRepository;
import com.opencodeweb.common.ApiException;
import com.opencodeweb.config.DockerProperties;
import com.opencodeweb.opencode.OpenCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One Docker sandbox per user. The container runs the official OpenCode image
 * with `opencode serve` bound to 0.0.0.0:4096, protected by basic auth
 * (OPENCODE_SERVER_USERNAME / OPENCODE_SERVER_PASSWORD).
 *
 * All host-facing traffic goes through a random published port (PublishAllPorts),
 * resolved via docker inspect after start and stored on the user row.
 */
@Service
public class ContainerService {

    private static final Logger log = LoggerFactory.getLogger(ContainerService.class);

    public record State(boolean exists, boolean running, Integer hostPort, boolean ready) {
        public static final State NONE = new State(false, false, null, false);
    }

    private final DockerClient docker;
    private final DockerProperties props;
    private final UserRepository users;
    private final OpenCodeService opencode;
    private final HttpClient probeClient;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public ContainerService(DockerClient docker, DockerProperties props,
                            UserRepository users, OpenCodeService opencode) {
        this.docker = docker;
        this.props = props;
        this.users = users;
        this.opencode = opencode;
        this.probeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** Docker inspect-level snapshot of the user's container. */
    public State status(User user) {
        if (isBlank(user.getContainerId())) return State.NONE;
        try {
            var info = docker.inspectContainerCmd(user.getContainerId()).exec();
            Integer port = resolveHostPort(user.getContainerId());
            boolean running = Boolean.TRUE.equals(info.getState().getRunning());
            return new State(true, running, port, running && probe(port, user));
        } catch (Exception e) {
            return State.NONE;
        }
    }

    /**
     * Make sure the user has a running container with a reachable OpenCode API.
     * Pulls the image on first use, creates container + named volumes, starts it,
     * waits until /api/info answers and re-applies stored model credentials.
     */
    public State ensureRunning(User user) {
        Object lock = locks.computeIfAbsent(user.getId(), k -> new Object());
        synchronized (lock) {
            ensureImage();

            if (isBlank(user.getContainerId()) || !containerExists(user.getContainerId())) {
                user.setContainerId(null);
                createContainer(user);
                // persist immediately so a failed start doesn't create duplicate containers
                user.setHostPort(null);
                users.save(user);
            }

            var info = docker.inspectContainerCmd(user.getContainerId()).exec();
            if (!Boolean.TRUE.equals(info.getState().getRunning())) {
                log.info("Starting container {} for user {}", user.getContainerId(), user.getUsername());
                docker.startContainerCmd(user.getContainerId()).exec();
            }

            int port = waitForReady(user);
            if (user.getHostPort() == null || user.getHostPort() != port) {
                user.setHostPort(port);
            }

            applyConfigIfPresent(user);

            users.save(user);
            return new State(true, true, port, true);
        }
    }

    public void stop(User user) {
        if (isBlank(user.getContainerId())) return;
        try {
            var info = docker.inspectContainerCmd(user.getContainerId()).exec();
            if (Boolean.TRUE.equals(info.getState().getRunning())) {
                docker.stopContainerCmd(user.getContainerId()).withTimeout(10).exec();
            }
            user.setHostPort(null);
            users.save(user);
        } catch (Exception e) {
            throw new ApiException(500, "failed to stop container: " + e.getMessage());
        }
    }

    /** Remove the container but keep the per-user named volumes (workspace + data). */
    public void remove(User user) {
        if (isBlank(user.getContainerId())) return;
        try {
            try {
                docker.stopContainerCmd(user.getContainerId()).withTimeout(5).exec();
            } catch (Exception ignored) { }
            docker.removeContainerCmd(user.getContainerId()).withForce(true).exec();
            user.setContainerId(null);
            user.setContainerName(null);
            user.setHostPort(null);
            users.save(user);
        } catch (Exception e) {
            throw new ApiException(500, "failed to remove container: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ internals

    /** Does the user's sandbox answer the OpenCode API? (no mutation) */
    public boolean daemonAvailable() {
        try {
            docker.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Verify the Docker daemon answers before doing anything long-running. */
    private void checkDaemon() {
        String resolvedHost = com.opencodeweb.config.AppConfig.resolveDockerHost(props.getDockerHost());
        try {
            docker.pingCmd().exec();
        } catch (Exception e) {
            throw new ApiException(503,
                    "无法连接 Docker 守护进程（" + resolvedHost + "）。"
                    + "解决方式任选其一：1) 安装并启动 Docker Desktop 后重启本服务；"
                    + "2) 在 Docker Desktop 设置中启用 \"Expose daemon on tcp://localhost:2375 without TLS\"；"
                    + "3) 守护进程在其它地址（WSL/远程）时配置 ocw.docker-host 或环境变量 DOCKER_HOST。"
                    + "原始错误：" + e.getMessage());
        }
    }

    private void ensureImage() {
        checkDaemon();
        String image = props.getImage();
        try {
            docker.inspectImageCmd(image).exec();
            return; // cached
        } catch (Exception ignored) { }

        log.info("Pulling sandbox image {} (first run, may take a while)...", image);
        try {
            docker.pullImageCmd(image)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "image pull interrupted");
        } catch (Exception e) {
            throw new ApiException(500, "failed to pull image " + image + ": " + e.getMessage());
        }
    }

    private boolean containerExists(String id) {
        try {
            docker.inspectContainerCmd(id).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void createContainer(User user) {
        String username = user.getUsername();
        String containerName = "cn-" + username.toLowerCase();
        String wsVolume = "cn-ws-" + user.getId();
        String dataVolume = "cn-data-" + user.getId();

        ensureVolume(wsVolume);
        ensureVolume(dataVolume);

        String password = randomToken();
        user.setContainerName(containerName);
        user.setContainerId(null);
        user.setHostPort(null);
        user.setInstancePassword(password);

        int attempt = 0;
        while (true) {
            String name = attempt == 0 ? containerName : containerName + "-" + attempt;
            try {
                String cmd = "mkdir -p /workspace && cd /workspace && exec opencode serve --hostname 0.0.0.0 --port 4096";

                // Explicitly expose 4096 despite the image not EXPOSEing it,
                // with an ephemeral host port (0 = Docker picks a free one).
                Ports ports = new Ports(ExposedPort.tcp(4096), Ports.Binding.empty());

                CreateContainerResponse created = docker.createContainerCmd(props.getImage())
                        .withName(name)
                        .withLabels(Map.of(
                                "dev.codenest.owner", username,
                                "dev.codenest", "sandbox"))
                        .withEntrypoint("/bin/sh", "-c")
                        .withCmd(cmd)
                        .withWorkingDir("/workspace")
                        .withEnv(
                                "HOME=/data/home",
                                "XDG_CONFIG_HOME=/data/config",
                                "XDG_DATA_HOME=/data/data",
                                // V2 ignores OPENCODE_SERVER_USERNAME (hardcoded "opencode",
                                // see opencode issue #45856); only the password takes effect
                                "OPENCODE_SERVER_PASSWORD=" + password,
                                "OPENCODE_LOG_LEVEL=INFO"
                        )
                        .withExposedPorts(ExposedPort.tcp(4096))
                        .withHostConfig(HostConfig.newHostConfig()
                                .withBinds(
                                        new Bind(wsVolume, new Volume("/workspace")),
                                        new Bind(dataVolume, new Volume("/data")))
                                .withPortBindings(ports))
                        .exec();

                user.setContainerId(created.getId());
                user.setContainerName(name);
                return;
            } catch (com.github.dockerjava.api.exception.ConflictException e) {
                attempt++;
                if (attempt > 5) {
                    throw new ApiException(500, "container name conflict for " + username);
                }
            } catch (Exception e) {
                throw new ApiException(500, "failed to create container: " + e.getMessage());
            }
        }
    }

    private void ensureVolume(String name) {
        try {
            docker.createVolumeCmd().withName(name).withDriver("local").exec();
        } catch (Exception ignored) {
            // already exists
        }
    }

    private int waitForReady(User user) {
        long deadline = System.currentTimeMillis() + props.getReadyTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Integer port = resolveHostPort(user.getContainerId());
            if (port != null && probe(port, user)) {
                return port;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(500, "interrupted while waiting for container");
            }
        }
        throw new ApiException(504, "container did not become ready in "
                + props.getReadyTimeoutSeconds() + "s (check docker logs " + user.getContainerName() + ")");
    }

    private Integer resolveHostPort(String containerId) {
        try {
            var info = docker.inspectContainerCmd(containerId).exec();
            var bindings = info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(4096));
            if (bindings == null || bindings.length == 0) return null;
            String hostPortSpec = bindings[0].getHostPortSpec();
            if (hostPortSpec == null) return null;
            return Integer.parseInt(hostPortSpec);
        } catch (Exception e) {
            return null;
        }
    }

    /** Does the instance answer /api/info with valid basic auth? */
    public boolean probe(Integer hostPort, User user) {
        if (hostPort == null || isBlank(user.getInstancePassword())) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hostPort + "/api/info"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", basicAuth(user))
                    .GET()
                    .build();
            HttpResponse<String> resp = probeClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void applyConfigIfPresent(User user) {
        if (!isBlank(user.getProviderId()) && !isBlank(user.getApiKey())) {
            try {
                opencode.writeUserConfig(user);
            } catch (Exception e) {
                log.warn("Failed to apply model config for user {}: {}", user.getUsername(), e.getMessage());
            }
        }
    }

    private String basicAuth(User user) {
        // NOTE: opencode V2 hardcodes the basic-auth username to "opencode"
        // (OPENCODE_SERVER_USERNAME is ignored, see opencode issue #45856)
        String auth = "opencode:" + user.getInstancePassword();
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
