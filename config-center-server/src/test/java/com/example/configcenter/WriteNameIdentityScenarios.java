package com.example.configcenter;

import com.example.configcenter.service.ConfigWatchNotifier;
import com.example.configcenter.service.FeatureEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.configcenter.LoopbackWriteFixture.encode;
import static com.example.configcenter.LoopbackWriteFixture.scope;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "rate-limit.enabled=false",
        "security.api-keys[0].key=plan05-base-key", "security.api-keys[0].app=plan05-cafe", "security.api-keys[0].env=dev",
        "security.api-keys[1].key=plan05-case-key", "security.api-keys[1].app=Plan05-cafe", "security.api-keys[1].env=dev",
        "security.api-keys[2].key=plan05-other-key", "security.api-keys[2].app=plan05-other", "security.api-keys[2].env=dev"
})
@Import(LoopbackWriteFixture.WatchRequests.class)
@Timeout(35)
abstract class WriteNameIdentityScenarios {
    private static final String BASE = "plan05-cafe";
    private static final String CASE = "Plan05-cafe";
    private static final String OTHER = "plan05-other";
    private static final String ENV = "dev";
    private static final String BASE_KEY = "plan05-base-key";
    private static final String CASE_KEY = "plan05-case-key";
    private static final String OTHER_KEY = "plan05-other-key";
    private static final String NAME = "cafe";
    private static final String CASE_NAME = "Cafe";
    private static final String CJK_NAME = "中文旗标";
    private static final String USER = "plan05-user-6";
    private static final String ALLOWED = "plan05-allowed";
    private static final List<String> TABLES = List.of("config_item", "config_item_history", "feature_flag",
            "feature_flag_history", "config_namespace_revision");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired LoopbackWriteFixture.WatchRequests watchRequests;
    @Autowired ConfigWatchNotifier notifier;
    @LocalServerPort int port;

    @Test
    void compareNameIdentityAtSqlHttpAuthorizationWatchAndEvaluation() throws Exception {
        boolean mysql;
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            System.out.printf("Plan05 identity engine=%s %s%n", metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion());
            mysql = metadata.getDatabaseProductName().equals("MySQL");
            if (mysql) {
                assertEquals("config_center_it", connection.getCatalog());
                assertNotEquals("root", jdbc.queryForObject(
                        "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class));
            } else {
                assertEquals("H2", metadata.getDatabaseProductName());
                assertTrue(metadata.getURL().startsWith("jdbc:h2:mem:"));
            }
        }
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table + " WHERE app IN (?,?,?) AND env=?", BASE, CASE, OTHER, ENV);
        }

        var http = new LoopbackWriteFixture(mapper, port, watchRequests);
        putConfig(http, BASE, BASE_KEY, NAME, "base-v1").expect(200, 0);
        putFlag(http, BASE, BASE_KEY, NAME, true, "base-enabled").expect(200, 0);
        if (mysql) {
            System.out.println("Plan05 identity collation=" + jdbc.queryForList(
                    "SELECT table_name, column_name, collation_name FROM information_schema.columns "
                            + "WHERE table_schema=DATABASE() AND column_name IN ('app','env','config_key','name') "
                            + "ORDER BY table_name, column_name"));
        }

        for (String app : List.of(BASE, CASE, "plan05-café", "plan05-cafe ", OTHER, "计划五")) {
            observe(http, app, NAME);
        }
        for (String name : List.of(NAME, CASE_NAME, "café", "cafe ", "tea", CJK_NAME)) {
            observe(http, BASE, name);
        }

        // Each key permits its exact scope. Reads below use the real table comparison.
        var wrongConfig = putConfig(http, CASE, BASE_KEY, NAME, "denied-config");
        var wrongFlag = putFlag(http, BASE, CASE_KEY, NAME, false, "denied-flag");
        wrongConfig.expect(403, 4031);
        wrongFlag.expect(403, 4031);
        assertEquals(List.of(1L), revisions(BASE));
        assertEquals("base-v1", config(http, BASE, NAME).expect(200, 0).path("value").asText());
        assertTrue(eval(http, BASE, NAME, ALLOWED).expect(200, 0).path("enabled").booleanValue());
        System.out.printf("Plan05 authority wrong-scope config=%d flag=%d%n", wrongConfig.status(), wrongFlag.status());

