package com.example.configcenter;

import com.example.configcenter.config.TraceIdFilter;
import com.example.configcenter.domain.entity.ConfigItem;
import com.example.configcenter.domain.entity.FeatureFlag;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import com.example.configcenter.service.ConfigNamespaceRevisionService;
import com.example.configcenter.service.ConfigWatchNotifier;
import com.example.configcenter.service.NamespaceRevisionLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import static com.example.configcenter.LoopbackWriteFixture.await;
import static com.example.configcenter.LoopbackWriteFixture.encode;
import static com.example.configcenter.LoopbackWriteFixture.scope;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "rate-limit.enabled=false",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=6",
        "security.api-keys[0].key=plan05-write-key", "security.api-keys[0].app=plan05-write", "security.api-keys[0].env=dev",
        "security.api-keys[1].key=plan05-alias-key", "security.api-keys[1].app=Plan05-write", "security.api-keys[1].env=dev"
})
@ActiveProfiles("mysql")
@Import({LoopbackWriteFixture.WatchRequests.class, MysqlWriteConsistencyIT.RepositoryCheckpoints.class})
@DirtiesContext
@Timeout(45)
class MysqlWriteConsistencyIT {

    private static final String APP = "plan05-write";
    private static final String ALIAS = "Plan05-write";
    private static final String ENV = "dev";
    private static final String KEY = "plan05-write-key";
    private static final String OBJECT = "subject";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";
    private static final List<String> TABLES = List.of("config_item_history", "feature_flag_history",
            "config_item", "feature_flag", "config_namespace_revision");

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired ObjectMapper mapper;
    @Autowired LoopbackWriteFixture.WatchRequests watchRequests;
    @Autowired RepositoryCheckpoints checkpoints;
    @MockitoSpyBean ConfigNamespaceRevisionService revisions;
    @MockitoSpyBean NamespaceRevisionLock namespaceLock;
    @MockitoSpyBean ConfigWatchNotifier notifier;
    @LocalServerPort int port;
    private LoopbackWriteFixture http;

