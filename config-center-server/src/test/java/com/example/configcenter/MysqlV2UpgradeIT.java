package com.example.configcenter;

import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.RollbackFeatureRequest;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.FeatureFlagService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mysql")
@Import(MysqlV2UpgradeIT.UpgradeFixture.class)
@DirtiesContext
class MysqlV2UpgradeIT {
    private static final long TARGET = 1_000_000_000_000_000_000L;
    private static final List<String> TABLES = List.of("config_item", "feature_flag", "config_item_history",
            "feature_flag_history", "config_namespace_revision");

    @Autowired ConfigService configs;
    @Autowired FeatureFlagService features;

    @Test
    void upgradedApplicationRollsBackBothDomainsWithMaximumReason() {
        String reason = "界".repeat(500);
        String expected = "rollback-to=" + TARGET + ", " + reason;
        assertEquals(533, expected.length());
        RollbackConfigRequest cfg = new RollbackConfigRequest();
        cfg.setApp("legacy");
        cfg.setEnv("dev");
        cfg.setKey("k");
        cfg.setTargetVersion(TARGET);
        cfg.setReason(reason);
        var item = configs.rollback(cfg);
        assertEquals(TARGET + 2, item.getVersion());
        assertEquals("old", item.getValue());
        assertEquals(expected, configs.history("legacy", "dev", "k").get(0).getReason());

        RollbackFeatureRequest flag = new RollbackFeatureRequest();
        flag.setApp("legacy");
        flag.setEnv("dev");
        flag.setName("f");
        flag.setTargetVersion(TARGET);
        flag.setReason(reason);
        var restored = features.rollback(flag);
        assertEquals(TARGET + 2, restored.getVersion());
        assertTrue(restored.isEnabled());
        assertEquals(75, restored.getRolloutPercentage());
        assertEquals(List.of("u"), restored.getAllowlist());
        assertEquals(expected, features.history("legacy", "dev", "f").get(0).getReason());
        assertEquals(8, configs.latestVersion("legacy", "dev"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UpgradeFixture {
        @Bean
        FlywayMigrationStrategy upgradeFromV2() {
            return latest -> {
                var source = latest.getConfiguration().getDataSource();
                JdbcTemplate db = new JdbcTemplate(source);
                assertEquals("config_center_it", db.queryForObject("SELECT DATABASE()", String.class));
                assertNotEquals("root", db.queryForObject("SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class));
                // Only the application's test tables are reset. The mysql-it suites run serially.
                for (String table : TABLES) {
                    db.execute("DROP TABLE IF EXISTS " + table);
                }
                db.execute("DROP TABLE IF EXISTS flyway_schema_history");
                var v2 = Flyway.configure().configuration(latest.getConfiguration()).target("2").load();
                assertEquals(2, v2.migrate().migrationsExecuted);
                v2.validate();
                assertReasonColumns(db, 500);
                seedV2(db);
                var rows = snapshot(db);
                var indexes = indexes(db);
                var earlierMigrations = migrationIdentity(db);
                assertEquals(2, earlierMigrations.size());

                assertEquals(1, latest.migrate().migrationsExecuted);
                latest.validate();
                assertReasonColumns(db, 600);
                assertEquals(rows, snapshot(db));
                assertEquals(indexes, indexes(db));
                assertEquals(earlierMigrations, migrationIdentity(db));
                assertEquals(0, latest.migrate().migrationsExecuted);
                // Boot next validates the upgraded schema, then the test calls both rollback services.
            };
        }

        private static void seedV2(JdbcTemplate db) {
            db.update("""
                    INSERT INTO config_item (app,env,config_key,config_value,version,lock_version,updated_at)
                    VALUES ('legacy','dev','k','current',?,4,NOW(6))
                    """, TARGET + 1);
            db.update("""
                    INSERT INTO feature_flag (app,env,name,enabled,rollout_percentage,allowlist_json,
                                              version,lock_version,updated_at)
                    VALUES ('legacy','dev','f',false,0,'[]',?,4,NOW(6))
                    """, TARGET + 1);
            db.update("""
                    INSERT INTO config_namespace_revision (app,env,revision,lock_version)
                    VALUES ('legacy','dev',7,3)
                    """);
            long version = TARGET - 3;
            for (String reason : Arrays.asList(null, "", "   ", "旧".repeat(500), "current")) {
                boolean current = version == TARGET + 1;
                db.update("""
                        INSERT INTO config_item_history (app,env,config_key,config_value,version,action,reason,created_at)
                        VALUES ('legacy','dev','k',?,?,'UPSERT',?,NOW(6))
                        """, current ? "current" : "old", version, reason);
                db.update("""
                        INSERT INTO feature_flag_history (app,env,name,enabled,rollout_percentage,allowlist_json,
                                                          version,action,reason,created_at)
                        VALUES ('legacy','dev','f',?,?,?,?,'UPSERT',?,NOW(6))
                        """, !current, current ? 0 : 75, current ? "[]" : "[\"u\"]", version, reason);
                version++;
            }
        }

        private static List<List<Map<String, Object>>> snapshot(JdbcTemplate db) {
            return TABLES.stream().map(t -> db.queryForList("SELECT * FROM " + t + " ORDER BY id")).toList();
        }

        private static List<Map<String, Object>> indexes(JdbcTemplate db) {
            return db.queryForList("""
                    SELECT table_name, index_name, non_unique, seq_in_index, column_name
                    FROM information_schema.statistics WHERE table_schema = DATABASE()
                    ORDER BY table_name, index_name, seq_in_index
                    """);
        }

        private static List<Map<String, Object>> migrationIdentity(JdbcTemplate db) {
            return db.queryForList("""
                    SELECT version, script, checksum FROM flyway_schema_history
                    WHERE version IN ('1','2') AND success = 1 ORDER BY installed_rank
                    """);
        }

        private static void assertReasonColumns(JdbcTemplate db, int width) {
            for (String table : List.of("config_item_history", "feature_flag_history")) {
                assertEquals(1, db.queryForObject("""
                        SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                        AND table_name = ? AND column_name = 'reason' AND data_type = 'varchar'
                        AND character_maximum_length = ? AND is_nullable = 'YES'
                        """, Integer.class, table, width));
            }
        }
    }
}
