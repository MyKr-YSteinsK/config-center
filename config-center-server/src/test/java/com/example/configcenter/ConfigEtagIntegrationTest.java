package com.example.configcenter;

import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import com.example.configcenter.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rate-limit.enabled=false")
@AutoConfigureMockMvc
class ConfigEtagIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigService service;

    @SpyBean
    private ConfigItemRepository itemRepository;

    @Autowired
    private ConfigItemHistoryRepository historyRepository;

    @Autowired
    private ConfigNamespaceRevisionRepository revisionRepository;

    @BeforeEach
    void cleanup() {
        historyRepository.deleteAll();
        itemRepository.deleteAll();
        revisionRepository.deleteAll();
    }

    @Test
    void matchingIfNoneMatch_returns304WithoutBody() throws Exception {
        service.upsert(config("v1", "first"));

        String etag = mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.data[0].value").value("v1"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev")
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));
    }

    @Test
    void reusedBusinessVersionWithChangedValueAndDescription_invalidatesEtag() throws Exception {
        service.upsert(config("v1", "first"));
        String firstEtag = currentEtag();

        resetStorage();
        service.upsert(config("v2", "first"));
        String valueChangedEtag = mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev")
                        .header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").value("v2"))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertNotEquals(firstEtag, valueChangedEtag);

        resetStorage();
        service.upsert(config("v2", "second"));

        String descriptionChangedEtag = mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev")
                        .header(HttpHeaders.IF_NONE_MATCH, valueChangedEtag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").value("v2"))
                .andExpect(jsonPath("$.data[0].description").value("second"))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertNotEquals(valueChangedEtag, descriptionChangedEtag);
    }

    @Test
    void etagAndBody_useOneLoadedSnapshot() throws Exception {
        service.upsert(config("v1", "first"));
        clearInvocations(itemRepository);

        mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.data[0].value").value("v1"));

        verify(itemRepository, times(1))
                .findAllByAppAndEnvOrderByConfigKeyAsc("app", "dev");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conditionalGetEvaluatesExistingRepresentation(boolean populated) throws Exception {
        if (populated) {
            service.upsert(config("v1", "first"));
        }
        String etag = currentEtag();
        for (String condition : List.of("*", etag, etag.substring(2), "\"other\", " + etag)) {
            mockMvc.perform(get("/api/configs").param("app", "app").param("env", "dev")
                            .header(HttpHeaders.IF_NONE_MATCH, condition).header("X-Trace-Id", "conditional"))
                    .andExpect(status().isNotModified())
                    .andExpect(content().string(""))
                    .andExpect(header().string(HttpHeaders.ETAG, etag))
                    .andExpect(header().string("X-Trace-Id", "conditional"));
        }
        mockMvc.perform(get("/api/configs").param("app", "app").param("env", "dev")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"other\", W/\"another\""))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(populated ? 1 : 0));
        mockMvc.perform(get("/api/configs").param("app", " ").param("env", "dev")
                        .header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void timestampAloneInvalidatesEtag() throws Exception {
        service.upsert(config("fixed", "description"));
        String etag = currentEtag();
        var row = itemRepository.findByAppAndEnvAndConfigKey("app", "dev", "key").orElseThrow();
        row.setUpdatedAt(Instant.parse("2001-01-01T00:00:00Z"));
        itemRepository.saveAndFlush(row);
        assertNotEquals(etag, currentEtag());
    }

    private String currentEtag() throws Exception {
        return mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private void resetStorage() {
        historyRepository.deleteAll();
        itemRepository.deleteAll();
        revisionRepository.deleteAll();
    }

    private UpsertConfigRequest config(String value, String description) {
        UpsertConfigRequest request = new UpsertConfigRequest();
        request.setApp("app");
        request.setEnv("dev");
        request.setKey("key");
        request.setValue(value);
        request.setDescription(description);
        return request;
    }
}
