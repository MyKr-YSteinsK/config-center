package com.example.configcenter;

import com.example.configcenter.service.ConfigWatchNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.configcenter.LoopbackWriteFixture.encode;
import static com.example.configcenter.LoopbackWriteFixture.scope;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "rate-limit.enabled=false"
})
@Import(LoopbackWriteFixture.WatchRequests.class)
@Timeout(70)
abstract class WriteNameIdentityScenarios {
    private static final List<String> SPELLINGS = List.of(
            "cafe", "Cafe", "café", "cafe\u0301", " cafe", "cafe ", "中文", "cafe🌏");
    private static final Scope OBJECTS = new Scope("identity-objects", "dev");
    private static final Scope WATCH = new Scope("identity-watch", "dev");
    private static final Scope WATCH_ALIAS = new Scope("Identity-watch", "Dev");
    private static final String USER = "plan05-user-6";
    private static final String ALLOWED = "identity-allowlisted";
    private static final List<String> TABLES = List.of("config_item_history", "feature_flag_history",
            "config_item", "feature_flag", "config_namespace_revision");
    private static final List<Scope> AUTHORIZED = authorizedScopes();

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired LoopbackWriteFixture.WatchRequests watchRequests;
    @Autowired ConfigWatchNotifier notifier;
    @LocalServerPort int port;

    private LoopbackWriteFixture http;
    private boolean mysql;

    private static List<Scope> authorizedScopes() {
        List<Scope> scopes = new ArrayList<>();
        scopes.add(OBJECTS);
        scopes.add(WATCH);
        scopes.add(WATCH_ALIAS);
        for (String name : SPELLINGS) {
            scopes.add(appScope(name));
            scopes.add(new Scope("identity-env", name));
        }
        return List.copyOf(scopes);
    }

    private static Scope appScope(String spelling) {
        String name = spelling.startsWith(" ")
                ? " identity-app-" + spelling.substring(1) : "identity-app-" + spelling;
        return new Scope(name, "dev");
    }

    @DynamicPropertySource
    static void authorization(DynamicPropertyRegistry registry) {
        for (int i = 0; i < AUTHORIZED.size(); i++) {
            Scope scope = AUTHORIZED.get(i);
            String prefix = "security.api-keys[" + i + "].";
            registry.add(prefix + "key", scope::key);
            registry.add(prefix + "app", scope::app);
            registry.add(prefix + "env", scope::env);
        }
    }

    @BeforeEach
    void prepareFixture() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            mysql = metadata.getDatabaseProductName().equals("MySQL");
            System.out.printf("Exact identity engine=%s %s%n", metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion());
            if (mysql) {
                assertEquals("config_center_it", connection.getCatalog());
                assertNotEquals("root", jdbc.queryForObject(
                        "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class));
            } else {
                assertTrue(metadata.getURL().startsWith("jdbc:h2:mem:"));
            }
        }
        for (String table : TABLES) {
            for (Scope scope : AUTHORIZED) {
                jdbc.update("DELETE FROM " + table + " WHERE app=? AND env=?", scope.app(), scope.env());
            }
        }
        http = new LoopbackWriteFixture(mapper, port, watchRequests);
    }

    @Test
    void exactAppEnvKeyAndNameSurviveHttpAndParameterBinding() throws Exception {
        for (String dimension : List.of("app", "env", "object")) {
            var targets = SPELLINGS.stream().map(spelling -> switch (dimension) {
                case "app" -> new Target(appScope(spelling), "subject");
                case "env" -> new Target(new Scope("identity-env", spelling), "subject");
                default -> new Target(OBJECTS, spelling);
            }).toList();

            Target first = targets.get(0);
            putConfig(first, "value-0");
            putFlag(first.scope(), first.name(), true, List.of()).expect(200, 0);
            for (int i = 1; i < targets.size(); i++) {
                Target target = targets.get(i);
                readConfig(target).expect(404, 4041);
                eval(target, USER).expect(404, 4041);
                assertEquals(0, history("/api/configs/history", target, "key").size());
                assertEquals(0, history("/api/features/history", target, "name").size());
                if (!dimension.equals("object")) {
                    assertEquals(0, list(target.scope()).size());
                    assertTrue(revisions(target.scope()).isEmpty());
                }
                putConfig(target, "value-" + i);
                putFlag(target.scope(), target.name(), true, List.of()).expect(200, 0);
            }

            for (int i = 0; i < targets.size(); i++) {
                Target target = targets.get(i);
                var config = readConfig(target).expect(200, 0);
                assertEquals("value-" + i, config.path("value").asText());
                assertEquals(1, config.path("version").asInt());
                assertEquals(target.scope().app(), config.path("app").asText());
                assertEquals(target.scope().env(), config.path("env").asText());
                assertEquals(target.name(), config.path("key").asText());
                var flag = eval(target, USER).expect(200, 0);
                assertEquals(target.name(), flag.path("name").asText());
                assertEquals(1, history("/api/configs/history", target, "key").size());
                assertEquals(1, history("/api/features/history", target, "name").size());

                assertParameterizedIdentity("config_item", "config_key", target);
                assertParameterizedIdentity("feature_flag", "name", target);
                assertEquals(List.of(dimension.equals("object") ? 8L : 1L), revisions(target.scope()));
            }
        }

        if (mysql) {
            assertMysqlSchema();
        }
    }

