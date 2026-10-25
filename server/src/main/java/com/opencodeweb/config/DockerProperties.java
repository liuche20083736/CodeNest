package com.opencodeweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ocw")
public class DockerProperties {

    /** OpenCode sandbox image reference. */
    private String image = "ghcr.io/anomalyco/opencode:2.1.4";

    /** "host-port" or "container-dns" (see application.yml). */
    private String connectMode = "host-port";

    /** Seconds to wait for the OpenCode API to answer after container start. */
    private long readyTimeoutSeconds = 120;

    /** Optional JWT secret. Empty = random per startup. */
    private String jwtSecret = "";

    /**
     * Explicit Docker daemon endpoint override
     * (e.g. npipe:////./pipe/docker_engine, tcp://localhost:2375).
     * Empty = auto-detect (DOCKER_HOST env, then Windows named pipe, then tcp:2375).
     */
    private String dockerHost = "";

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getConnectMode() { return connectMode; }
    public void setConnectMode(String connectMode) { this.connectMode = connectMode; }

    public long getReadyTimeoutSeconds() { return readyTimeoutSeconds; }
    public void setReadyTimeoutSeconds(long readyTimeoutSeconds) { this.readyTimeoutSeconds = readyTimeoutSeconds; }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }

    public String getDockerHost() { return dockerHost; }
    public void setDockerHost(String dockerHost) { this.dockerHost = dockerHost; }
}
