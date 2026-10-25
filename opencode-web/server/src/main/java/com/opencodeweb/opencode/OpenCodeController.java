package com.opencodeweb.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencodeweb.auth.User;
import com.opencodeweb.auth.UserRepository;
import com.opencodeweb.docker.ContainerService;
import com.opencodeweb.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Map;

/**
 * All /api/oc/** endpoints are user-scoped: each one resolves the caller's own
 * sandbox container and forwards the call to the OpenCode API inside it.
 */
@RestController
@RequestMapping("/api/oc")
public class OpenCodeController {

    private final OpenCodeService opencode;
    private final ContainerService containers;
    private final UserRepository users;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenCodeController(OpenCodeService opencode, ContainerService containers,
                              UserRepository users, TransactionTemplate tx) {
        this.opencode = opencode;
        this.containers = containers;
        this.users = users;
        this.tx = tx;
    }

    private User user(HttpServletRequest req) {
        return (User) req.getAttribute("authUser");
    }

    private User reload(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("user vanished"));
    }

    // ---------------------------------------------------------------- container lifecycle

    /** Docker-level status without starting anything. */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        User user = user(req);
        ContainerService.State st = containers.status(user);
        boolean dockerOk = containers.daemonAvailable();
        return Map.of(
                "exists", st.exists(),
                "running", st.running(),
                "ready", st.ready(),
                "hostPort", st.hostPort() == null ? 0 : st.hostPort(),
                "dockerAvailable", dockerOk,
                "providerId", nz(user.getProviderId()),
                "modelId", nz(user.getModelId()),
                "hasApiKey", user.getApiKey() != null && !user.getApiKey().isBlank()
        );
    }

    /** Create/start the sandbox and wait until the OpenCode API is reachable. */
    @PostMapping("/start")
    public Map<String, Object> start(HttpServletRequest req) {
        User user = user(req);
        ContainerService.State st = containers.ensureRunning(user);
        return Map.of(
                "exists", true,
                "running", true,
                "ready", st.ready(),
                "hostPort", st.hostPort()
        );
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(HttpServletRequest req) {
        containers.stop(user(req));
        return Map.of("stopped", true);
    }

    /** Remove the container entirely (workspace + data volumes are kept). */
    @DeleteMapping("/container")
    public Map<String, Object> removeContainer(HttpServletRequest req) {
        containers.remove(user(req));
        return Map.of("removed", true);
    }

    // ---------------------------------------------------------------- settings (per-user credentials)

    public record SettingsInput(String providerId, String npmModule, String modelId,
                                String baseUrl, String apiKey) {}

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody SettingsInput in, HttpServletRequest req) {
        User user = user(req);
        Long uid = user.getId();

        tx.executeWithoutResult(s -> {
            User u = reload(uid);
            u.setProviderId(blankToNull(in.providerId()));
            u.setNpmModule(blankToNull(in.npmModule()));
            u.setModelId(blankToNull(in.modelId()));
            u.setBaseUrl(blankToNull(in.baseUrl()));
            if (in.apiKey() != null && !in.apiKey().isBlank()) {
                u.setApiKey(in.apiKey().trim());
            }
            users.save(u);
        });

        User fresh = reload(uid);
        boolean applied = false;
        if (fresh.getProviderId() != null && fresh.getApiKey() != null) {
            var st = containers.status(fresh);
            if (st.running()) {
                containers.ensureRunning(fresh); // re-applies stored config inside container
                applied = true;
            }
        }
        return Map.of("saved", true, "appliedToContainer", applied);
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings(HttpServletRequest req) {
        User user = user(req);
        return Map.of(
                "providerId", nz(user.getProviderId()),
                "npmModule", nz(user.getNpmModule()),
                "modelId", nz(user.getModelId()),
                "baseUrl", nz(user.getBaseUrl()),
                "hasApiKey", user.getApiKey() != null && !user.getApiKey().isBlank()
        );
    }

    /** List models available inside the user's sandbox (to fill modelId). */
    @GetMapping("/models")
    public Map<String, Object> models(HttpServletRequest req) {
        User fresh = reload(user(req).getId());
        if (fresh.getHostPort() == null) {
            throw ApiException.conflict("sandbox is not running; start it first");
        }
        JsonNode models = opencode.call(fresh, "GET", "/api/model", null);
        JsonNode providers = opencode.call(fresh, "GET", "/api/provider", null);
        return Map.of("models", models, "providers", providers);
    }

    // ---------------------------------------------------------------- OpenCode API passthrough

    /** Create a session inside the user's sandbox, pinned to that user's /workspace. */
    @PostMapping("/session")
    public JsonNode createSession(HttpServletRequest req, @RequestBody(required = false) JsonNode body) {
        User fresh = reload(user(req).getId());
        containers.ensureRunning(fresh);

        ObjectNode payload = (body instanceof ObjectNode o) ? o : mapper.createObjectNode();
        ObjectNode location = payload.withObject("location");
        if (!location.has("directory")) {
            location.put("directory", "/workspace");
        }
        return opencode.call(fresh, "POST", "/api/session", payload);
    }

    @GetMapping("/session")
    public JsonNode listSessions(HttpServletRequest req,
                                 @RequestParam(value = "directory", required = false) String directory) {
        User fresh = reload(user(req).getId());
        if (fresh.getHostPort() == null) {
            throw ApiException.conflict("sandbox is not running; start it first");
        }
        String path = (directory != null && !directory.isBlank())
                ? "/api/session?directory=" + URLEncoder.encode(directory, StandardCharsets.UTF_8)
                : "/api/session";
        return opencode.call(fresh, "GET", path, null);
    }

    /** Which sessions are currently busy (used by the UI for live status). */
    @GetMapping("/session/active")
    public JsonNode activeSessions(HttpServletRequest req) {
        User fresh = reload(user(req).getId());
        if (fresh.getHostPort() == null) {
            return mapper.createArrayNode();
        }
        return opencode.call(fresh, "GET", "/api/session/active", null);
    }

    @GetMapping("/session/{sessionID}")
    public JsonNode getSession(HttpServletRequest req, @PathVariable String sessionID) {
        User fresh = reload(user(req).getId());
        return opencode.call(fresh, "GET", "/api/session/" + sessionID, null);
    }

    @GetMapping("/session/{sessionID}/message")
    public JsonNode getMessages(HttpServletRequest req, @PathVariable String sessionID) {
        User fresh = reload(user(req).getId());
        return opencode.call(fresh, "GET", "/api/session/" + sessionID + "/message", null);
    }

    @PostMapping("/session/{sessionID}/prompt")
    public JsonNode prompt(HttpServletRequest req, @PathVariable String sessionID,
                           @RequestBody(required = false) JsonNode body) {
        User fresh = reload(user(req).getId());
        ObjectNode payload = (body instanceof ObjectNode o) ? o : mapper.createObjectNode();
        if (!payload.hasNonNull("text")) {
            throw ApiException.badRequest("'text' is required");
        }
        return opencode.call(fresh, "POST", "/api/session/" + sessionID + "/prompt", payload);
    }

    @PostMapping("/session/{sessionID}/interrupt")
    public JsonNode interrupt(HttpServletRequest req, @PathVariable String sessionID) {
        User fresh = reload(user(req).getId());
        return opencode.call(fresh, "POST", "/api/session/" + sessionID + "/interrupt", null);
    }

    @DeleteMapping("/session/{sessionID}")
    public JsonNode deleteSession(HttpServletRequest req, @PathVariable String sessionID) {
        User fresh = reload(user(req).getId());
        return opencode.call(fresh, "DELETE", "/api/session/" + sessionID, null);
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
