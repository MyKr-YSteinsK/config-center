package com.example.configcenter;

import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import com.example.configcenter.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Autowired
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
        service.upsert(config("v1"));

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
    void changedConfig_invalidatesPreviousEtag() throws Exception {
        service.upsert(config("v1"));
        String firstEtag = service.etagForList("app", "dev");
        service.upsert(config("v2"));

        String nextEtag = mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev")
                        .header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").value("v2"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertNotEquals(firstEtag, nextEtag);
    }

    private UpsertConfigRequest config(String value) {
        UpsertConfigRequest request = new UpsertConfigRequest();
        request.setApp("app");
        request.setEnv("dev");
        request.setKey("key");
        request.setValue(value);
        return request;
    }
}
