package com.example.configcenter.receipt;

import com.example.configcenter.LoopbackWriteFixture;
import com.example.configcenter.LoopbackWriteFixture.Call;
import com.example.configcenter.config.ApiKeyProperties;
import com.example.configcenter.service.ConfigWatchNotifier;
import com.example.configcenter.web.WriteHttpAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.example.configcenter.LoopbackWriteFixture.await;
import static com.example.configcenter.LoopbackWriteFixture.scope;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rate-limit.enabled=false")
@Import(LoopbackWriteFixture.WatchRequests.class)
abstract class WriteReceiptScenarios {
    private static final String APP = "receipt-app";
    private static final String ALIAS = "Receipt-app";
    private static final String ENV = "dev";
    private static final String KEY = "fixture-scope-one";
    private static final String ALTERNATE = "fixture-scope-rotation";
    private static final String ALIAS_KEY = "fixture-scope-two";
    private static final String LONG_APP = "界".repeat(100);
    private static final String LONG_ENV = "ét".repeat(25);
    private static final List<String> TABLES = List.of("config_item", "config_item_history",
            "config_namespace_revision", "feature_flag", "feature_flag_history", "write_receipt");

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired LoopbackWriteFixture.WatchRequests watchRequests;
    @Autowired ApiKeyProperties apiKeys;
    @Autowired JdbcTemplate db;
    @PersistenceContext EntityManager entityManager;
    @MockitoSpyBean WriteReceiptStore store;
    @MockitoSpyBean WriteReceiptCodec codec;
    @MockitoSpyBean ConfigWatchNotifier notifier;
    private LoopbackWriteFixture http;

    @DynamicPropertySource
    static void scopes(DynamicPropertyRegistry registry) {
        grant(registry, 0, APP, ENV, KEY);
        grant(registry, 1, APP, ENV, ALTERNATE);
        grant(registry, 2, ALIAS, ENV, ALIAS_KEY);
        grant(registry, 3, LONG_APP, LONG_ENV, KEY);
    }

    private static void grant(DynamicPropertyRegistry registry, int index, String app, String env, String key) {
        String prefix = "security.api-keys[" + index + "].";
        registry.add(prefix + "app", () -> app);
        registry.add(prefix + "env", () -> env);
        registry.add(prefix + "key", () -> key);
    }