    @Test
    void admissionWatchAndRolloutFollowTheExactRequestedName() throws Exception {
        Target lower = new Target(WATCH, "cafe");
        Target upper = new Target(WATCH_ALIAS, "cafe");
        try (var original = http.watch(WATCH.app(), WATCH.env(), 0, 12, "exact-original");
             var alias = http.watch(WATCH_ALIAS.app(), WATCH_ALIAS.env(), 0, 12, "exact-alias")) {
            http.post("/api/configs", WATCH.key(), "wrong-config", upper.scope().fields("key", upper.name(),
                    "value", "wrong")).expect(403, 4031);
            var forgedFlag = lower.scope().fields("name", lower.name(), "enabled", true);
            forgedFlag.put("rolloutPercentage", 50);
            http.post("/api/features", WATCH_ALIAS.key(), "wrong-flag", forgedFlag).expect(403, 4031);
            assertEquals(0, list(WATCH).size());
            assertEquals(0, list(WATCH_ALIAS).size());
            assertEquals(0, http.get("/api/features" + WATCH.query()).expect(200, 0).size());

            putConfig(lower, "original");
            original.expect(true, 1);
            assertFalse(alias.response().isDone());
            assertEquals(1, notifier.pendingWaiterCount());
            putConfig(upper, "separate");
            alias.expect(true, 1);
        }
        assertEquals("original", readConfig(lower).expect(200, 0).path("value").asText());
        assertEquals("separate", readConfig(upper).expect(200, 0).path("value").asText());
        assertEquals(List.of(1L), revisions(WATCH));
        assertEquals(List.of(1L), revisions(WATCH_ALIAS));
        var caughtUp = http.get("/api/configs/watch" + WATCH.query() + "&sinceVersion=0&timeoutSeconds=1")
                .expect(200, 0);
        assertTrue(caughtUp.path("changed").booleanValue());
        assertEquals(1, caughtUp.path("latestVersion").intValue());
        try (var current = http.watch(WATCH.app(), WATCH.env(), 1, 1, "exact-current")) {
            current.expect(false, 1);
        }
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());

