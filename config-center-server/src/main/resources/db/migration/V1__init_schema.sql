CREATE TABLE config_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app VARCHAR(100) NOT NULL,
    env VARCHAR(50) NOT NULL,
    config_key VARCHAR(200) NOT NULL,
    config_value VARCHAR(2000) NOT NULL,
    description VARCHAR(500) NULL,
    version BIGINT NOT NULL,
    lock_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_env_key UNIQUE (app, env, config_key)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE config_item_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app VARCHAR(100) NOT NULL,
    env VARCHAR(50) NOT NULL,
    config_key VARCHAR(200) NOT NULL,
    config_value VARCHAR(2000) NOT NULL,
    description VARCHAR(500) NULL,
    version BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    operator VARCHAR(100) NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cfg_hist_app_env_key (app, env, config_key),
    INDEX idx_cfg_hist_app_env_key_ver (app, env, config_key, version)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE config_namespace_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app VARCHAR(100) NOT NULL,
    env VARCHAR(50) NOT NULL,
    revision BIGINT NOT NULL,
    lock_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_config_namespace_app_env UNIQUE (app, env)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE feature_flag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app VARCHAR(100) NOT NULL,
    env VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    enabled BIT NOT NULL,
    rollout_percentage INT NOT NULL,
    version BIGINT NOT NULL,
    lock_version BIGINT NOT NULL,
    allowlist_json VARCHAR(4000) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_env_name UNIQUE (app, env, name)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE feature_flag_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app VARCHAR(100) NOT NULL,
    env VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    enabled BIT NOT NULL,
    rollout_percentage INT NOT NULL,
    allowlist_json VARCHAR(4000) NOT NULL,
    version BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    operator VARCHAR(100) NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ff_hist_app_env_name (app, env, name),
    INDEX idx_ff_hist_app_env_name_ver (app, env, name, version)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
