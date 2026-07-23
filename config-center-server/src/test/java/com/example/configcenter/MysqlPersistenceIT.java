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

        assertEquals(List.of("1", "2"), appliedVersions);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertIndexExists("uk_cfg_hist_app_env_key_ver", 0);
        assertIndexExists("uk_ff_hist_app_env_name_ver", 0);
        assertIndexDoesNotExist("idx_cfg_hist_app_env_key_ver");
        assertIndexDoesNotExist("idx_ff_hist_app_env_name_ver");
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
                INSERT INTO config_item_history
                    (app, env, config_key, config_value, description, version,
                     action, operator, reason, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, "history-app", "dev", key, "value", version,
                "UPSERT", "mysql-it", "history uniqueness test");
    }

    private void insertFeatureHistory(String name, long version) {
        jdbcTemplate.update("""
                INSERT INTO feature_flag_history
                    (app, env, name, enabled, rollout_percentage, allowlist_json, version,
                     action, operator, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, "history-app", "dev", name, true, 50, "[]", version,
                "UPSERT", "mysql-it", "history uniqueness test");
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
