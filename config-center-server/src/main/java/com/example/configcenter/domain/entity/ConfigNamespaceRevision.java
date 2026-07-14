package com.example.configcenter.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "config_namespace_revision",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_config_namespace_app_env",
                columnNames = {"app", "env"}
        )
)
public class ConfigNamespaceRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String app;

    @Column(nullable = false, length = 50)
    private String env;

    @Column(nullable = false)
    private long revision;

    @Version
    private long lockVersion;

    public Long getId() { return id; }

    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
}
