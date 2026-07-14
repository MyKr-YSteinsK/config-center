package com.example.configcenter;

import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.ConfigWatchNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void watchTimesOutWithNoChange() {
        var response = restTemplate.getForEntity(
                "/api/configs/watch?app=app&env=dev&sinceVersion=0&timeoutSeconds=1",
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"changed\":false"));
        assertTrue(response.getBody().contains("\"latestVersion\":0"));
    }

    @Test
    void watchReturnsImmediatelyWhenRevisionIsNewer() throws Exception {
        service.upsert(config("key", "v1"));

        MvcResult result = mockMvc.perform(get("/api/configs/watch")
                        .param("app", "app")
                        .param("env", "dev")
                        .param("sinceVersion", "0"))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.latestVersion").value(1));
    }

    @Test
    void waitingWatchIsNotifiedByCommittedChange() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/configs/watch")
                        .param("app", "app")
                        .param("env", "dev")
                        .param("sinceVersion", "0")
                        .param("timeoutSeconds", "5"))
                .andExpect(request().asyncStarted())
                .andReturn();

        service.upsert(config("key", "v1"));

        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.latestVersion").value(1));
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