    @BeforeEach
    void prepareOwnNamespace() {
        assertEquals("config_center_it", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertNotEquals("root", jdbc.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class));
        assertEquals(0, notifier.pendingWaiterCount());
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table + " WHERE app IN (?,?) AND env=?", APP, ALIAS, ENV);
        }
        checkpoints.hook = null;
        http = new LoopbackWriteFixture(mapper, port, watchRequests);
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void twoExpectedVersionUpdatesCommitOnlyOne(Domain domain) throws Exception {
        write(domain, OBJECT, 10, 999L, "seed").expect(200, 0);
        JsonNode seedHistory = history(domain, OBJECT).get(0);
        clearInvocations(notifier);
        try (var watch = domain == Domain.CONFIG ? http.watch(APP, ENV, 1, 12, "same-version-watch") : null;
             var gate = holdAfterReads(domain)) {
            List<LoopbackWriteFixture.Call> results = race(gate, domain + " loaded v1", 1,
                    start(domain, OBJECT, 21, 1L, LEFT),
                    start(domain, OBJECT, 33, 1L, RIGHT));
            var winner = assertOneWinner(results, 2);
            assertCurrentAndHistory(domain, OBJECT, winner, "UPSERT", winner.trace());
            assertVersions(domain, OBJECT, 2, 1);
            assertEquals(seedHistory, history(domain, OBJECT).get(1));
            if (watch != null) {
                watch.expect(true, 2);
                verify(notifier).notifyChanged(APP, ENV, 2);
                verify(notifier, times(1)).notifyChanged(eq(APP), eq(ENV), anyLong());
            } else {
                verify(notifier, never()).notifyChanged(eq(APP), eq(ENV), anyLong());
            }
            assertRevision(domain == Domain.CONFIG ? 2 : 0);
            assertEquals(0, notifier.pendingWaiterCount());

            // The loser can re-read the winner, use the new expectedVersion, and make a new update.
            String loser = winner.trace().equals(LEFT) ? RIGHT : LEFT;
            long expected = current(domain, OBJECT).path("version").longValue();
            var retried = write(domain, OBJECT, loser.equals(LEFT) ? 21 : 33, expected, "resubmitted");
            retried.expect(200, 0);
            assertCurrentAndHistory(domain, OBJECT, retried, "UPSERT", "resubmitted");
            assertVersions(domain, OBJECT, 3, 2, 1);
            assertRevision(domain == Domain.CONFIG ? 3 : 0);
        }
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void twoMissingRowUpsertsProduceOnlyOneV1(Domain domain) throws Exception {
        try (var gate = holdAfterReads(domain)) {
            List<LoopbackWriteFixture.Call> results = race(gate, domain + " both saw no row", 0,
                    start(domain, OBJECT, 25, 999L, LEFT), start(domain, OBJECT, 35, null, RIGHT));
            var winner = assertOneWinner(results, 1);
            assertCurrentAndHistory(domain, OBJECT, winner, "UPSERT", winner.trace());
            assertVersions(domain, OBJECT, 1);
        }
        assertRevision(domain == Domain.CONFIG ? 1 : 0);
        var unconditional = write(domain, OBJECT, 40, null, "unconditional");
        unconditional.expect(200, 0);
        assertCurrentAndHistory(domain, OBJECT, unconditional, "UPSERT", "unconditional");
        assertVersions(domain, OBJECT, 2, 1);
        assertRevision(domain == Domain.CONFIG ? 2 : 0);
    }

    @Test
    void differentKeysInOneNamespaceBothCommitOnFirstWriteAndUpdate() throws Exception {
        try (var gate = holdBeforeNamespaceLock()) {
            var first = start(Domain.CONFIG, "one", 11, null, LEFT);
            var second = start(Domain.CONFIG, "two", 22, null, RIGHT);
            gate.bothArrived("different keys before first revision");
            assertRevision(0);
            assertEquals(0, items(Domain.CONFIG).size());
            gate.release();
            assertCurrentAndHistory(Domain.CONFIG, "one", await(first), "UPSERT", LEFT);
            assertCurrentAndHistory(Domain.CONFIG, "two", await(second), "UPSERT", RIGHT);
            assertVersions(Domain.CONFIG, "one", 1);
            assertVersions(Domain.CONFIG, "two", 1);
        }
        assertRevision(2);

        try (var gate = holdBeforeNamespaceLock()) {
            var first = start(Domain.CONFIG, "one", 12, 1L, LEFT);
            var second = start(Domain.CONFIG, "two", 23, 1L, RIGHT);
            gate.bothArrived("different keys before existing revision");
            assertRevision(2);
            gate.release();
            assertCurrentAndHistory(Domain.CONFIG, "one", await(first), "UPSERT", LEFT);
            assertCurrentAndHistory(Domain.CONFIG, "two", await(second), "UPSERT", RIGHT);
            assertVersions(Domain.CONFIG, "one", 2, 1);
            assertVersions(Domain.CONFIG, "two", 2, 1);
        }
        assertRevision(4);
        for (long revision = 1; revision <= 4; revision++) {
            verify(notifier).notifyChanged(APP, ENV, revision);
        }
    }

    @ParameterizedTest
    @EnumSource(Domain.class)
    void rollbackAndUpsertReadTheSamePreviousVersion(Domain domain) throws Exception {
        var old = write(domain, OBJECT, 10, null, "seed").expect(200, 0);
        write(domain, OBJECT, 20, 1L, "changed").expect(200, 0);
        JsonNode initialHistory = history(domain, OBJECT);
        try (var gate = holdAfterReads(domain)) {
            var upsert = start(domain, OBJECT, 30, 2L, LEFT);
            var rollback = http.postAsync(domain.path + "/rollback", KEY, RIGHT, Map.of(
                    "app", APP, "env", ENV, domain.field, OBJECT, "targetVersion", 1,
                    "operator", "fixture-" + RIGHT, "reason", RIGHT));
            var winner = assertOneWinner(race(gate, domain + " upsert/rollback loaded v2", 2, upsert, rollback), 3);
            boolean restored = winner.trace().equals(RIGHT);
            assertCurrentAndHistory(domain, OBJECT, winner, restored ? "ROLLBACK" : "UPSERT", winner.trace());
            for (String field : domain.payloadFields) {
                assertEquals(restored ? old.get(field) : mapper.valueToTree(domain.payload(30).get(field)),
                        current(domain, OBJECT).get(field), field);
            }
            assertVersions(domain, OBJECT, 3, 2, 1);
            JsonNode finalHistory = history(domain, OBJECT);
            assertEquals(initialHistory.get(0), finalHistory.get(1));
            assertEquals(initialHistory.get(1), finalHistory.get(2));
            assertRevision(domain == Domain.CONFIG ? 3 : 0);
        }
        write(domain, OBJECT, 40, null, "after-race").expect(200, 0);
        assertVersions(domain, OBJECT, 4, 3, 2, 1);
        assertRevision(domain == Domain.CONFIG ? 4 : 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void flushedConfigIsInvisibleBeforeCommitAndAbortLeavesNoEffects(boolean abort) throws Exception {
        write(Domain.CONFIG, OBJECT, 10, null, "seed").expect(200, 0);
        clearInvocations(notifier);
        Map<String, List<Map<String, Object>>> before = snapshot();
        try (var gate = new Gate(Set.of(LEFT));
             var watcher = http.watch(APP, ENV, 1, 12, "flush-watch")) {
            ConfigNamespaceRevisionService target = AopTestUtils.getUltimateTargetObject(revisions);
            doAnswer(invocation -> {
                long revision = (long) invocation.callRealMethod();
                if (LEFT.equals(MDC.get(TraceIdFilter.MDC_KEY))) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            entities.flush();
                            assertEquals(2L, jdbc.queryForObject(
                                    "SELECT version FROM config_item WHERE app=? AND env=? AND config_key=?",
                                    Long.class, APP, ENV, OBJECT));
                            assertEquals(2, historyRows());
                            assertRevision(revision);
                            gate.arrive(LEFT, revision);
                            if (abort) {
                                throw new DataIntegrityViolationException("test abort after flushing all config writes");
                            }
                        }
                    });
                }
                return revision;
            }).when(target).advance(APP, ENV);

            var first = start(Domain.CONFIG, OBJECT, 20, 1L, LEFT);
            gate.bothArrived("after flush; abort=" + abort);
            assertNotEquals(gate.observations.get(LEFT).connection(), connectionId());
            assertEquals(before, snapshot());
            assertEquals("10", current(Domain.CONFIG, OBJECT).path("value").asText());
            assertFalse(watcher.response().isDone());
            assertEquals(1, notifier.pendingWaiterCount());
            verify(notifier, never()).notifyChanged(eq(APP), eq(ENV), anyLong());

            gate.release();
            var result = await(first);
            if (abort) {
                result.expect(409, 4091);
                assertEquals(before, snapshot());
                verify(notifier, never()).notifyChanged(eq(APP), eq(ENV), anyLong());
                assertFalse(watcher.response().isDone());
                result = write(Domain.CONFIG, OBJECT, 30, 1L, "after-abort");
            }
            assertCurrentAndHistory(Domain.CONFIG, OBJECT, result, "UPSERT", result.trace());
            assertVersions(Domain.CONFIG, OBJECT, 2, 1);
            assertRevision(2);
            watcher.expect(true, 2);
            verify(notifier).notifyChanged(APP, ENV, 2);
            verify(notifier, times(1)).notifyChanged(eq(APP), eq(ENV), anyLong());
            assertEquals(0, notifier.pendingWaiterCount());
            assertEquals(0, notifier.pendingNamespaceCount());
        }
    }

    @Test
    void formerlyCollatedScopesKeepIndependentFirstWrites() throws Exception {
        var left = Map.of("app", APP, "env", ENV, "key", "subject", "value", "lower");
        var right = Map.of("app", ALIAS, "env", ENV, "key", "subject", "value", "upper");
        var bodies = List.of(left, right);
        var keys = List.of(KEY, "plan05-alias-key");
        try (var gate = new Gate(Set.of(LEFT, RIGHT))) {
            checkpoints.hook = new ReadHook("findForUpdate", gate, ignored -> 1L);
            var results = race(gate, "exact namespaces both saw no revision row", 0,
                    http.postAsync(Domain.CONFIG.path, keys.get(0), LEFT, left),
                    http.postAsync(Domain.CONFIG.path, keys.get(1), RIGHT, right));
            long successes = results.stream().filter(result -> result.status() == 200).count();
            assertTrue(successes >= 1);
            for (int i = 0; i < results.size(); i++) {
                var result = results.get(i);
                var body = bodies.get(i);
                String app = body.get("app");
                if (result.status() == 409) {
                    result.expect(409, 4091);
                    assertExactFreshState(app, body.get("value"), false);
                    verify(notifier, never()).notifyChanged(eq(app), eq(ENV), anyLong());
                } else {
                    assertEquals(1, result.expect(200, 0).path("version").asInt());
                    assertExactFreshState(app, body.get("value"), true);
                }
            }
            for (int i = 0; i < results.size(); i++) {
                var body = bodies.get(i);
                if (results.get(i).status() == 409) {
                    http.post(Domain.CONFIG.path, keys.get(i), "exact-follow-up", body).expect(200, 0);
                }
                assertExactFreshState(body.get("app"), body.get("value"), true);
                verify(notifier).notifyChanged(body.get("app"), ENV, 1);
                verify(notifier, times(1)).notifyChanged(eq(body.get("app")), eq(ENV), anyLong());
            }
            System.out.printf("Plan06 distinct exact namespaces initial successes=%d; both finish v1/revision 1%n", successes);
        } finally {
            checkpoints.hook = null;
        }
    }

    private void assertExactFreshState(String app, String value, boolean written) throws Exception {
        var state = http.get(Domain.CONFIG.path + scope(app, ENV)).expect(200, 0);
        var journal = http.get(Domain.CONFIG.path + "/history" + scope(app, ENV) + "&key=subject").expect(200, 0);
        var revisions = jdbc.queryForList("SELECT revision FROM config_namespace_revision WHERE app=? AND env=?",
                Long.class, app, ENV);
        assertEquals(written ? List.of(1L) : List.of(), revisions);
        assertEquals(written ? 1 : 0, state.size());
        assertEquals(written ? 1 : 0, journal.size());
        if (written) {
            assertEquals(app, state.get(0).path("app").asText());
            assertEquals(app, journal.get(0).path("app").asText());
            assertEquals(value, state.get(0).path("value").asText());
            assertEquals(value, journal.get(0).path("value").asText());
            assertEquals(1, journal.get(0).path("version").asInt());
        }
    }

    private Gate holdAfterReads(Domain domain) {
        Gate gate = new Gate(Set.of(LEFT, RIGHT));
        checkpoints.hook = domain == Domain.CONFIG
                ? new ReadHook("findByAppAndEnvAndConfigKey", gate, row -> ((ConfigItem) row).getVersion())
                : new ReadHook("findByAppAndEnvAndName", gate, row -> ((FeatureFlag) row).getVersion());
        return gate;
    }

    private Gate holdBeforeNamespaceLock() {
        Gate gate = new Gate(Set.of(LEFT, RIGHT));
        doAnswer(invocation -> {
            String trace = MDC.get(TraceIdFilter.MDC_KEY);
            if (trace != null && gate.expected.contains(trace)) {
                gate.arrive(trace, -1);
            }
            return invocation.callRealMethod();
        }).when(namespaceLock).lockUntilTransactionCompletion(APP, ENV);
        return gate;
    }

    private List<LoopbackWriteFixture.Call> race(Gate gate, String label, long version,
                                                CompletableFuture<LoopbackWriteFixture.Call> left,
                                                CompletableFuture<LoopbackWriteFixture.Call> right) throws Exception {
        gate.bothArrived(label);
        assertTrue(gate.observations.values().stream().allMatch(read -> read.version == version));
        gate.release();
        var results = List.of(await(left), await(right));
        System.out.printf("Plan05 %s HTTP statuses=%s%n", label, results.stream().map(LoopbackWriteFixture.Call::status).toList());
        return results;
    }

    private LoopbackWriteFixture.Call assertOneWinner(List<LoopbackWriteFixture.Call> results, long version) {
        assertEquals(List.of(200, 409), results.stream().map(LoopbackWriteFixture.Call::status).sorted().toList(),
                results::toString);
        var winner = results.stream().filter(result -> result.status() == 200).findFirst().orElseThrow();
        results.stream().filter(result -> result.status() != 200).forEach(result -> result.expect(409, 4091));
        assertEquals(version, winner.expect(200, 0).path("version").longValue());
        return winner;
    }

    private CompletableFuture<LoopbackWriteFixture.Call> start(Domain domain, String object, int value,
                                                                Long expected, String trace) throws Exception {
        Map<String, Object> fields = new HashMap<>(domain.payload(value));
        fields.putAll(Map.of("app", APP, "env", ENV, domain.field, object, "operator", "fixture-" + trace, "reason", trace));
        if (expected != null) {
            fields.put("expectedVersion", expected);
        }
        return http.postAsync(domain.path, KEY, trace, fields);
    }

    private LoopbackWriteFixture.Call write(Domain domain, String object, int value, Long expected, String trace)
            throws Exception {
        return await(start(domain, object, value, expected, trace));
    }

    private JsonNode items(Domain domain) throws Exception {
        return http.get(domain.path + scope(APP, ENV)).expect(200, 0);
    }

    private JsonNode current(Domain domain, String object) throws Exception {
        for (JsonNode row : items(domain)) {
            if (row.path(domain.field).asText().equals(object)) {
                return row;
            }
        }
        throw new AssertionError("Missing row " + object);
    }

    private JsonNode history(Domain domain, String object) throws Exception {
        return http.get(domain.path + "/history" + scope(APP, ENV) + "&" + domain.field + "=" + encode(object))
                .expect(200, 0);
    }

    private void assertCurrentAndHistory(Domain domain, String object, LoopbackWriteFixture.Call write,
                                         String action, String reason) throws Exception {
        JsonNode success = write.expect(200, 0);
        JsonNode stored = current(domain, object);
        JsonNode latest = history(domain, object).get(0);
        for (String field : List.of("app", "env", domain.field, "version")) {
            assertEquals(success.get(field), stored.get(field), field);
            assertEquals(success.get(field), latest.get(field), field);
        }
        for (String field : domain.payloadFields) {
            assertEquals(success.get(field), stored.get(field), field);
            assertEquals(success.get(field), latest.get(field), field);
        }
        assertEquals(action, latest.path("action").asText());
        assertEquals("fixture-" + reason, latest.path("operator").asText());
        assertEquals(action.equals("ROLLBACK") ? "rollback-to=1, " + reason : reason, latest.path("reason").asText());
    }

    private void assertVersions(Domain domain, String object, long... expected) throws Exception {
        JsonNode rows = history(domain, object);
        assertEquals(expected.length, rows.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], rows.get(i).path("version").longValue());
        }
    }

    private void assertRevision(long expected) {
        var matches = jdbc.queryForList("SELECT revision FROM config_namespace_revision WHERE app=? AND env=?",
                Long.class, APP, ENV);
        assertEquals(expected == 0 ? List.of() : List.of(expected), matches);
    }

    private int historyRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM config_item_history WHERE app=? AND env=?",
                Integer.class, APP, ENV);
    }

    private long connectionId() {
        return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new HashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE app=? AND env=? ORDER BY id", APP, ENV));
        }
        return rows;
    }

    enum Domain {
        CONFIG("/api/configs", "key", List.of("value", "description")),
        FEATURE("/api/features", "name", List.of("enabled", "rolloutPercentage", "allowlist"));

        final String path;
        final String field;
        final List<String> payloadFields;

        Domain(String path, String field, List<String> payloadFields) {
            this.path = path;
            this.field = field;
            this.payloadFields = payloadFields;
        }

        Map<String, ?> payload(int value) {
            return this == CONFIG ? Map.of("value", Integer.toString(value), "description", "candidate-" + value)
                    : Map.of("enabled", true, "rolloutPercentage", value, "allowlist", List.of("user-" + value));
        }
    }

    private record Observation(long connection, String isolation, long version) {}

    private final class Gate implements AutoCloseable {
        private final Set<String> expected;
        private final CountDownLatch reached;
        private final CountDownLatch released = new CountDownLatch(1);
        private final Map<String, Observation> observations = new ConcurrentHashMap<>();

        Gate(Set<String> expected) {
            this.expected = expected;
            this.reached = new CountDownLatch(expected.size());
        }

        void arrive(String trace, long version) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            var observation = new Observation(connectionId(),
                    jdbc.queryForObject("SELECT @@transaction_isolation", String.class), version);
            if (observations.putIfAbsent(trace, observation) == null) {
                reached.countDown();
                requireSignal(released, "release request " + trace);
            }
        }

        void bothArrived(String label) {
            requireSignal(reached, label);
            assertEquals(expected, observations.keySet());
            assertEquals(expected.size(), observations.values().stream().map(Observation::connection).distinct().count());
            System.out.printf("Plan05 concurrent checkpoint '%s': %s%n", label, observations);
        }

        void release() {
            released.countDown();
        }

        @Override
        public void close() {
            release();
        }

        private void requireSignal(CountDownLatch signal, String what) {
            try {
                assertTrue(signal.await(8, SECONDS), () -> "Timed out at " + what + ": " + observations);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted at " + what, cancelled);
            }
        }
    }

    private record ReadHook(String method, Gate gate, Function<Object, Long> version) {
        void afterRead(String name, Object result) {
            String trace = MDC.get(TraceIdFilter.MDC_KEY);
            if (method.equals(name) && trace != null && gate.expected.contains(trace)) {
                long readVersion = ((Optional<?>) result).map(version).orElse(0L);
                gate.arrive(trace, readVersion);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static class RepositoryCheckpoints {
        private volatile ReadHook hook;

        @Bean
        static BeanPostProcessor repositoryProbe(RepositoryCheckpoints probes) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof Advised repository && Set.of("configItemRepository", "featureFlagRepository",
                            "configNamespaceRevisionRepository").contains(name)) {
                        repository.addAdvice(0, (MethodInterceptor) invocation -> {
                            Object result = invocation.proceed();
                            var hook = probes.hook;
                            if (hook != null) {
                                hook.afterRead(invocation.getMethod().getName(), result);
                            }
                            return result;
                        });
                    }
                    return bean;
                }
            };
        }
    }
}