        Map<String, Integer> buckets = Map.of("cafe", 3, "Cafe", 68, "café", 4, "cafe ", 58);
        for (var sample : buckets.entrySet()) {
            putFlag(WATCH, sample.getKey(), true, List.of(ALLOWED)).expect(200, 0);
            var target = new Target(WATCH, sample.getKey());
            JsonNode result = eval(target, USER).expect(200, 0);
            assertEquals(sample.getValue(), result.path("bucket").intValue());
            assertEquals(sample.getValue() < 50, result.path("enabled").booleanValue());
            var allowed = eval(target, ALLOWED).expect(200, 0);
            assertTrue(allowed.path("enabled").booleanValue());
            assertEquals(-1, allowed.path("bucket").intValue());
        }
        putFlag(WATCH, "cafe", false, List.of(ALLOWED)).expect(200, 0);
        var off = eval(lower, ALLOWED).expect(200, 0);
        assertFalse(off.path("enabled").booleanValue());
        assertEquals(-1, off.path("bucket").intValue());
        assertEquals(List.of(1L), revisions(WATCH));
    }

    @Test
    void malformedUnicodeNamesReturnParameterErrorWithoutWriting() throws Exception {
        for (var request : List.of(
                new Object[]{"/api/configs", "key", "subject", "value", "v"},
                new Object[]{"/api/features", "name", "subject", "rolloutPercentage", 25},
                new Object[]{"/api/configs/rollback", "key", "subject", "targetVersion", 1},
                new Object[]{"/api/features/rollback", "name", "subject", "targetVersion", 1})) {
            Map<String, Object> fields = OBJECTS.fields((String) request[1], request[2],
                    (String) request[3], request[4]);
            fields.put("enabled", true);
            for (String field : List.of("app", "env", (String) request[1])) {
                fields.put(field, "invalid-unicode-marker");
                String json = mapper.writeValueAsString(fields)
                        .replace("\"invalid-unicode-marker\"", "\"\\ud800\"");
                http.postJson((String) request[0], OBJECTS.key(), "invalid-surrogate", json).expect(400, 4001);
                fields.put(field, switch (field) {
                    case "app" -> OBJECTS.app();
                    case "env" -> OBJECTS.env();
                    default -> "subject";
                });
            }
        }
        assertTrue(revisions(OBJECTS).isEmpty());
        assertEquals(0, list(OBJECTS).size());
        assertEquals(0, http.get("/api/features" + OBJECTS.query()).expect(200, 0).size());
    }

    private void assertMysqlSchema() {
        assertEquals("NO PAD", jdbc.queryForObject("""
                SELECT PAD_ATTRIBUTE FROM information_schema.collations WHERE collation_name='utf8mb4_0900_bin'
                """, String.class));
        assertEquals("utf8mb4", jdbc.queryForObject("SELECT @@character_set_connection", String.class));
        for (String table : List.of("config_item", "feature_flag", "config_namespace_revision")) {
            var keys = jdbc.queryForList("""
                    SELECT CONCAT(column_name, ':', character_maximum_length, ':', collation_name, ':', is_nullable)
                    FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?
                        AND column_name IN ('app','env','config_key','name') ORDER BY column_name
                    """, String.class, table);
            List<String> expected = switch (table) {
                case "config_item" -> List.of("app:100", "config_key:200", "env:50");
                case "feature_flag" -> List.of("app:100", "env:50", "name:200");
                default -> List.of("app:100", "env:50");
            };
            assertEquals(expected.stream().map(column -> column + ":utf8mb4_0900_bin:NO").toList(), keys);
        }
        for (String index : List.of("uk_cfg_hist_owner_ver", "uk_ff_hist_owner_ver")) {
            assertEquals(List.of("owner_id", "version"), jdbc.queryForList("""
                    SELECT column_name FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND index_name=? AND non_unique=0 ORDER BY seq_in_index
                    """, String.class, index));
        }
        assertEquals("utf8mb4_unicode_ci", jdbc.queryForObject("""
                SELECT collation_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='config_item' AND column_name='config_value'
                """, String.class));
    }

    private void assertParameterizedIdentity(String table, String nameColumn, Target target) {
        var rows = jdbc.queryForList("SELECT app,env," + nameColumn + " FROM " + table
                        + " WHERE app=? AND env=? AND " + nameColumn + "=?",
                target.scope().app(), target.scope().env(), target.name());
        assertEquals(1, rows.size());
        assertEquals(target.scope().app(), rows.get(0).get("app"));
        assertEquals(target.scope().env(), rows.get(0).get("env"));
        assertEquals(target.name(), rows.get(0).get(nameColumn));
    }

    private void putConfig(Target target, String value) throws Exception {
        var scope = target.scope();
        http.post("/api/configs", scope.key(), "exact-write",
                scope.fields("key", target.name(), "value", value)).expect(200, 0);
    }

    private LoopbackWriteFixture.Call putFlag(Scope scope, String name, boolean enabled, List<String> allowlist)
            throws Exception {
        var body = scope.fields("name", name, "enabled", enabled);
        body.put("allowlist", allowlist);
        body.put("rolloutPercentage", 50);
        return http.post("/api/features", scope.key(), "exact-feature", body);
    }

    private JsonNode list(Scope scope) throws Exception {
        return http.get("/api/configs" + scope.query()).expect(200, 0);
    }

    private JsonNode history(String route, Target target, String field) throws Exception {
        return http.get(route + target.scope().query() + "&" + field + "=" + encode(target.name())).expect(200, 0);
    }

    private LoopbackWriteFixture.Call readConfig(Target target) throws Exception {
        return http.get("/api/configs/" + encode(target.name()).replace("+", "%20") + target.scope().query());
    }

    private LoopbackWriteFixture.Call eval(Target target, String user) throws Exception {
        return http.get("/api/features/evaluate" + target.scope().query()
                + "&name=" + encode(target.name()) + "&userId=" + encode(user));
    }

    private List<Long> revisions(Scope scope) {
        return jdbc.queryForList("SELECT revision FROM config_namespace_revision WHERE app=? AND env=?",
                Long.class, scope.app(), scope.env());
    }

    private record Target(Scope scope, String name) {}
    private record Scope(String app, String env) {
        String query() {
            return scope(app, env);
        }

        String key() {
            return "exact-fixture-" + AUTHORIZED.indexOf(this);
        }

        Map<String, Object> fields(String name, Object value, String other, Object otherValue) {
            return new HashMap<>(Map.of("app", app, "env", env, name, value, other, otherValue));
        }
    }
}