        try (var same = http.watch(BASE, ENV, 1, 8, "identity-exact-watch");
             var independent = http.watch(OTHER, ENV, 0, 3, "identity-isolated-watch")) {
            putConfig(http, BASE, BASE_KEY, NAME, "base-v2").expect(200, 0);
            same.expect(true, 2);
            independent.expect(false, 0);
            System.out.printf("Plan05 exact Watch changed=true revision=2 trace=%s and isolated Watch changed=false revision=0 trace=%s%n",
                    same.trace(), independent.trace());
        }

        var aliasRevisionsBeforeWrite = revisions(CASE);
        boolean sharedNamespace = !aliasRevisionsBeforeWrite.isEmpty();
        try (var alreadyParked = http.watch(BASE, ENV, 2, 3, "identity-alias-watch")) {
            var alias = putConfig(http, CASE, CASE_KEY, CASE_NAME, "alias-write").expect(200, 0);
            assertEquals(CASE, alias.path("app").asText());
            assertEquals(CASE_NAME, alias.path("key").asText());
            var actualWait = LoopbackWriteFixture.await(alreadyParked.response());
            JsonNode observed = actualWait.expect(200, 0);
            assertEquals(alreadyParked.trace(), actualWait.trace());
            assertEquals(1, revisions(CASE).size());
            long actualBaseRevision = revisions(BASE).get(0);
            var next = http.get("/api/configs/watch" + scope(BASE, ENV) + "&sinceVersion=2&timeoutSeconds=1");
            var nextData = next.expect(200, 0);
            assertEquals(actualBaseRevision > 2, nextData.path("changed").booleanValue());
            assertEquals(actualBaseRevision, nextData.path("latestVersion").longValue());
            // If the registered watch timed out, it reports its registration snapshot.
            assertEquals(observed.path("changed").booleanValue() ? actualBaseRevision : 2,
                    observed.path("latestVersion").longValue());

            assertEquals(sharedNamespace ? "alias-write" : "base-v2",
                    config(http, BASE, NAME).expect(200, 0).path("value").asText());
            assertEquals("alias-write", config(http, CASE, CASE_NAME).expect(200, 0).path("value").asText());
            String revisionSpelling = jdbc.queryForObject(
                    "SELECT app FROM config_namespace_revision WHERE app=? AND env=?", String.class, CASE, ENV);
            System.out.printf("Plan05 alias scope shared=%s write=200 v%d revisionApp=%s waited=%s trace=%s next=%s nextTrace=%s final=%s history=%s%n",
                    sharedNamespace, alias.path("version").longValue(), revisionSpelling, observed, actualWait.trace(),
                    nextData, next.trace(), config(http, BASE, NAME).body().path("data"),
                    spellings(history(http, "/api/configs", BASE, "key", NAME).body().path("data"), "key"));
        }

        var aliasEval = eval(http, BASE, CASE_NAME, ALLOWED);
        if (aliasEval.status() == 200) {
            assertAllowed(aliasEval.expect(200, 0));
        } else {
            aliasEval.expect(404, 4041);
        }
        var flag = putFlag(http, CASE, CASE_KEY, CASE_NAME, false, "alias-close").expect(200, 0);
        assertEquals(CASE, flag.path("app").asText());
        assertEquals(CASE_NAME, flag.path("name").asText());
        var closed = eval(http, CASE, CASE_NAME, ALLOWED).expect(200, 0);
        assertFalse(closed.path("enabled").booleanValue());
        assertEquals(-1, closed.path("bucket").intValue());
        var flagHistory = history(http, "/api/features", BASE, "name", NAME).expect(200, 0);
        var baseFlag = http.get("/api/features" + scope(BASE, ENV)).expect(200, 0).get(0);
        System.out.printf("Plan05 flag exact alias write=200 returned=%s storedBase=%s/%s history=%s closedAllowed=%s%n",
                spellings(mapper.createArrayNode().add(flag), "name"), baseFlag.path("app").asText(),
                baseFlag.path("name").asText(), spellings(flagHistory, "name"), closed);
        assertEquals(sharedNamespace ? 2 : 1, flagHistory.size());
        assertEquals(sharedNamespace ? CASE_NAME : NAME, baseFlag.path("name").asText());

