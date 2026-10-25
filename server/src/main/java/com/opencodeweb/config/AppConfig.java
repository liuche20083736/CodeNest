package com.opencodeweb.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * Resolve order:
     *  1. ocw.docker-host / DOCKER_HOST env
     *  2. Windows named pipe if it exists (Docker Desktop)
     *  3. tcp://localhost:2375 fallback error message explains how to make the daemon reachable
     */
    @Bean
    public DockerClient dockerClient(DockerProperties props) {
        String host = resolveDockerHost(props.getDockerHost());
        log.info("Using Docker host: {}", host);

        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        builder.withDockerHost(host);

        DockerClientConfig config = builder.build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofMinutes(15))
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    public static String resolveDockerHost(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String env = System.getenv("DOCKER_HOST");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            if (new java.io.File("\\\\.\\pipe\\dockerDesktopLinuxEngine").exists()) {
                return "npipe:////./pipe/dockerDesktopLinuxEngine";
            }
            if (new java.io.File("\\\\.\\pipe\\docker_engine").exists()) {
                return "npipe:////./pipe/docker_engine";
            }
        }
        // fallback: daemon must be exposed on tcp (see error guidance at ping time)
        return "tcp://localhost:2375";
    }
}
