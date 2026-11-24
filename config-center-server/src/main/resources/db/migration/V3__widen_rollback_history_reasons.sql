ALTER TABLE config_item_history MODIFY COLUMN reason VARCHAR(600) NULL;
ALTER TABLE feature_flag_history MODIFY COLUMN reason VARCHAR(600) NULL;
