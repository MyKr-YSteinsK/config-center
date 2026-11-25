package com.example.configcenter;

import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.RollbackFeatureRequest;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.request.UpsertFeatureRequest;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import com.example.configcenter.repository.FeatureFlagHistoryRepository;
import com.example.configcenter.repository.FeatureFlagRepository;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.FeatureFlagService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mysql")
class MysqlPersistenceIT {

    private static final String DATABASE = "config_center_it";

    @Autowired
    private ConfigService configService;

    @Autowired
    private FeatureFlagService featureService;

    @Autowired
    private ConfigItemRepository configRepository;

    @Autowired
    private ConfigItemHistoryRepository configHistoryRepository;

    @Autowired
    private ConfigNamespaceRevisionRepository revisionRepository;

    @Autowired
    private FeatureFlagRepository featureRepository;

    @Autowired
    private FeatureFlagHistoryRepository featureHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void cleanDedicatedSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(DATABASE, connection.getCatalog(),
                    "mysql-it may delete data only from the dedicated integration schema");
        }
        assertFalse("root".equalsIgnoreCase(jdbcTemplate.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class)));

        jdbcTemplate.update("DELETE FROM write_receipt");
        featureHistoryRepository.deleteAllInBatch();
        featureRepository.deleteAllInBatch();
        configHistoryRepository.deleteAllInBatch();
        configRepository.deleteAllInBatch();
        revisionRepository.deleteAllInBatch();
    }

    @Test
    void flywayMigrationIsAppliedAndRepeatable() {
        List<String> appliedVersions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);

        assertEquals(List.of("1", "2", "3", "4", "5"), appliedVersions);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertIndexExists("uk_cfg_hist_owner_ver", 0);
        assertIndexDoesNotExist("uk_cfg_hist_app_env_key_ver");
        assertIndexExists("uk_ff_hist_owner_ver", 0);
        assertIndexDoesNotExist("uk_ff_hist_app_env_name_ver");
        assertIndexDoesNotExist("idx_cfg_hist_app_env_key_ver");
        assertIndexDoesNotExist("idx_ff_hist_app_env_name_ver");
        assertIndexExists("uk_write_receipt_scope_key", 0);
        assertEquals(List.of("app", "env", "request_key"), jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND index_name='uk_write_receipt_scope_key'
                ORDER BY seq_in_index
                """, String.class));
        assertEquals(List.of("app:utf8mb4_0900_bin:100:NO", "env:utf8mb4_0900_bin:50:NO",
                "request_key:varbinary:128:NO", "result_json:text:65535:YES"), jdbcTemplate.queryForList("""
                SELECT CONCAT(column_name, ':', IF(column_name IN ('app','env'), collation_name, data_type), ':',
                              IF(column_name='result_json', character_octet_length, character_maximum_length),
                              ':', is_nullable)
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='write_receipt'
                AND column_name IN ('app', 'env', 'request_key', 'result_json')
                ORDER BY ordinal_position
                """, String.class));
        assertEquals(0, configRepository.count());
    }

    @Test
    void historyVersionsAreUniquePerBusinessKey() {
        insertConfigHistory("config-key", 1);
        assertThrows(DuplicateKeyException.class, () -> insertConfigHistory("config-key", 1));
        insertConfigHistory("config-key", 2);
        insertConfigHistory("other-config-key", 1);

        insertFeatureHistory("feature-one", 1);
        assertThrows(DuplicateKeyException.class, () -> insertFeatureHistory("feature-one", 1));
        insertFeatureHistory("feature-one", 2);
        insertFeatureHistory("feature-two", 1);

        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM config_item_history", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feature_flag_history", Integer.class));
    }

    @Test
    void historyQueriesAreBoundedAndUseExclusiveVersionCursors() {
        for (long version = 1; version <= 55; version++) {
            insertConfigHistory("config-paged", version);
            insertFeatureHistory("feature-paged", version);
        }

        var configFirstPage = configService.history("history-app", "dev", "config-paged", 50, null);
        var configNextPage = configService.history("history-app", "dev", "config-paged", 50, 6L);
        assertEquals(50, configFirstPage.size());
        assertEquals(55, configFirstPage.get(0).getVersion());
        assertEquals(6, configFirstPage.get(49).getVersion());
        assertEquals(5, configNextPage.size());
        assertEquals(5, configNextPage.get(0).getVersion());

        var featureFirstPage = featureService.history("history-app", "dev", "feature-paged", 50, null);
        var featureNextPage = featureService.history("history-app", "dev", "feature-paged", 50, 6L);
        assertEquals(50, featureFirstPage.size());
        assertEquals(55, featureFirstPage.get(0).getVersion());
        assertEquals(6, featureFirstPage.get(49).getVersion());
        assertEquals(5, featureNextPage.size());
        assertEquals(5, featureNextPage.get(0).getVersion());
    }

    @Test
    void configLifecyclePreservesUtf8HistoryAndNamespaceRevisionAcrossConnection() throws SQLException {
        UpsertConfigRequest request = configRequest("中文配置🚀", "第一版：你好，世界🌏", "初始说明😀");
        configService.upsert(request);

        request.setValue("第二版：灰度发布✨");
        request.setDescription("更新说明🧪");
        configService.upsert(request);

        RollbackConfigRequest rollback = new RollbackConfigRequest();
        rollback.setApp("mysql-it");
        rollback.setEnv("dev");
        rollback.setKey("中文配置🚀");
        rollback.setTargetVersion(1L);
        rollback.setOperator("集成测试");
        rollback.setReason("恢复稳定版本✅");

        var restored = configService.rollback(rollback);
        var history = configService.history("mysql-it", "dev", "中文配置🚀");

        assertEquals(3, restored.getVersion());
        assertEquals("第一版：你好，世界🌏", restored.getValue());
        assertEquals("初始说明😀", restored.getDescription());
        assertEquals(3, history.size());
        assertEquals("ROLLBACK", history.get(0).getAction());
        assertEquals(3, configService.latestVersion("mysql-it", "dev"));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT revision FROM config_namespace_revision WHERE app = ? AND env = ?")) {
            statement.setString(1, "mysql-it");
            statement.setString(2, "dev");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(3, result.getLong(1));
            }
        }
    }

    @Test
    void featureLifecyclePreservesUtf8HistoryAndRollback() {
        UpsertFeatureRequest request = featureRequest(false, 10, List.of("用户一😀"));
        featureService.upsert(request);

        request.setEnabled(true);
        request.setRolloutPercentage(80);
        request.setAllowlist(List.of("用户二🚀"));
        featureService.upsert(request);

        RollbackFeatureRequest rollback = new RollbackFeatureRequest();
        rollback.setApp("mysql-it");
        rollback.setEnv("dev");
        rollback.setName("结算开关✨");
        rollback.setTargetVersion(1L);
        rollback.setOperator("集成测试");
        rollback.setReason("恢复旧规则✅");

        var restored = featureService.rollback(rollback);
        var history = featureService.history("mysql-it", "dev", "结算开关✨");

        assertEquals(3, restored.getVersion());
        assertFalse(restored.isEnabled());
        assertEquals(10, restored.getRolloutPercentage());
        assertEquals(List.of("用户一😀"), restored.getAllowlist());
        assertEquals(3, history.size());
        assertEquals("ROLLBACK", history.get(0).getAction());
    }

    @Test
    void mysqlUniqueConstraintAndOptimisticLockAreEnforced() {
        UpsertConfigRequest request = configRequest("unique-key", "first", "writer-zero");
        configService.upsert(request);

        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO config_item
                    (app, env, config_key, config_value, description, version, lock_version, updated_at)
                VALUES (?, ?, ?, ?, NULL, 1, 0, CURRENT_TIMESTAMP(6))
                """, "mysql-it", "dev", "unique-key", "duplicate"));

        var firstWriter = configRepository
                .findByAppAndEnvAndConfigKey("mysql-it", "dev", "unique-key")
                .orElseThrow();
        var staleWriter = configRepository
                .findByAppAndEnvAndConfigKey("mysql-it", "dev", "unique-key")
                .orElseThrow();

        firstWriter.setDescription("writer-one");
        configRepository.saveAndFlush(firstWriter);

        staleWriter.setDescription("writer-two");
        assertThrows(OptimisticLockingFailureException.class,
                () -> configRepository.saveAndFlush(staleWriter));
    }

    private void assertIndexExists(String indexName, int nonUnique) {
        Integer matchingIndexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND index_name = ? AND non_unique = ?
                """, Integer.class, indexName, nonUnique);

        assertEquals(1, matchingIndexes);
    }

    private void assertIndexDoesNotExist(String indexName) {
        Integer matchingIndexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND index_name = ?
                """, Integer.class, indexName);

        assertEquals(0, matchingIndexes);
    }

    private void insertConfigHistory(String key, long version) {
        jdbcTemplate.update("""
                INSERT INTO config_item (app,env,config_key,config_value,version,lock_version,updated_at)
                SELECT 'history-app','dev',?,'value',55,0,CURRENT_TIMESTAMP(6)
                WHERE NOT EXISTS (SELECT 1 FROM config_item WHERE app='history-app' AND env='dev' AND config_key=?)
                """, key, key);
        jdbcTemplate.update("""
                INSERT INTO config_item_history
                    (owner_id,app,env,config_key,config_value,version,action,operator,reason,created_at)
                SELECT id,app,env,config_key,'value',?,'UPSERT','mysql-it','history fixture',CURRENT_TIMESTAMP(6)
                FROM config_item WHERE app='history-app' AND env='dev' AND config_key=?
                """, version, key);
    }

    private void insertFeatureHistory(String name, long version) {
        jdbcTemplate.update("""
                INSERT INTO feature_flag
                    (app,env,name,enabled,rollout_percentage,allowlist_json,version,lock_version,updated_at)
                SELECT 'history-app','dev',?,true,50,'[]',55,0,CURRENT_TIMESTAMP(6)
                WHERE NOT EXISTS (SELECT 1 FROM feature_flag WHERE app='history-app' AND env='dev' AND name=?)
                """, name, name);
        jdbcTemplate.update("""
                INSERT INTO feature_flag_history
                    (owner_id,app,env,name,enabled,rollout_percentage,allowlist_json,version,
                     action,operator,reason,created_at)
                SELECT id,app,env,name,true,50,'[]',?,'UPSERT','mysql-it','history fixture',CURRENT_TIMESTAMP(6)
                FROM feature_flag WHERE app='history-app' AND env='dev' AND name=?
                """, version, name);
    }

    private UpsertConfigRequest configRequest(String key, String value, String description) {
        UpsertConfigRequest request = new UpsertConfigRequest();
        request.setApp("mysql-it");
        request.setEnv("dev");
        request.setKey(key);
        request.setValue(value);
        request.setDescription(description);
        request.setOperator("集成测试");
        request.setReason("MySQL 自动回归");
        return request;
    }

    private UpsertFeatureRequest featureRequest(boolean enabled, int rollout, List<String> allowlist) {
        UpsertFeatureRequest request = new UpsertFeatureRequest();
        request.setApp("mysql-it");
        request.setEnv("dev");
        request.setName("结算开关✨");
        request.setEnabled(enabled);
        request.setRolloutPercentage(rollout);
        request.setAllowlist(allowlist);
        request.setOperator("集成测试");
        request.setReason("MySQL 自动回归");
        return request;
    }
}