    @BeforeEach
    void resetIsolatedTables() {
        if (mysql()) {
            assertEquals("config_center_it", db.queryForObject("SELECT DATABASE()", String.class));
            assertFalse("root".equalsIgnoreCase(db.queryForObject(
                    "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class)));
        } else {
            assertTrue(environment.matchesProfiles("test"));
        }
        TABLES.forEach(table -> db.update("DELETE FROM " + table));
        http = new LoopbackWriteFixture(json, port, watchRequests);
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void upsertAndRollbackReplayTheOriginalDataEvenAfterLaterWrites(Domain domain) throws Exception {
        var seed = domain.payload("item", 1);
        assertNull(post(domain.path, seed, "seed").header(WriteHttpAdapter.REPLAYED));
        var change = with(domain.payload("item", 2), "expectedVersion", 1);
        var first = numbered(post(domain.path, change, "change", "update-1"), "change", false, 2);
        var saved = snapshot();
        assertEquals(first, numbered(post(domain.path, change, "retry", "update-1"), "retry", true, 2));
        assertEquals(first, numbered(post(domain.path, change, "retry-again", "update-1"), "retry-again", true, 2));
        assertEquals(saved, snapshot());

        var next = domain.payload("item", 3);
        assertEquals(3, post(domain.path, next, "later").expect(200, 0).path("version").longValue());
        assertEquals(first, numbered(post(domain.path, change, "stale-retry", "update-1"), "stale-retry", true, 2));
        post(domain.path, change, "stale-new", "wrong-version").expect(409, 4091);
        assertEquals(1, count("write_receipt"));
        var rollback = domain.rollback("item", 1);
        var restored = numbered(post(domain.rollbackPath(), rollback, "rollback", "restore-1"), "rollback", false, 4);
        assertEquals(seed.get(domain.effect), json.convertValue(restored.path(domain.effect), Object.class));
        var rolled = snapshot();
        assertEquals(restored, numbered(post(domain.rollbackPath(), rollback, "rollback-retry", "restore-1"),
                "rollback-retry", true, 4));
        assertEquals(rolled, snapshot());
        post(domain.rollbackPath(), with(rollback, "targetVersion", 2), "other-target", "restore-1").expect(409, 4091);
        assertEquals(5, post(domain.path, next, "unkeyed-again").expect(200, 0).path("version").longValue());
        assertEquals(5, count(domain.history));
        assertEquals(2, count("write_receipt"));

        String list = "/api/configs" + scope(APP, ENV);
        String etag = http.get(list).header("ETag");
        try (var watch = http.watch(APP, ENV, domain.config() ? 5 : 0, 2, "unchanged-watch")) {
            assertEquals(restored, numbered(post(domain.rollbackPath(), rollback, "after-later", "restore-1"),
                    "after-later", true, 4));
            assertEquals(304, http.get(list, Map.of("If-None-Match", etag)).status());
            watch.expect(false, domain.config() ? 5 : 0);
        }
        assertEquals(domain.config() ? 5 : 0, revision());
        verify(notifier, times(domain.config() ? 5 : 0)).notifyChanged(anyString(), anyString(), anyLong());
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void headerGrammarIsStrictAndCaseSensitive() throws Exception {
        var fields = Domain.CONFIG.payload("item", 1);
        var empty = snapshot();
        for (String key : List.of("", "   ", "two keys", "é", "bad/key", "a,b", "a".repeat(129))) {
            var call = post(Domain.CONFIG.path, fields, "bad-header", key);
            call.expect(400, 4001);
            assertNull(call.header(WriteHttpAdapter.REPLAYED));
        }
        post(Domain.CONFIG.path, fields, "duplicates", "same", "same").expect(400, 4001);
        post(Domain.CONFIG.path, fields, "two-values", "first", "second").expect(400, 4001);
        assertEquals(empty, snapshot());
        numbered(post(Domain.CONFIG.path, fields, "one-char", "A"), "one-char", false, 1);
        numbered(post(Domain.CONFIG.path, fields, "other-case", "a"), "other-case", false, 2);
        String maximum = "Az-._".repeat(25) + "abc";
        assertEquals(128, maximum.length());
        numbered(post(Domain.CONFIG.path, fields, "maximum", maximum), "maximum", false, 3);
        assertEquals(3, count("write_receipt"));
    }

    @Test
    void equivalentDtoReplaysAndOtherIntentsConflictAcrossTheFourRoutes() throws Exception {
        String path = "/api/configs";
        var first = numbered(http.postJson(path, KEY, "initial", """
                {"key":"café","value":"café","env":"dev","app":"receipt-app"}
                """, "shared"), "initial", false, 1);
        var again = http.postJson(path, KEY, "equivalent", """
                {
                  "app":"receipt-app", "env":"dev", "key":"café", "description":null,
                  "reason":null, "operator":null, "expectedVersion":null, "value":"café"
                }
                """, "shared");
        assertEquals(first, numbered(again, "equivalent", true, 1));
        var before = snapshot();
        var fields = Map.<String, Object>of("app", APP, "env", ENV, "key", "café", "value", "café");
        for (var variation : Map.of("description", "", "operator", "writer", "reason", "comment",
                "key", "Cafe", "value", "cafe\u0301", "expectedVersion", 1).entrySet()) {
            expectReuse(post(path, with(fields, variation.getKey(), variation.getValue()), "mismatch", "shared"));
        }
        expectReuse(post(Domain.CONFIG.rollbackPath(), Domain.CONFIG.rollback("café", 1), "wrong-type", "shared"));
        expectReuse(post(Domain.FEATURE.path, Domain.FEATURE.payload("café", 1), "wrong-type", "shared"));
        expectReuse(post(Domain.FEATURE.rollbackPath(), Domain.FEATURE.rollback("café", 1), "wrong-type", "shared"));
        assertEquals(before, snapshot());

        var feature = with(Domain.FEATURE.payload("flag", 1), "allowlist", List.of("B", "A", "B"));
        var enabled = numbered(post(Domain.FEATURE.path, feature, "feature-order", "order"), "feature-order", false, 1);
        assertEquals(List.of("B", "A", "B"), json.convertValue(enabled.path("allowlist"), List.class));
        expectReuse(post(Domain.FEATURE.path, with(feature, "allowlist", List.of("A", "B", "B")), "reorder", "order"));
        expectReuse(post(Domain.FEATURE.path, with(feature, "name", "FLAG"), "another-name", "order"));
        expectReuse(post(Domain.FEATURE.path, with(feature, "enabled", false), "another-rule", "order"));
        var missing = Domain.FEATURE.payload("nullable", 0);
        var nullList = numbered(post(Domain.FEATURE.path, missing, "null-list", "null-list"), "null-list", false, 1);
        assertTrue(nullList.path("allowlist").isArray() && nullList.path("allowlist").isEmpty());
        assertEquals(nullList, numbered(post(Domain.FEATURE.path, with(missing, "allowlist", null), "explicit-null",
                "null-list"), "explicit-null", true, 1));
        expectReuse(post(Domain.FEATURE.path, with(missing, "allowlist", List.of()), "empty-list", "null-list"));
    }

    @Test
    void replayAlwaysUsesCurrentExactScopeAuthorization() throws Exception {
        var fields = Domain.CONFIG.payload("item", 1);
        var first = numbered(post(Domain.CONFIG.path, fields, "authorized", "auth-bound"), "authorized", false, 1);
        var before = snapshot();
        clearInvocations(store);
        for (String number : List.of("auth-bound", "unused-auth")) {
            for (String credential : List.of("ungranted-test", ALIAS_KEY)) {
                var denied = http.post(Domain.CONFIG.path, credential, "unauthorized", fields, number);
                assertTrue(denied.expect(403, 4031).isNull());
                assertNull(denied.header(WriteHttpAdapter.REPLAYED));
            }
        }
        http.post(Domain.CONFIG.path, KEY, "cross-case", with(fields, "app", ALIAS), "auth-bound").expect(403, 4031);
        http.post(Domain.CONFIG.path, null, "no-key", fields, "auth-bound").expect(403, 4031);
        http.post(Domain.CONFIG.path, KEY, "invalid-dto", with(fields, "value", ""), "auth-bound").expect(400, 4001);
        verifyNoInteractions(store);
        assertEquals(before, snapshot());
        var originalGrants = apiKeys.getApiKeys();
        try {
            apiKeys.setApiKeys(originalGrants.stream().filter(grant -> !grant.getKey().equals(KEY)).toList());
            http.post(Domain.CONFIG.path, KEY, "revoked", fields, "auth-bound").expect(403, 4031);
            var replay = http.post(Domain.CONFIG.path, ALTERNATE, "other-authorized", fields, "auth-bound");
            assertEquals(first, numbered(replay, "other-authorized", true, 1));
        } finally {
            apiKeys.setApiKeys(originalGrants);
        }
        assertEquals(before, snapshot());
    }

    @Test
    void maximumLegalResultsAndEscapesAreStoredWithoutTruncation() throws Exception {
        var sample = Domain.CONFIG.payload("item", 1);
        post(Domain.CONFIG.path, sample, "sample", "sample").expect(200, 0);
        var text = "\"\u0001界🧪".repeat(400);
        var description = "\\界🧪".repeat(125);
        assertEquals(2000, text.length());
        assertEquals(500, description.length());
        var cfg = Map.of("app", LONG_APP, "env", LONG_ENV, "key", "🧪".repeat(100),
                "value", text, "description", description, "operator", "文".repeat(100), "reason", "理".repeat(500));
        var first = numbered(post(Domain.CONFIG.path, cfg, "limit-config", "maximum-config"), "limit-config", false, 1);
        assertEquals(text, first.path("value").textValue());
        assertEquals(first, numbered(post(Domain.CONFIG.path, cfg, "limit-config-replay", "maximum-config"),
                "limit-config-replay", true, 1));

        var listEntry = "\"\t界🧪".repeat(6) + "xx";
        var lists = java.util.Collections.nCopies(20, listEntry);
        var flag = Map.of("app", LONG_APP, "env", LONG_ENV, "name", "🧪".repeat(100),
                "enabled", true, "rolloutPercentage", 100, "allowlist", lists);
        var flags = numbered(post(Domain.FEATURE.path, flag, "limit-feature", "maximum-flag"), "limit-feature", false, 1);
        assertEquals(lists, json.convertValue(flags.path("allowlist"), List.class));
        assertEquals(flags, numbered(post(Domain.FEATURE.path, flag, "limit-flag-replay", "maximum-flag"),
                "limit-flag-replay", true, 1));

        if (mysql()) {
            var sizes = db.queryForList("""
                    SELECT OCTET_LENGTH(result_json) snapshot_bytes,
                           OCTET_LENGTH(app) + OCTET_LENGTH(env) + OCTET_LENGTH(request_key)
                              + OCTET_LENGTH(operation) + OCTET_LENGTH(intent_digest) + OCTET_LENGTH(result_json) AS variable_bytes
                    FROM write_receipt ORDER BY id
                    """);
            assertEquals(3, sizes.size());
            assertTrue(((Number) sizes.get(1).get("snapshot_bytes")).intValue() > 10_000);
            System.out.println("Plan07 receipt value bytes (sample config, max config, max flag): " + sizes);
        }
    }

    @Test
    void openApiDescribesOptionalHeaderAndTypedSuccessOnAllRoutes() throws Exception {
        var paths = http.get("/v3/api-docs").body().path("paths");
        for (String path : List.of("/api/configs", "/api/configs/rollback", "/api/features", "/api/features/rollback")) {
            JsonNode operation = paths.path(path).path("post");
            List<JsonNode> headers = new ArrayList<>();
            operation.path("parameters").forEach(p -> {
                if (WriteHttpAdapter.KEY.equals(p.path("name").asText())) {
                    headers.add(p);
                }
            });
            assertEquals(1, headers.size(), path);
            assertFalse(headers.get(0).path("required").asBoolean());
            assertEquals(WriteHttpAdapter.KEY_PATTERN, headers.get(0).path("schema").path("pattern").asText());
            JsonNode success = operation.path("responses").path("200");
            assertTrue(success.path("headers").has(WriteHttpAdapter.REPLAYED), path);
            assertTrue(success.path("content").toString().contains(
                    path.startsWith("/api/configs") ? "ApiResponseConfigItemDto" : "ApiResponseFeatureFlagDto"), path);
        }
    }

    @ParameterizedTest
    @EnumSource(ClaimRace.class)
    void sameNumberRaceUsesTheDatabaseAndNewReadAfterLosingClaim(ClaimRace race) throws Exception {
        var leftClaimed = new CountDownLatch(1);
        var rightAttempted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);
        boolean requireDuplicate = race == ClaimRace.SAME_INTENT || race == ClaimRace.OTHER_INTENT;
        var connections = new ConcurrentHashMap<String, Long>();
        var observed = new AtomicReference<String>();
        var waited = new AtomicLong();
        doAnswer(invocation -> {
            String trace = MDC.get("traceId");
            if ("left".equals(trace)) {
                connections.put(trace, connectionId());
                invocation.callRealMethod();
                leftClaimed.countDown();
                awaitSignal(release);
                if (race == ClaimRace.ABORTED_FIRST) {
                    throw new IllegalStateException("fixture stops the first transaction after the claim");
                }
            } else {
                connections.put(trace, connectionId());
                rightAttempted.countDown();
                if (requireDuplicate) {
                    // The second transaction has already read absence. Let the first commit before the
                    // second INSERT to exercise a duplicate and readback from a new transaction.
                    awaitSignal(winnerCommitted);
                }
                long start = System.nanoTime();
                try {
                    invocation.callRealMethod();
                } catch (WriteReceiptStore.ClaimConflict conflict) {
                    observed.set(conflict.getMessage());
                    throw conflict;
                } finally {
                    waited.set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                }
            }
            return null;
        }).when(store).claim(any(), any());

        String number = "contended";
        var leftBody = Domain.CONFIG.payload("item", 1);
        var rightBody = race == ClaimRace.OTHER_INTENT ? Domain.CONFIG.payload("item", 9) : leftBody;
        try (var watch = http.watch(APP, ENV, 0, 14, "contention-watch")) {
            var left = http.postAsync(Domain.CONFIG.path, KEY, "left", leftBody, number);
            Call leading;
            Call following;
            try {
                awaitSignal(leftClaimed);
                assertEquals(0, count("write_receipt"));
                var right = http.postAsync(Domain.CONFIG.path, KEY, "right", rightBody, number);
                awaitSignal(rightAttempted);
                assertNotEquals(connections.get("left"), connections.get("right"));
                if (race == ClaimRace.HELD_CLAIM) {
                    // Keep the real insertion blocked until its bounded JDBC/engine wait finishes.
                    following = await(right);
                    expectReuse(following);
                    assertTrue(following.body().path("message").asText().contains("竞争"));
                    assertFalse(left.isDone());
                    assertEquals(0, count("write_receipt"));
                    release.countDown();
                    leading = await(left);
                } else {
                    release.countDown();
                    leading = await(left);
                    winnerCommitted.countDown();
                    following = await(right);
                }
            } finally {
                release.countDown();
                winnerCommitted.countDown();
            }
            reset(store);

            if (race == ClaimRace.ABORTED_FIRST) {
                leading.expect(500, 5000);
                if (following.status() == 409) {
                    expectReuse(following);
                    following = post(Domain.CONFIG.path, rightBody, "after-abort", number);
                }
                numbered(following, following.trace(), false, 1);
            } else {
                var original = numbered(leading, "left", false, 1);
                if (race == ClaimRace.OTHER_INTENT) {
                    expectReuse(following);
                    assertTrue(following.body().path("message").asText().contains("不同的写入"));
                } else if (race == ClaimRace.HELD_CLAIM) {
                    assertEquals(original, numbered(post(Domain.CONFIG.path, rightBody, "after-lock", number),
                            "after-lock", true, 1));
                } else {
                    assertEquals(original, numbered(following, "right", true, 1));
                }
            }
            watch.expect(true, 1);
            assertEquals(1, count("write_receipt"));
            assertEquals(1, count("config_item_history"));
            assertEquals(1, count("config_item"));
            assertEquals(1, revision());
            assertEquals(0, notifier.pendingWaiterCount());
            verify(notifier, times(1)).notifyChanged(APP, ENV, 1);
            if (requireDuplicate) {
                assertEquals("receipt unique constraint", observed.get());
            } else if (race == ClaimRace.HELD_CLAIM) {
                assertEquals("receipt claim lock or statement timeout", observed.get());
            }
            System.out.printf("Plan07 %s %s independent connections=%s HTTP=%s/%s claim=%s waitedMs=%d%n",
                    mysql() ? "MySQL" : "H2", race, connections, leading.status(), following.status(), observed.get(),
                    waited.get());
        }
    }

    @Test
    void differentNumbersAndExactScopesHaveIndependentClaims() throws Exception {
        var seed = Domain.FEATURE.payload("item", 1);
        post(Domain.FEATURE.path, seed, "seed").expect(200, 0);
        var claimed = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var connections = new ConcurrentHashMap<String, Long>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertEquals(1L, db.queryForObject("SELECT version FROM feature_flag WHERE app=? AND env=? AND name='item'",
                    Long.class, APP, ENV));
            connections.put(MDC.get("traceId"), connectionId());
            claimed.countDown();
            awaitSignal(release);
            return null;
        }).when(store).claim(any(), any());

        var left = with(Domain.FEATURE.payload("item", 20), "expectedVersion", 1);
        var right = with(Domain.FEATURE.payload("item", 30), "expectedVersion", 1);
        var first = http.postAsync(Domain.FEATURE.path, KEY, "first-number", left, "one");
        var second = http.postAsync(Domain.FEATURE.path, KEY, "second-number", right, "two");
        try {
            awaitSignal(claimed);
            assertEquals(2, connections.values().stream().distinct().count());
        } finally {
            release.countDown();
        }
        var a = await(first);
        var b = await(second);
        reset(store);
        assertEquals(List.of(200, 409), List.of(a.status(), b.status()).stream().sorted().toList());
        Call winner = a.status() == 200 ? a : b;
        Call loser = a.status() == 409 ? a : b;
        numbered(winner, winner.trace(), false, 2);
        loser.expect(409, 4091);
        assertNull(loser.header(WriteHttpAdapter.REPLAYED));
        String winningNumber = a == winner ? "one" : "two";
        assertEquals(1, count("write_receipt"));
        assertEquals(winningNumber, new String(db.queryForObject("SELECT request_key FROM write_receipt", byte[].class),
                StandardCharsets.US_ASCII));
        assertEquals(2, count("feature_flag_history"));
        assertEquals(0, revision());

        // The same number can bind concurrently in two exact, previously equivalent scopes.
        var leftBody = Domain.FEATURE.payload("scoped", 40);
        var rightBody = with(Domain.FEATURE.payload("scoped", 50), "app", ALIAS);
        var scopeGate = new CountDownLatch(2);
        var scopeRelease = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            scopeGate.countDown();
            awaitSignal(scopeRelease);
            return null;
        }).when(store).claim(any(), any());
        var leftScope = http.postAsync(Domain.FEATURE.path, KEY, "exact-one", leftBody, "same-scope-number");
        var rightScope = http.postAsync(Domain.FEATURE.path, ALIAS_KEY, "exact-two", rightBody, "same-scope-number");
        try {
            awaitSignal(scopeGate);
        } finally {
            scopeRelease.countDown();
        }
        var scopedOne = numbered(await(leftScope), "exact-one", false, 1);
        var scopedTwo = numbered(await(rightScope), "exact-two", false, 1);
        assertEquals(APP, scopedOne.path("app").asText());
        assertEquals(ALIAS, scopedTwo.path("app").asText());
        assertEquals(3, count("write_receipt"));
        assertEquals(4, count("feature_flag_history"));
        assertEquals(0, revision());
        verify(notifier, never()).notifyChanged(anyString(), anyString(), anyLong());
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void businessEncodeAndCommitFailuresRollbackEveryTable(Domain domain) throws Exception {
        var seed = domain.payload("item", 1);
        post(domain.path, seed, "seed").expect(200, 0);
        clearInvocations(notifier);
        var original = snapshot();
        var stale = with(domain.payload("item", 2), "expectedVersion", 99);
        post(domain.path, stale, "stale", "attempt").expect(409, 4091);
        post(domain.rollbackPath(), domain.rollback("item", 999), "no-target", "attempt").expect(404, 4041);
        assertEquals(original, snapshot());

        var update = with(domain.payload("item", 2), "expectedVersion", 1);
        long before = domain.config() ? 1 : 0;
        try (var watch = http.watch(APP, ENV, before, 14, "transaction-watch")) {
            doAnswer(invocation -> {
                entityManager.flush();
                assertPendingEffects(domain, false);
                throw new IllegalStateException("fixture receipt encoder fault");
            }).when(codec).encode(any());
            post(domain.path, update, "failed-encoding", "attempt").expect(500, 5000);
            reset(codec);
            assertEquals(original, snapshot());
            assertFalse(watch.response().isDone());

            var flushed = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var insideConnection = new AtomicReference<Long>();
            doAnswer(invocation -> {
                invocation.callRealMethod();
                entityManager.flush();
                insideConnection.set(connectionId());
                assertPendingEffects(domain, true);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("fixture aborts after receipt and business flush");
                    }
                });
                flushed.countDown();
                awaitSignal(release);
                return null;
            }).when(store).complete(any(), any(), anyString());
            var failing = http.postAsync(domain.path, KEY, "before-commit", update, "attempt");
            try {
                awaitSignal(flushed);
                assertNotEquals(insideConnection.get(), connectionId());
                assertEquals(original, snapshot());
                assertFalse(watch.response().isDone());
                verify(notifier, never()).notifyChanged(anyString(), anyString(), anyLong());
            } finally {
                release.countDown();
            }
            await(failing).expect(500, 5000);
            reset(store);
            assertEquals(original, snapshot());
            assertFalse(watch.response().isDone());
            verify(notifier, never()).notifyChanged(anyString(), anyString(), anyLong());

