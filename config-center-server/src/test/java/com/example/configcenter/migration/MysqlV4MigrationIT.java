package com.example.configcenter.migration;

import com.example.configcenter.config.ApiKeyProperties;
import com.example.configcenter.migration.IdentityMigrationPlan.Scope;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class MysqlV4MigrationIT {

    private static final List<String> TABLES = List.of("config_item", "feature_flag", "config_namespace_revision",
            "config_item_history", "feature_flag_history");
    private static final Scope ORIGINAL = new Scope("legacy-cafe", "dev");
    private static final Scope SELECTED = new Scope("Legacy-cafe", "Dev");
    private static final Scope OTHER = new Scope("independent", "dev");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static boolean isolated;

    @TempDir Path directory;

    @BeforeAll
    static void requireDisposableMysql() throws Exception {
        String url = System.getenv("CONFIG_CENTER_DB_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/config_center_it(?:\\?.*)?"),
                "V4 fixture requires the isolated loopback config_center_it catalog");
        dataSource = new DriverManagerDataSource(url, System.getenv("CONFIG_CENTER_DB_USERNAME"),
                System.getenv("CONFIG_CENTER_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            assertEquals("config_center_it", connection.getCatalog());
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        assertNotEquals("root", jdbc.queryForObject("SELECT SUBSTRING_INDEX(CURRENT_USER(),'@',1)", String.class));
        isolated = true;
    }

    @BeforeEach
    void createRealV3() {
        resetFixture();
        var v3 = Flyway.configure().dataSource(dataSource).target("3").load();
        assertEquals(3, v3.migrate().migrationsExecuted);
        v3.validate();
        assertEquals(3, migrationIdentity().size());
    }

    @AfterAll
    static void leaveAnEmptyLatestFixtureForOtherSuites() {
        if (isolated) {
            resetFixture();
            assertEquals(4, latest(authorization(ORIGINAL, SELECTED, OTHER), "").migrate().migrationsExecuted);
        }
    }

    private static void resetFixture() {
        // The Maven mysql-it profile runs these suites serially on its own throwaway database.
        jdbc.execute("DROP TABLE IF EXISTS write_receipt");
        for (String table : TABLES) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("DROP TABLE IF EXISTS flyway_schema_history");
    }

    @Test
    void approvedMixedMigrationKeepsAuditSnapshotsAndAssociatesTheExactCurrentOwner() throws Exception {
        seedMixed();
        var authorization = authorization(ORIGINAL, SELECTED, OTHER);
        var before = rows();
        var priorMigrations = migrationIdentity();
        var columnDefinitions = unaffectedColumns();
        var group = group(authorization, ORIGINAL);
        assertTrue(group.needsMapping());
        assertEquals(2, group.persisted().size());
        assertEquals(2, group.grants().size());
        assertTrue(group.problems().isEmpty());

        var migration = latest(authorization, mapping(group.fingerprint(), SELECTED));
        assertEquals(1, migration.migrate().migrationsExecuted);
        migration.validate();
        assertEquals(0, migration.migrate().migrationsExecuted);
        assertEquals(priorMigrations, migrationIdentity().subList(0, 3));
        assertEquals(List.of("1", "2", "3", "4"), jdbc.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank
                """, String.class));

        assertEquals(columnDefinitions, unaffectedColumns());
        var after = rows();
        for (String table : TABLES) {
            var original = before.get(table);
            var upgraded = after.get(table);
            assertEquals(original.size(), upgraded.size());
            for (int index = 0; index < original.size(); index++) {
                var expected = new LinkedHashMap<>(original.get(index));
                var actual = new LinkedHashMap<>(upgraded.get(index));
                if (table.endsWith("_history")) {
                    long id = ((Number) expected.get("id")).longValue();
                    long owner = table.equals("config_item_history")
                            ? (id == 13 ? 102 : 101) : (id == 23 ? 202 : 201);
                    assertEquals(owner, ((Number) actual.remove("owner_id")).longValue());
                } else if (!expected.get("app").equals(OTHER.app())) {
                    expected.put("app", SELECTED.app());
                    expected.put("env", SELECTED.env());
                }
                assertEquals(expected, actual, table + " id=" + expected.get("id"));
            }
        }
        assertEquals(List.of(19L), jdbc.queryForList("""
                SELECT revision FROM config_namespace_revision WHERE app=? AND env=?
                """, Long.class, SELECTED.app(), SELECTED.env()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM config_item WHERE app=? AND env=?
                """, Integer.class, ORIGINAL.app(), ORIGINAL.env()));
        assertEquals(List.of(1L, 3L), jdbc.queryForList("""
                SELECT version FROM config_item_history WHERE owner_id=101 ORDER BY version
                """, Long.class));
        assertEquals("文".repeat(500), jdbc.queryForObject(
                "SELECT SUBSTRING(reason,16) FROM config_item_history WHERE id=12", String.class));
    }

    @Test
    void aSingleExactScopeWithHistoricalKeyAliasesNeedsNoMapping() throws Exception {
        seedMixed();
        for (String table : TABLES) {
            jdbc.update("UPDATE " + table + " SET app=?,env=? WHERE app=? AND env=?",
                    ORIGINAL.app(), ORIGINAL.env(), SELECTED.app(), SELECTED.env());
        }
        var before = rows();
        var auth = authorization(ORIGINAL, OTHER);
        var group = group(auth, ORIGINAL);
        assertFalse(group.needsMapping());
        var flyway = latest(auth, "");
        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();
        var after = rows();
        for (String table : TABLES) {
            var actual = after.get(table);
            actual.forEach(row -> row.remove("owner_id"));
            assertEquals(before.get(table), actual);
        }
        assertEquals(List.of("cafe", "Cafe"), jdbc.queryForList("""
                SELECT config_key FROM config_item_history WHERE owner_id=101 ORDER BY version
                """, String.class));
        assertEquals(List.of("café", "Cafe"), jdbc.queryForList("""
                SELECT name FROM feature_flag_history WHERE owner_id=201 ORDER BY version
                """, String.class));
    }

    @ParameterizedTest
    @EnumSource(BadMapping.class)
    void rejectedMappingFailsBeforeBusinessDataOrDdlChanges(BadMapping input) throws Exception {
        seedMixed();
        var authorization = input == BadMapping.TARGET_NOT_AUTHORIZED
                ? authorization(ORIGINAL, OTHER) : authorization(ORIGINAL, SELECTED, OTHER);
        var group = group(authorization, ORIGINAL);
        String file = switch (input) {
            case MISSING -> "";
            case CROSS_GROUP -> mapping(group.fingerprint(), OTHER);
            case TARGET_NOT_AUTHORIZED -> mapping(group.fingerprint(), SELECTED);
            case STALE_PAYLOAD -> {
                jdbc.update("UPDATE config_item_history SET reason='checksum changed' WHERE id=11");
                yield mapping(group.fingerprint(), SELECTED);
            }
            case UNNEEDED -> mapping(group(authorization, OTHER).fingerprint(), OTHER);
        };
        expectRefused(latest(authorization, file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing revision", "orphan history", "future history"})
    void migrationDoesNotGuessBrokenLegacyOwnership(String broken) throws Exception {
        seedMixed();
        switch (broken) {
            case "missing revision" -> jdbc.update("DELETE FROM config_namespace_revision WHERE id=401");
            case "orphan history" -> jdbc.update("UPDATE feature_flag_history SET name='orphan' WHERE id=21");
            case "future history" -> jdbc.update("UPDATE config_item_history SET version=999 WHERE id=12");
            default -> throw new AssertionError(broken);
        }
        var authorization = authorization(ORIGINAL, SELECTED, OTHER);
        var group = group(authorization, ORIGINAL);
        assertFalse(group.problems().isEmpty());
        expectRefused(latest(authorization, mapping(group.fingerprint(), SELECTED)));
    }

    @Test
    void competingAuthorizationAloneRequiresReviewAndUnusedIndependentGrantDoesNot() throws Exception {
        jdbc.update("""
                INSERT INTO feature_flag (id,app,env,name,enabled,rollout_percentage,allowlist_json,version,lock_version,updated_at)
                VALUES (201,'legacy-cafe','dev','cafe',true,50,'[]',1,0,NOW(6))
                """);
        var authorization = authorization(ORIGINAL, SELECTED, OTHER);
        var group = group(authorization, ORIGINAL);
        assertEquals(List.of(ORIGINAL), group.persisted());
        assertEquals(2, group.grants().size());
        assertTrue(group.needsMapping());
        assertFalse(group(authorization, OTHER).needsMapping());
        expectRefused(latest(authorization, ""));
    }

    private void expectRefused(Flyway flyway) {
        var before = rows();
        var structure = structure();
        var checksums = migrationIdentity();
        var failure = assertThrows(FlywayException.class, flyway::migrate);
        var reason = NestedExceptionUtils.getMostSpecificCause(failure);
        assertTrue(reason.getMessage().contains("V4 identity preflight refused"), reason::getMessage);
        assertEquals(before, rows());
        assertEquals(structure, structure());
        assertEquals(checksums, migrationIdentity().subList(0, 3));
        assertEquals(List.of(false), jdbc.queryForList("""
                SELECT success FROM flyway_schema_history WHERE version='4'
                """, Boolean.class));
    }

    private IdentityMigrationPlan.Group group(ApiKeyProperties properties, Scope member) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return IdentityMigrationPlan.inspect(connection, properties).groups().stream()
                    .filter(g -> g.persisted().contains(member) || g.grants().contains(member))
                    .findFirst().orElseThrow();
        }
    }

    private String mapping(String fingerprint, Scope target) throws Exception {
        var file = directory.resolve("mapping.json");
        IdentityMigrationPlan.JSON.writeValue(file.toFile(), new IdentityMigrationPlan.MappingDocument(
                List.of(new IdentityMigrationPlan.Mapping(fingerprint, target))));
        return file.toString();
    }

    private static ApiKeyProperties authorization(Scope... scopes) {
        var result = new ApiKeyProperties();
        for (Scope scope : scopes) {
            var item = new ApiKeyProperties.ApiKeyItem();
            item.setKey("synthetic-key-" + result.getApiKeys().size());
            item.setApp(scope.app());
            item.setEnv(scope.env());
            result.getApiKeys().add(item);
        }
        return result;
    }

    private static Flyway latest(ApiKeyProperties auth, String file) {
        return Flyway.configure().dataSource(dataSource).target("4")
                .javaMigrations(new V4__ExactNames(auth, file)).load();
    }

    private static List<Map<String, Object>> migrationIdentity() {
        return jdbc.queryForList("SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
    }

    private Map<String, List<Map<String, Object>>> rows() {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return snapshot;
    }

    private List<List<Map<String, Object>>> structure() {
        return List.of(jdbc.queryForList("""
                SELECT table_name,column_name,column_type,is_nullable,collation_name,column_default
                FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'
                ORDER BY table_name,ordinal_position
                """), jdbc.queryForList("""
                SELECT table_name,index_name,seq_in_index,column_name,non_unique,sub_part
                FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'
                ORDER BY table_name,index_name,seq_in_index
                """));
    }

    private List<Map<String, Object>> unaffectedColumns() {
        return jdbc.queryForList("""
                SELECT table_name,column_name,column_type,is_nullable,collation_name,column_default
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' AND column_name<>'owner_id'
                    AND NOT (table_name IN ('config_item','feature_flag','config_namespace_revision')
                             AND column_name IN ('app','env','config_key','name'))
                ORDER BY table_name,ordinal_position
                """);
    }

    private void seedMixed() {
        jdbc.update("""
                INSERT INTO config_item (id,app,env,config_key,config_value,description,version,lock_version,updated_at)
                VALUES (101,'Legacy-cafe','Dev','Cafe','new 🌏','description',3,2,'2026-01-01 02:03:04.567891'),
                       (102,'legacy-cafe','dev','other','second',NULL,1,0,'2026-01-01 02:03:04.567892')
                """);
        jdbc.update("""
                INSERT INTO feature_flag (id,app,env,name,enabled,rollout_percentage,allowlist_json,version,lock_version,updated_at)
                VALUES (201,'Legacy-cafe','Dev','Cafe',true,50,'["a+b","中文"]',4,3,'2026-01-01 02:03:04.123456'),
                       (202,'legacy-cafe','dev','other',false,1,'[]',1,0,'2026-01-01 02:03:04.123456'),
                       (203,'independent','dev','clean',false,0,'[]',1,0,'2026-01-01 02:03:04.000000')
                """);
        jdbc.update("""
                INSERT INTO config_namespace_revision (id,app,env,revision,lock_version)
                VALUES (401,'legacy-cafe','dev',19,18)
                """);
        jdbc.update("""
                INSERT INTO config_item_history
                   (id,app,env,config_key,config_value,description,version,action,operator,reason,created_at)
                VALUES (11,'legacy-cafe','dev','cafe','old',NULL,1,'UPSERT','owner',NULL,'2026-01-01 01:00:00.100001'),
                       (12,'Legacy-cafe','Dev','Cafe','new 🌏','description',3,'ROLLBACK','reviewer',?,'2026-01-01 01:00:00.100003'),
                       (13,'legacy-cafe','dev','other','second',NULL,1,'UPSERT',NULL,'','2026-01-01 01:00:00.200001')
                """, "rollback-to=1, " + "文".repeat(500));
        jdbc.update("""
                INSERT INTO feature_flag_history
                   (id,app,env,name,enabled,rollout_percentage,allowlist_json,version,action,operator,reason,created_at)
                VALUES (21,'legacy-cafe','dev','café',true,20,'[]',2,'UPSERT','👤','   ','2026-01-01 01:00:00.200002'),
                       (22,'Legacy-cafe','Dev','Cafe',true,50,'["a+b","中文"]',4,'ROLLBACK',NULL,?,'2026-01-01 01:00:00.200001'),
                       (23,'legacy-cafe','dev','other',false,1,'[]',1,'UPSERT',NULL,NULL,'2026-01-01 01:00:00.200001')
                """, "rollback-to=2, " + "界".repeat(500));
    }

    enum BadMapping { MISSING, CROSS_GROUP, TARGET_NOT_AUTHORIZED, STALE_PAYLOAD, UNNEEDED }
}
