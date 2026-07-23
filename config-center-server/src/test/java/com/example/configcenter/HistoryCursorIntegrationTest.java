package com.example.configcenter;

import com.example.configcenter.config.TraceIdFilter;
import com.example.configcenter.domain.entity.ConfigItemHistory;
import com.example.configcenter.domain.entity.FeatureFlagHistory;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.FeatureFlagHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rate-limit.enabled=false")
@AutoConfigureMockMvc
class HistoryCursorIntegrationTest {

    private static final String APP = "history-app";
    private static final String ENV = "dev";

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private ConfigItemHistoryRepository configHistoryRepository;

    @SpyBean
    private FeatureFlagHistoryRepository featureHistoryRepository;

    @BeforeEach
    void cleanup() {
        featureHistoryRepository.deleteAllInBatch();
        configHistoryRepository.deleteAllInBatch();
        clearInvocations(configHistoryRepository, featureHistoryRepository);
    }

    @Test
    void defaultHistoryReadsAreBoundedToNewestFiftyAtRepositoryLevel() throws Exception {
        seedConfigHistory("config-bounded", 55);
        seedFeatureHistory("feature-bounded", 55);
        clearInvocations(configHistoryRepository, featureHistoryRepository);

        mockMvc.perform(configHistory("config-bounded")
                        .header(TraceIdFilter.HEADER, "config-history-trace"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER, "config-history-trace"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.traceId").value("config-history-trace"))
                .andExpect(jsonPath("$.data.length()").value(50))
                .andExpect(jsonPath("$.data[0].version").value(55))
                .andExpect(jsonPath("$.data[49].version").value(6));

        ArgumentCaptor<Pageable> configPageable = ArgumentCaptor.forClass(Pageable.class);
        verify(configHistoryRepository).findAllByAppAndEnvAndConfigKeyOrderByVersionDesc(
                eq(APP), eq(ENV), eq("config-bounded"), configPageable.capture());
        assertEquals(0, configPageable.getValue().getPageNumber());
        assertEquals(50, configPageable.getValue().getPageSize());

        mockMvc.perform(featureHistory("feature-bounded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(50))
                .andExpect(jsonPath("$.data[0].version").value(55))
                .andExpect(jsonPath("$.data[49].version").value(6));

        ArgumentCaptor<Pageable> featurePageable = ArgumentCaptor.forClass(Pageable.class);
        verify(featureHistoryRepository).findAllByAppAndEnvAndNameOrderByVersionDesc(
                eq(APP), eq(ENV), eq("feature-bounded"), featurePageable.capture());
        assertEquals(0, featurePageable.getValue().getPageNumber());
        assertEquals(50, featurePageable.getValue().getPageSize());
    }

    @Test
    void historyLimitAcceptsOneAndTwoHundredForBothResources() throws Exception {
        seedConfigHistory("config-limit", 55);
        seedFeatureHistory("feature-limit", 55);

        mockMvc.perform(configHistory("config-limit").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value(55));
        mockMvc.perform(configHistory("config-limit").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(55));

        mockMvc.perform(featureHistory("feature-limit").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value(55));
        mockMvc.perform(featureHistory("feature-limit").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(55));
    }

    @Test
    void invalidHistoryPagingParametersReturnParameterCodeForBothResources() throws Exception {
        for (String value : new String[]{"0", "201"}) {
            mockMvc.perform(configHistory("config-invalid").param("limit", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4001));
            mockMvc.perform(featureHistory("feature-invalid").param("limit", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4001));
        }

        mockMvc.perform(configHistory("config-invalid").param("beforeVersion", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));
        mockMvc.perform(featureHistory("feature-invalid").param("beforeVersion", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void cursorsAreExclusiveOrderedAndIndependentAcrossHistoryResources() throws Exception {
        seedConfigHistory("config-cursor", 5);
        seedFeatureHistory("feature-cursor", 5);

        mockMvc.perform(configHistory("config-cursor").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(5))
                .andExpect(jsonPath("$.data[1].version").value(4));
        mockMvc.perform(configHistory("config-cursor")
                        .param("limit", "2")
                        .param("beforeVersion", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(3))
                .andExpect(jsonPath("$.data[1].version").value(2));
        mockMvc.perform(configHistory("config-cursor").param("beforeVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(featureHistory("feature-cursor").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(5))
                .andExpect(jsonPath("$.data[1].version").value(4));
        mockMvc.perform(featureHistory("feature-cursor")
                        .param("limit", "2")
                        .param("beforeVersion", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(3))
                .andExpect(jsonPath("$.data[1].version").value(2));
        mockMvc.perform(featureHistory("feature-cursor").param("beforeVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder configHistory(
            String key) {
        return get("/api/configs/history")
                .param("app", APP)
                .param("env", ENV)
                .param("key", key);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder featureHistory(
            String name) {
        return get("/api/features/history")
                .param("app", APP)
                .param("env", ENV)
                .param("name", name);
    }

    private void seedConfigHistory(String key, int count) {
        List<ConfigItemHistory> history = new ArrayList<>();
        for (int version = 1; version <= count; version++) {
            ConfigItemHistory item = new ConfigItemHistory();
            item.setApp(APP);
            item.setEnv(ENV);
            item.setConfigKey(key);
            item.setConfigValue("value-" + version);
            item.setVersion(version);
            item.setAction("UPSERT");
            item.setOperator("history-test");
            item.setReason("seed");
            item.setCreatedAt(Instant.ofEpochSecond(version));
            history.add(item);
        }
        configHistoryRepository.saveAll(history);
    }

    private void seedFeatureHistory(String name, int count) {
        List<FeatureFlagHistory> history = new ArrayList<>();
        for (int version = 1; version <= count; version++) {
            FeatureFlagHistory item = new FeatureFlagHistory();
            item.setApp(APP);
            item.setEnv(ENV);
            item.setName(name);
            item.setEnabled(true);
            item.setRolloutPercentage(50);
            item.setAllowlist(List.of());
            item.setVersion(version);
            item.setAction("UPSERT");
            item.setOperator("history-test");
            item.setReason("seed");
            item.setCreatedAt(Instant.ofEpochSecond(version));
            history.add(item);
        }
        featureHistoryRepository.saveAll(history);
    }
}