            var success = numbered(post(domain.path, update, "after-failure", "attempt"), "after-failure", false, 2);
            assertEquals(success, numbered(post(domain.path, update, "after-failure-retry", "attempt"),
                    "after-failure-retry", true, 2));
            assertEquals(1, count("write_receipt"));
            assertEquals(2, count(domain.history));
            assertEquals(domain.config() ? 2 : 0, revision());
            verify(notifier, times(domain.config() ? 1 : 0)).notifyChanged(anyString(), anyString(), anyLong());
            if (!domain.config()) {
                assertFalse(watch.response().isDone());
                post(Domain.CONFIG.path, Domain.CONFIG.payload("release-watch", 1), "release-watch").expect(200, 0);
            }
            watch.expect(true, domain.config() ? 2 : 1);
        }
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void corruptPendingOrUnsupportedReceiptFailsClosed() throws Exception {
        var update = Domain.CONFIG.payload("item", 1);
        post(Domain.CONFIG.path, update, "seed", "corrupt-check").expect(200, 0);
        String valid = db.queryForObject("SELECT result_json FROM write_receipt", String.class);
        for (String invalid : List.of("null", "{\"version\":1}", valid + "{}", valid.replace("\"item\"", "\"bad\""))) {
            db.update("UPDATE write_receipt SET result_json=?", invalid);
            var corrupt = snapshot();
            post(Domain.CONFIG.path, update, "invalid-snapshot", "corrupt-check").expect(500, 5000);
            assertEquals(corrupt, snapshot());
        }
        db.update("UPDATE write_receipt SET result_json=NULL");
        post(Domain.CONFIG.path, update, "bad-completion", "corrupt-check").expect(500, 5000);
        db.update("UPDATE write_receipt SET result_json=?, snapshot_version=41", valid);
        var unsupported = snapshot();
        post(Domain.CONFIG.path, update, "unsupported", "corrupt-check").expect(500, 5000);
        assertEquals(unsupported, snapshot());
        db.update("UPDATE write_receipt SET snapshot_version=1, operation='unknown'");
        var unknown = snapshot();
        post(Domain.CONFIG.path, update, "unknown-operation", "corrupt-check").expect(500, 5000);
        assertEquals(unknown, snapshot());
        assertEquals(1, revision());
        verify(notifier, times(1)).notifyChanged(anyString(), anyString(), anyLong());
    }

    private void assertPendingEffects(Domain domain, boolean complete) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(2L, db.queryForObject("SELECT version FROM " + domain.current, Long.class));
        assertEquals(2, count(domain.history));
        assertEquals(domain.config() ? 2 : 0, revision());
        assertEquals(1, count("write_receipt"));
        assertEquals(complete ? 1 : 0, db.queryForObject(
                "SELECT COUNT(*) FROM write_receipt WHERE result_json IS NOT NULL", Integer.class));
    }

    private long connectionId() {
        return db.queryForObject(mysql() ? "SELECT CONNECTION_ID()" : "SELECT SESSION_ID()", Long.class);
    }

    private static void awaitSignal(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(9, SECONDS), "Timed out at a transaction checkpoint");
    }

    enum ClaimRace {
        SAME_INTENT, OTHER_INTENT, ABORTED_FIRST, HELD_CLAIM
    }

    private Call post(String path, Map<String, ?> fields, String trace, String... numbers) throws Exception {
        return http.post(path, KEY, trace, fields, numbers);
    }

    private static JsonNode numbered(Call call, String trace, boolean replayed, long version) {
        JsonNode data = call.expect(200, 0);
        assertEquals(trace, call.trace());
        assertEquals(Boolean.toString(replayed), call.header(WriteHttpAdapter.REPLAYED));
        assertEquals(version, data.path("version").longValue());
        return data;
    }

    private static void expectReuse(Call call) {
        assertTrue(call.expect(409, 4091).isNull());
        assertTrue(call.body().path("message").asText().contains("编号"));
        assertNull(call.header(WriteHttpAdapter.REPLAYED));
    }

    private List<List<Map<String, Object>>> snapshot() {
        return TABLES.stream().map(table -> {
            var rows = db.queryForList("SELECT * FROM " + table + " ORDER BY id");
            if (table.equals("write_receipt")) {
                rows.forEach(row -> row.put("request_key", HexFormat.of().formatHex((byte[]) row.get("request_key"))));
            }
            return rows;
        }).toList();
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long revision() {
        var found = db.queryForList("SELECT revision FROM config_namespace_revision WHERE app=? AND env=?",
                Long.class, APP, ENV);
        return found.isEmpty() ? 0 : found.get(0);
    }

    private boolean mysql() {
        return environment.matchesProfiles("mysql");
    }

    private static Map<String, Object> with(Map<String, ?> input, String field, Object value) {
        var copy = new LinkedHashMap<String, Object>(input);
        copy.put(field, value);
        return copy;
    }

    enum Domain {
        CONFIG("/api/configs", "config_item", "config_item_history", "key", "value"),
        FEATURE("/api/features", "feature_flag", "feature_flag_history", "name", "rolloutPercentage");

        final String path;
        final String current;
        final String history;
        final String nameField;
        final String effect;

        Domain(String path, String current, String history, String nameField, String effect) {
            this.path = path;
            this.current = current;
            this.history = history;
            this.nameField = nameField;
            this.effect = effect;
        }

        boolean config() {
            return this == CONFIG;
        }

        String rollbackPath() {
            return path + "/rollback";
        }

        Map<String, Object> payload(String name, int versionContent) {
            return config() ? Map.of("app", APP, "env", ENV, nameField, name, effect, "value-" + versionContent)
                    : Map.of("app", APP, "env", ENV, nameField, name, "enabled", true, effect, versionContent);
        }

        Map<String, Object> rollback(String name, long version) {
            return Map.of("app", APP, "env", ENV, nameField, name, "targetVersion", version, "reason", "fixture rollback");
        }
    }
}
