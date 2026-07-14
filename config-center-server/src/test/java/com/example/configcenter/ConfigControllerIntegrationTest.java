package com.example.configcenter;

import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.service.ApiKeyService;
import com.example.configcenter.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rate-limit.enabled=false")
@AutoConfigureMockMvc
class ConfigControllerIntegrationTest {

    private static final String VALID_CONFIG = """
            {"app":"demo-app","env":"dev","key":"sample.key","value":"value"}
            """;
    private static final String VALID_ROLLBACK = """
            {"app":"demo-app","env":"dev","key":"sample.key","targetVersion":1}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConfigService configService;

    @MockBean
    private ApiKeyService apiKeyService;

    @Test
    void bodyValidation_returns400WithParameterCode() throws Exception {
        mockMvc.perform(post("/api/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void requestParameterValidation_returns400WithParameterCode() throws Exception {
        mockMvc.perform(get("/api/configs")
                        .param("app", "")
                        .param("env", "dev"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void malformedJson_returns400WithParameterCode() throws Exception {
        mockMvc.perform(post("/api/configs")
                        .header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void dataIntegrityConflict_returns409WithConflictCode() throws Exception {
        allowConfigWrites();
        when(configService.upsert(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        mockMvc.perform(post("/api/configs")
                        .header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4091));
    }

    @Test
    void optimisticLockConflict_returns409WithConflictCode() throws Exception {
        allowConfigWrites();
        when(configService.upsert(any())).thenThrow(new OptimisticLockingFailureException("stale write"));

        mockMvc.perform(post("/api/configs")
                        .header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4091));
    }

    @Test
    void missingOrUnauthorizedApiKey_returns403WithAuthorizationCode() throws Exception {
        mockMvc.perform(post("/api/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4031));

        mockMvc.perform(post("/api/configs")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4031));
    }

    @Test
    void allowedApiKey_preservesSuccessfulUpsertResponse() throws Exception {
        allowConfigWrites();
        when(configService.upsert(any())).thenReturn(configDto());

        mockMvc.perform(post("/api/configs")
                        .header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.key").value("sample.key"));
    }

    @Test
    void rollback_usesTheSameApiKeyAuthorizationRule() throws Exception {
        mockMvc.perform(post("/api/configs/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ROLLBACK))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4031));

        allowConfigWrites();
        when(configService.rollback(any())).thenReturn(configDto());

        mockMvc.perform(post("/api/configs/rollback")
                        .header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ROLLBACK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private void allowConfigWrites() {
        when(apiKeyService.allow("kr-dev-key", "demo-app", "dev")).thenReturn(true);
    }

    private ConfigItemDto configDto() {
        return new ConfigItemDto("demo-app", "dev", "sample.key", "value", null, 1, Instant.now());
    }
}
