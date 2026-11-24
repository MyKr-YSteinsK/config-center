package com.example.configcenter;

import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.RollbackFeatureRequest;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.ConfigWatchNotifier;
import com.example.configcenter.service.FeatureFlagService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rate-limit.enabled=false")
@AutoConfigureMockMvc
class RollbackBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConfigService configs;
    @Autowired FeatureFlagService features;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entities;
    @SpyBean ConfigWatchNotifier notifier;

    private static final String APP = "demo-app";
    private static final String ENV = "dev";
    private static final String KEY = "boundary";

    @BeforeEach
    void seedTwoVersions() throws Exception {
        for (String table : List.of("config_item_history", "feature_flag_history", "config_item",
                "feature_flag", "config_namespace_revision")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (int version = 1; version <= 2; version++) {
            send("configs", configBody().put("value", "v" + version), 200);
            send("features", featureBody().put("enabled", version == 1)
                    .put("rolloutPercentage", version * 10), 200);
        }
    }

    @Test
    void httpRollbackPreservesReasonSnapshotAndHistory() throws Exception {
        long version = 2;
        var reasons = java.util.Arrays.asList(null, "", "   ", "发布备注", "x".repeat(500),
                "文".repeat(500), "🌏".repeat(250));
        for (String reason : reasons) {
            var cfg = configBody().put("targetVersion", 1);
            var flag = featureBody().put("targetVersion", 1);
            if (reason != null) {
                cfg.put("reason", reason);
                flag.put("reason", reason);
            }
            var oldConfigs = rows("config_item_history");
            var oldFeatures = rows("feature_flag_history");
            var restored = send("configs/rollback", cfg, 200).path("data");
            var restoredFlag = send("features/rollback", flag, 200).path("data");
            version++;
            assertEquals(version, restored.path("version").longValue());
            assertEquals("v1", restored.path("value").textValue());
            assertEquals(version, restoredFlag.path("version").longValue());
            assertTrue(restoredFlag.path("enabled").asBoolean());
            assertEquals(10, restoredFlag.path("rolloutPercentage").intValue());
            String expected = "rollback-to=1" + (reason == null ? "" : ", " + reason);
            assertEquals(expected, configs.history(APP, ENV, KEY).get(0).getReason());
            assertEquals(expected, features.history(APP, ENV, KEY).get(0).getReason());
            assertAppendOnly(oldConfigs, rows("config_item_history"));
            assertAppendOnly(oldFeatures, rows("feature_flag_history"));
            assertEquals(version, configs.latestVersion(APP, ENV));
        }
    }

    @Test
    void invalidReasonLeavesBothDomainsUntouched() throws Exception {
        var before = databaseState();
        clearInvocations(notifier);
        for (String path : List.of("configs", "features")) {
            ObjectNode request = path.equals("configs") ? configBody() : featureBody();
            request.put("targetVersion", 1).put("reason", "文".repeat(501));
            assertEquals(4001, send(path + "/rollback", request, 400).path("code").intValue());
        }
        assertEquals(before, databaseState());
        verifyNoInteractions(notifier);
    }

    @Test
    void failureAfterFlushRollsBackBothHistoriesAndRevision() {
        var before = databaseState();
        clearInvocations(notifier);
        RollbackConfigRequest cfg = new RollbackConfigRequest();
        cfg.setApp(APP);
        cfg.setEnv(ENV);
        cfg.setKey(KEY);
        cfg.setTargetVersion(1L);
        RollbackFeatureRequest flag = new RollbackFeatureRequest();
        flag.setApp(APP);
        flag.setEnv(ENV);
        flag.setName(KEY);
        flag.setTargetVersion(1L);
        assertThrows(SimulatedFailure.class, () -> transactions.executeWithoutResult(tx -> {
            configs.rollback(cfg);
            features.rollback(flag);
            entities.flush();
            assertEquals(3, rows("config_item_history").size());
            assertEquals(3, rows("feature_flag_history").size());
            assertEquals(3, configs.latestVersion(APP, ENV));
            throw new SimulatedFailure();
        }));
        // The test itself has no enclosing transaction. These queries see committed state.
        assertEquals(before, databaseState());
        verifyNoInteractions(notifier);
    }

    private static final class SimulatedFailure extends RuntimeException {}

    private void assertAppendOnly(List<Map<String, Object>> prior, List<Map<String, Object>> current) {
        assertEquals(prior.size() + 1, current.size());
        assertEquals(prior, current.subList(0, prior.size()));
    }

    private List<List<Map<String, Object>>> databaseState() {
        return List.of(rows("config_item"), rows("config_item_history"), rows("feature_flag"),
                rows("feature_flag_history"), rows("config_namespace_revision"));
    }

    private List<Map<String, Object>> rows(String table) {
        return jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id");
    }

    private ObjectNode configBody() {
        return json.createObjectNode().put("app", APP).put("env", ENV).put("key", KEY);
    }

    private ObjectNode featureBody() {
        return json.createObjectNode().put("app", APP).put("env", ENV).put("name", KEY);
    }

    private JsonNode send(String path, ObjectNode body, int expectedStatus) throws Exception {
        var response = mvc.perform(post("/api/" + path).header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
}