        var before = revisions(BASE);
        putConfig(http, OTHER, OTHER_KEY, CJK_NAME, "独立值").expect(200, 0);
        putFlag(http, OTHER, OTHER_KEY, CJK_NAME, true, "independent-flag").expect(200, 0);
        assertEquals("独立值", config(http, OTHER, CJK_NAME).expect(200, 0).path("value").asText());
        assertAllowed(eval(http, OTHER, CJK_NAME, ALLOWED).expect(200, 0));
        assertEquals(before, revisions(BASE));
        assertEquals(List.of(1L), revisions(OTHER));
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    private void observe(LoopbackWriteFixture http, String app, String name) throws Exception {
        int configRows = rows("config_item", "config_key", app, name);
        int configHistory = rows("config_item_history", "config_key", app, name);
        int flagRows = rows("feature_flag", "name", app, name);
        int flagHistory = rows("feature_flag_history", "name", app, name);
        var read = config(http, app, name);
        var flag = eval(http, app, name, USER);
        assertEquals(configRows == 1 ? 200 : 404, read.status(), read.body()::toString);
        assertEquals(flagRows == 1 ? 200 : 404, flag.status(), flag.body()::toString);
        assertEquals(configHistory,
                history(http, "/api/configs", app, "key", name).expect(200, 0).size());
        assertEquals(flagHistory,
                history(http, "/api/features", app, "name", name).expect(200, 0).size());

        String bucket = "n/a";
        if (flag.status() == 200) {
            var data = flag.expect(200, 0);
            int expected = new FeatureEvaluator().stableBucket(USER, name);
            assertEquals(expected, data.path("bucket").intValue());
            assertEquals(expected < 50, data.path("enabled").booleanValue());
            bucket = data.path("bucket").intValue() + "/" + data.path("enabled").booleanValue();
        }
        System.out.printf("Plan05 matrix app=\"%s\" name=\"%s\" javaExact=(%s,%s) sql=(%d,%d,%d,%d,%s) HTTP=(%d,%d) bucket=%s%n",
                app, name, app.equals(BASE), name.equals(NAME), configRows, configHistory,
                flagRows, flagHistory, revisions(app), read.status(), flag.status(), bucket);
    }

    private List<String> spellings(JsonNode rows, String nameField) {
        List<String> values = new ArrayList<>();
        for (JsonNode row : rows) {
            values.add(row.path("version").longValue() + ":" + row.path("app").asText()
                    + "/" + row.path(nameField).asText());
        }
        return values;
    }

    private List<Long> revisions(String app) {
        return jdbc.queryForList("SELECT revision FROM config_namespace_revision WHERE app=? AND env=?",
                Long.class, app, ENV);
    }

    private int rows(String table, String nameField, String app, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE app=? AND env=? AND " + nameField + "=?",
                Integer.class, app, ENV, name);
    }

    private void assertAllowed(JsonNode data) {
        assertTrue(data.path("enabled").booleanValue());
        assertEquals(-1, data.path("bucket").intValue());
    }

    private LoopbackWriteFixture.Call putConfig(LoopbackWriteFixture http, String app, String key, String name,
                                                 String value) throws Exception {
        return http.post("/api/configs", key, value.startsWith("独立") ? "cjk-write" : value,
                Map.of("app", app, "env", ENV, "key", name, "value", value));
    }

    private LoopbackWriteFixture.Call putFlag(LoopbackWriteFixture http, String app, String key, String name,
                                               boolean enabled, String trace) throws Exception {
        return http.post("/api/features", key, trace, Map.of("app", app, "env", ENV, "name", name,
                "enabled", enabled, "rolloutPercentage", 50, "allowlist", List.of(ALLOWED)));
    }

    private LoopbackWriteFixture.Call history(LoopbackWriteFixture http, String path, String app,
                                               String field, String name) throws Exception {
        return http.get(path + "/history" + scope(app, ENV) + "&" + field + "=" + encode(name));
    }

    private LoopbackWriteFixture.Call config(LoopbackWriteFixture http, String app, String name) throws Exception {
        return http.get("/api/configs/" + encode(name).replace("+", "%20") + scope(app, ENV));
    }

    private LoopbackWriteFixture.Call eval(LoopbackWriteFixture http, String app, String name, String user)
            throws Exception {
        return http.get("/api/features/evaluate" + scope(app, ENV) + "&name=" + encode(name) + "&userId=" + encode(user));
    }
}
