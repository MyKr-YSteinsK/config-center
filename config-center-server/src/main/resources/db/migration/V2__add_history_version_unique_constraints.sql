DROP INDEX idx_cfg_hist_app_env_key_ver ON config_item_history;

ALTER TABLE config_item_history
    ADD CONSTRAINT uk_cfg_hist_app_env_key_ver
        UNIQUE (app, env, config_key, version);

DROP INDEX idx_ff_hist_app_env_name_ver ON feature_flag_history;

ALTER TABLE feature_flag_history
    ADD CONSTRAINT uk_ff_hist_app_env_name_ver
        UNIQUE (app, env, name, version);
