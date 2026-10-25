package com.opencodeweb.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.opencodeweb.auth.User;
import com.opencodeweb.common.ApiException;
import com.opencodeweb.config.DockerProperties;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin proxy between the web backend and each user's OpenCode HTTP API (V2).
 * All requests inside carry the per-container basic auth credentials.
 */
@Service
public class OpenCodeService {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeService.class);

    private final HttpClient http;
    private final DockerClient docker;
    private final DockerProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenCodeService(DockerClient docker, DockerProperties props) {
        this.docker = docker;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ------------------------------------------------------------------ proxy

    public JsonNode call(User user, String method, String path, JsonNode body) {
        return call(user, method, path, body, false);
    }

    public JsonNode call(User user, String method, String path, JsonNode body, boolean silentError) {
        URI uri = URI.create(baseUrl(user) + path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", basicAuth(user));

            if (body != null && !body.isNull() && !body.isEmpty()) {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 400) {
                String msg = "OpenCode API " + path + " -> HTTP " + resp.statusCode();
                if (!resp.body().isBlank()) msg += ": " + truncate(resp.body());
                if (silentError) {
                    log.warn(msg);
                    return mapper.nullNode();
                }
                throw new ApiException(502, msg);
            }
            if (resp.body().isBlank()) return mapper.nullNode();
            JsonNode parsed = mapper.readTree(resp.body());
            // V2 HTTP API wraps payloads as {"data": ...} (+ optional "cursor"
            // pagination envelope); unwrap this wrapper so callers see values
            if (parsed.isObject() && parsed.has("data")
                    && (parsed.size() == 1 || (parsed.size() == 2 && parsed.has("cursor")))) {
                return parsed.get("data");
            }
            return parsed;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "cannot reach OpenCode instance: " + e.getMessage());
        }
    }

    /** Address used to talk to this user's sandbox. */
    public String baseUrl(User user) {
        if (user.getHostPort() == null) {
            throw ApiException.conflict("sandbox container is not running; start it first");
        }
        if ("container-dns".equals(props.getConnectMode())) {
            return "http://" + user.getContainerName() + ":4096";
        }
        return "http://127.0.0.1:" + user.getHostPort();
    }

    // ------------------------------------------------------------------ config

    /**
     * Write the user's model credentials into the sandbox and reload.
     * Uses docker exec + base64 so nothing shell-sensitive is interpolated.
     * XDG_CONFIG_HOME inside the container is /data/config, so the global
     * config file becomes /data/config/opencode/opencode.json.
     */
    public void writeUserConfig(User user) throws Exception {
        ObjectNode provider = mapper.createObjectNode();
        if (!isBlank(user.getNpmModule())) {
            provider.put("npm", user.getNpmModule());
        }
        ObjectNode options = provider.putObject("options");
        options.put("apiKey", user.getApiKey());
        if (!isBlank(user.getBaseUrl())) {
            options.put("baseURL", user.getBaseUrl());
        }

        ObjectNode providers = mapper.createObjectNode();
        if (!isBlank(user.getProviderId())) {
            providers.set(user.getProviderId(), provider);
        }

        ObjectNode config = mapper.createObjectNode();
        config.put("$schema", "https://opencode.ai/config.json");
        if (!isBlank(user.getModelId())) {
            config.put("model", user.getModelId());
        }
        config.set("provider", providers);

        String json = mapper.writeValueAsString(config);
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String script = "mkdir -p \"$XDG_CONFIG_HOME/opencode\" "
                + "&& echo " + b64 + " | base64 -d > \"$XDG_CONFIG_HOME/opencode/opencode.json\" "
                + "&& echo CONFIG_OK";

        exec(user, script);

        // hot-reload best effort (V2: config reload without restart)
        try {
            call(user, "POST", "/api/location/reload", mapper.nullNode(), true);
        } catch (Exception ignored) {
        }

        log.info("Applied model config for user {} (provider={}, model={})",
                user.getUsername(), user.getProviderId(), user.getModelId());
    }

    /** Run a shell command inside the user's sandbox container, wait for completion. */
    public void exec(User user, String script) {
        if (isBlank(user.getContainerId())) {
            throw ApiException.conflict("sandbox container not created yet");
        }
        try {
            ExecCreateCmdResponse exec = docker.execCreateCmd(user.getContainerId())
                    .withCmd("sh", "-c", script)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            docker.execStartCmd(exec.getId())
                    .exec(new ExecStartResultCallback(System.out, System.err))
                    .awaitCompletion(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "exec interrupted");
        } catch (Exception e) {
            throw new ApiException(500, "exec failed: " + e.getMessage());
        }
    }

    private String basicAuth(User user) {
        // NOTE: opencode V2 hardcodes the basic-auth username to "opencode"
        // (OPENCODE_SERVER_USERNAME is ignored, see opencode issue #45856)
        String auth = "opencode:" + user.getInstancePassword();
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
