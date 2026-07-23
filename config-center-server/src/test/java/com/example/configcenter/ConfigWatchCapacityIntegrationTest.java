package com.example.configcenter;

import com.example.configcenter.config.TraceIdFilter;
import com.example.configcenter.service.ConfigWatchNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rate-limit.enabled=false",
                "config-watch.max-pending-waiters=3",
                "config-watch.max-pending-per-namespace=2"
        }
)
@AutoConfigureMockMvc
class ConfigWatchCapacityIntegrationTest {

    @Autowired
    private ConfigWatchNotifier notifier;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void withinCapacity_registersWaitersAndTracksNamespaces() {
        ConfigWatchNotifier.Registration first = register("within", "dev", "one");
        ConfigWatchNotifier.Registration second = register("within", "dev", "two");
        ConfigWatchNotifier.Registration third = register("within", "prod", "three");

        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertTrue(third.accepted());
        assertEquals(3, notifier.pendingWaiterCount());
        assertEquals(2, notifier.pendingNamespaceCount());

        notifier.notifyChanged("within", "dev", 1);
        notifier.notifyChanged("within", "prod", 1);
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void globalCapacity_returns429WithoutRegisteringRejectedWaiter() throws Exception {
        MvcResult first = startWatch("global-one", "dev", "global-one");
        MvcResult second = startWatch("global-one", "dev", "global-two");
        MvcResult third = startWatch("global-two", "dev", "global-three");

        assertEquals(3, notifier.pendingWaiterCount());
        assertRejectedWatch("global-three", "dev", "global-rejected");
        assertEquals(3, notifier.pendingWaiterCount());
        assertEquals(2, notifier.pendingNamespaceCount());

        completeWatch(first, "global-one", "dev", "global-one", 1);
        completeWatch(second, "global-one", "dev", "global-two", 1);
        completeWatch(third, "global-two", "dev", "global-three", 1);
        assertEquals(0, notifier.pendingWaiterCount());
    }

    @Test
    void namespaceCapacity_rejectsOnlyFullNamespace() throws Exception {
        MvcResult first = startWatch("namespace", "dev", "namespace-one");
        MvcResult second = startWatch("namespace", "dev", "namespace-two");

        assertRejectedWatch("namespace", "dev", "namespace-rejected");
        MvcResult otherNamespace = startWatch("namespace", "prod", "namespace-other");
        assertEquals(3, notifier.pendingWaiterCount());
        assertEquals(2, notifier.pendingNamespaceCount());

        completeWatch(first, "namespace", "dev", "namespace-one", 1);
        completeWatch(second, "namespace", "dev", "namespace-two", 1);
        completeWatch(otherNamespace, "namespace", "prod", "namespace-other", 1);
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void timeout_releasesCapacityAndDiscardsNamespace() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceIdFilter.HEADER, "capacity-timeout");

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/configs/watch?app=timeout&env=dev&sinceVersion=0&timeoutSeconds=1",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(false, response.getBody().path("data").path("changed").asBoolean());
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void duplicateNotification_doesNotDoubleDecrementCapacity() throws Exception {
        MvcResult watcher = startWatch("duplicate", "dev", "duplicate-watch");
        notifier.notifyChanged("duplicate", "dev", 1);
        notifier.notifyChanged("duplicate", "dev", 2);

        watcher.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(watcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestVersion").value(1));
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void concurrentRegistrations_neverExceedGlobalCapacity() throws Exception {
        int attempts = 12;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<Future<ConfigWatchNotifier.Registration>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent registrations did not start");
                    }
                    return register("concurrent-" + index, "dev", "concurrent-" + index);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int accepted = 0;
            for (Future<ConfigWatchNotifier.Registration> future : futures) {
                if (future.get(5, TimeUnit.SECONDS).accepted()) {
                    accepted++;
                }
            }
            assertEquals(3, accepted);
            assertEquals(3, notifier.pendingWaiterCount());
            assertEquals(3, notifier.pendingNamespaceCount());
        } finally {
            executor.shutdownNow();
        }

        for (int i = 0; i < attempts; i++) {
            notifier.notifyChanged("concurrent-" + i, "dev", 1);
        }
        assertEquals(0, notifier.pendingWaiterCount());
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void metrics_reflectPendingNamespacesAndRejectedRequests() {
        Gauge pending = meterRegistry.find("config_center_watch_pending").gauge();
        Gauge namespaces = meterRegistry.find("config_center_watch_namespaces").gauge();
        FunctionCounter rejected = meterRegistry.find("config_center_watch_rejected").functionCounter();
        assertNotNull(pending);
        assertNotNull(namespaces);
        assertNotNull(rejected);
        double rejectedBefore = rejected.count();

        register("metrics", "dev", "metrics-one");
        register("metrics", "dev", "metrics-two");
        ConfigWatchNotifier.Registration rejectedRegistration = register("metrics", "dev", "metrics-three");

        assertEquals(false, rejectedRegistration.accepted());
        assertEquals(2.0, pending.value());
        assertEquals(1.0, namespaces.value());
        assertEquals(rejectedBefore + 1, rejected.count());

        notifier.notifyChanged("metrics", "dev", 1);
        assertEquals(0.0, pending.value());
        assertEquals(0.0, namespaces.value());
    }

    private ConfigWatchNotifier.Registration register(String app, String env, String traceId) {
        return notifier.register(app, env, Duration.ofSeconds(5), 0, traceId);
    }

    private MvcResult startWatch(String app, String env, String traceId) throws Exception {
        return mockMvc.perform(get("/api/configs/watch")
                        .param("app", app)
                        .param("env", env)
                        .param("sinceVersion", "0")
                        .param("timeoutSeconds", "5")
                        .header(TraceIdFilter.HEADER, traceId))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private void assertRejectedWatch(String app, String env, String traceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/configs/watch")
                        .param("app", app)
                        .param("env", env)
                        .param("sinceVersion", "0")
                        .param("timeoutSeconds", "5")
                        .header(TraceIdFilter.HEADER, traceId))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.code").value(4290))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    private void completeWatch(
            MvcResult result, String app, String env, String traceId, long latestVersion) throws Exception {
        notifier.notifyChanged(app, env, latestVersion);
        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.latestVersion").value(latestVersion));
    }
}
