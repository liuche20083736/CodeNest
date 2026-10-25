package com.opencodeweb.auth;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A platform user. Also acts as the registry of that user's sandbox container
 * and the model credentials applied inside it.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 128)
    private String passwordHash;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // --- sandbox container registry ---

    /** Docker container id (null = never created). */
    @Column(length = 128)
    private String containerId;

    @Column(length = 128)
    private String containerName;

    /** Basic-auth password protecting the OpenCode server inside the container. */
    @Column(length = 128)
    private String instancePassword;

    /** Host port the container's port 4096 is mapped to. */
    private Integer hostPort;

    // --- model credentials (applied into the container's opencode config) ---

    /** e.g. "anthropic", "openai", "google"... */
    @Column(length = 128)
    private String providerId;

    /** Optional npm module for a custom provider (e.g. @ai-sdk/openai-compatible). */
    @Column(length = 256)
    private String npmModule;

    /** Full model reference, e.g. "anthropic/claude-sonnet-4-5". */
    @Column(length = 256)
    private String modelId;

    /** Optional custom base URL (openai-compatible providers). */
    @Column(length = 512)
    private String baseUrl;

    @Column(length = 1024)
    private String apiKey;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }
    public String getInstancePassword() { return instancePassword; }
    public void setInstancePassword(String instancePassword) { this.instancePassword = instancePassword; }
    public Integer getHostPort() { return hostPort; }
    public void setHostPort(Integer hostPort) { this.hostPort = hostPort; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getNpmModule() { return npmModule; }
    public void setNpmModule(String npmModule) { this.npmModule = npmModule; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
