CREATE TABLE write_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app VARCHAR(100) NOT NULL,
  env VARCHAR(50) NOT NULL,
  request_key VARBINARY(128) NOT NULL,
  operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  intent_version SMALLINT NOT NULL,
  intent_digest VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_version SMALLINT NOT NULL,
  result_json TEXT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_write_receipt_scope_key UNIQUE (app, env, request_key)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
