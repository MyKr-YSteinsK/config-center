package com.example.configcenter;

import com.example.configcenter.config.TraceIdFilter;
import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.ConfigWatchNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rate-limit.enabled=false"
)
@AutoConfigureMockMvc
class ConfigWatchIntegrationTest {

    @Autowired
    private ConfigService service;

    @Autowired
    private ConfigItemRepository itemRepository;

    @Autowired
    private ConfigItemHistoryRepository historyRepository;

    @Autowired
    private ConfigNamespaceRevisionRepository revisionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRestTemplate restTemplate;

    @SpyBean
    private ConfigWatchNotifier notifier;

    @BeforeEach
    void cleanup() {
        historyRepository.deleteAll();
        itemRepository.deleteAll();
        revisionRepository.deleteAll();
        clearInvocations(notifier);
    }

    @Test
    void initialRevision_isZero() {
        assertEquals(0, service.latestVersion("app", "dev"));
    }

    @Test
    void upsert_advancesRevisionForEverySuccessfulWrite() {
        service.upsert(config("key", "v1"));
        assertEquals(1, service.latestVersion("app", "dev"));

        service.upsert(config("key", "v2"));
        assertEquals(2, service.latestVersion("app", "dev"));
    }

    @Test
    void lowerVersionKeyUpdate_cannotBeHiddenByAnotherItemVersion() {
        service.upsert(config("high", "v1"));
        service.upsert(config("high", "v2"));
        service.upsert(config("high", "v3"));
        service.upsert(config("high", "v4"));
        service.upsert(config("low", "v1"));
        service.upsert(config("low", "v2"));

        long maximumItemVersion = service.list("app", "dev").stream()
                .mapToLong(item -> item.getVersion())
                .max()
                .orElse(0);

        assertEquals(4, maximumItemVersion);
        assertEquals(6, service.latestVersion("app", "dev"));
    }

    @Test
    void rollback_advancesRevisionAndNotifiesAfterCommit() {
        service.upsert(config("key", "v1"));
        service.upsert(config("key", "v2"));
        clearInvocations(notifier);

        service.rollback(rollback("key", 1));

        assertEquals(3, service.latestVersion("app", "dev"));
        verify(notifier).notifyChanged("app", "dev", 3);
    }

    @Test
    void rolledBackTransaction_doesNotExposeRevisionOrNotify() {
        transactionTemplate.executeWithoutResult(status -> {
            service.upsert(config("key", "v1"));
            status.setRollbackOnly();
        });

        assertEquals(0, service.latestVersion("app", "dev"));
        assertEquals(0, itemRepository.count());
        verify(notifier, never()).notifyChanged(eq("app"), eq("dev"), anyLong());
    }

    @Test
    void watchTimeout_preservesTraceIdAndRemovesNamespace() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceIdFilter.HEADER, "watch-timeout");
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/configs/watch?app=app&env=dev&sinceVersion=0&timeoutSeconds=1",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("watch-timeout", response.getHeaders().getFirst(TraceIdFilter.HEADER));
        assertEquals("watch-timeout", response.getBody().path("traceId").asText());
        assertEquals(false, response.getBody().path("data").path("changed").asBoolean());
        assertEquals(0, response.getBody().path("data").path("latestVersion").asLong());

        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void watchReturnsImmediatelyWhenRevisionIsNewer() throws Exception {
        service.upsert(config("key", "v1"));

        MvcResult result = mockMvc.perform(get("/api/configs/watch")
                        .param("app", "app")
                        .param("env", "dev")
                        .param("sinceVersion", "0")
                        .header(TraceIdFilter.HEADER, "watch-immediate"))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER, "watch-immediate"))
                .andExpect(jsonPath("$.traceId").value("watch-immediate"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.latestVersion").value(1));
    }

    @Test
    void twoWaitingWatchers_keepOwnTraceIdsWhenWriteRequestNotifies() throws Exception {
        MvcResult first = startWatch("demo-app", "dev", "watch-one", 5);
        MvcResult second = startWatch("demo-app", "dev", "watch-two", 5);

        mockMvc.perform(post("/api/configs")
                        .header("X-API-Key", "kr-dev-key")
                        .header(TraceIdFilter.HEADER, "write-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "app": "demo-app",
                                  "env": "dev",
                                  "key": "key",
                                  "value": "v1"
                                }
                                """))
                .andExpect(status().isOk());

        first.getAsyncResult(2_000);
        second.getAsyncResult(2_000);
        assertWatchChanged(first, "watch-one", 1);
        assertWatchChanged(second, "watch-two", 1);
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void separatorCharacters_doNotCollideNamespaces() throws Exception {
        MvcResult first = startWatch("a|b", "c", "separator-one", 5);
        MvcResult second = startWatch("a", "b|c", "separator-two", 5);

        service.upsert(config("a|b", "c", "key", "v1"));
        first.getAsyncResult(2_000);
        assertWatchChanged(first, "separator-one", 1);
        assertEquals(1, notifier.pendingNamespaceCount());

        service.upsert(config("a", "b|c", "key", "v1"));
        second.getAsyncResult(2_000);
        assertWatchChanged(second, "separator-two", 1);
        assertEquals(0, notifier.pendingNamespaceCount());
    }

    @Test
    void invalidWatchRevisionAndTimeout_return400() throws Exception {
        mockMvc.perform(get("/api/configs/watch")
                        .param("app", "app")
                        .param("env", "dev")
                        .param("sinceVersion", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));

        for (String timeout : new String[]{"0", "61"}) {
            mockMvc.perform(get("/api/configs/watch")
                            .param("app", "app")
                            .param("env", "dev")
                            .param("sinceVersion", "0")
                            .param("timeoutSeconds", timeout))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4001));
        }
    }

    private MvcResult startWatch(String app, String env, String traceId, int timeoutSeconds)
            throws Exception {
        return mockMvc.perform(get("/api/configs/watch")
                        .param("app", app)
                        .param("env", env)
                        .param("sinceVersion", "0")
                        .param("timeoutSeconds", Integer.toString(timeoutSeconds))
                        .header(TraceIdFilter.HEADER, traceId))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private void assertWatchChanged(MvcResult result, String traceId, long latestVersion)
            throws Exception {
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.latestVersion").value(latestVersion));
    }

    private UpsertConfigRequest config(String app, String env, String key, String value) {
        UpsertConfigRequest request = config(key, value);
        request.setApp(app);
        request.setEnv(env);
        return request;
    }

    private UpsertConfigRequest config(String key, String value) {
        UpsertConfigRequest request = new UpsertConfigRequest();
        request.setApp("app");
        request.setEnv("dev");
        request.setKey(key);
        request.setValue(value);
        return request;
    }

    private RollbackConfigRequest rollback(String key, long targetVersion) {
        RollbackConfigRequest request = new RollbackConfigRequest();
        request.setApp("app");
        request.setEnv("dev");
        request.setKey(key);
        request.setTargetVersion(targetVersion);
        return request;
    }
}
