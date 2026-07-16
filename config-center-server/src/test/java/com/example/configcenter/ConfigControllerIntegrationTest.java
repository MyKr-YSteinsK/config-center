package com.example.configcenter;

import com.example.configcenter.config.TraceIdFilter;
import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.dto.response.FeatureEvalResult;
import com.example.configcenter.dto.response.FeatureFlagDto;
import com.example.configcenter.service.ApiKeyService;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.FeatureFlagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rate-limit.enabled=false")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ConfigControllerIntegrationTest {

    private static final String VALID_CONFIG = """
            {"app":"demo-app","env":"dev","key":"sample.key","value":"value"}
            """;
    private static final String VALID_ROLLBACK = """
            {"app":"demo-app","env":"dev","key":"sample.key","targetVersion":1}
            """;
    private static final String VALID_FEATURE = """
            {"app":"demo-app","env":"dev","name":"new-checkout","enabled":true,
             "rolloutPercentage":30,"allowlist":["u1000"]}
            """;
    private static final String VALID_FEATURE_ROLLBACK = """
            {"app":"demo-app","env":"dev","name":"new-checkout","targetVersion":1}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConfigService configService;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private FeatureFlagService featureFlagService;

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
    void oversizedAndNonPositiveConfigFields_return400BeforeService() throws Exception {
        String oversizedValue = "x".repeat(2001);
        String oversizedBody = """
                {"app":"demo-app","env":"dev","key":"sample.key","value":"%s"}
                """.formatted(oversizedValue);
        String invalidExpectedVersion = """
                {"app":"demo-app","env":"dev","key":"sample.key","value":"value","expectedVersion":0}
                """;
        String invalidTargetVersion = """
                {"app":"demo-app","env":"dev","key":"sample.key","targetVersion":0}
                """;

        for (String body : new String[]{oversizedBody, invalidExpectedVersion}) {
            mockMvc.perform(post("/api/configs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4001));
        }
        mockMvc.perform(post("/api/configs/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidTargetVersion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));

        verifyNoInteractions(configService);
    }

    @Test
    void featureAllowlistBoundsAndItems_return400BeforeService() throws Exception {
        String tooManyItems = String.join(",", Collections.nCopies(21, "\"user\""));
        String blankItem = featureBody("\" \"");
        String oversizedItem = featureBody("\"" + "x".repeat(33) + "\"");
        String oversizedList = featureBody(tooManyItems);

        for (String body : new String[]{blankItem, oversizedItem, oversizedList}) {
            mockMvc.perform(post("/api/features")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4001));
        }

        verifyNoInteractions(featureFlagService);
    }

    @Test
    void numericQueryTypeMismatch_returns400() throws Exception {
        mockMvc.perform(get("/api/configs/watch")
                        .param("app", "app")
                        .param("env", "dev")
                        .param("sinceVersion", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("请求参数类型错误: sinceVersion"));
    }

    @Test
    void unknownException_logsTraceAndStackButReturnsStableBody(CapturedOutput output)
            throws Exception {
        allowConfigWrites();
        when(configService.upsert(any()))
                .thenThrow(new IllegalStateException("sensitive-internal-message"));

        MvcResult result = mockMvc.perform(post("/api/configs")
                        .header("X-API-Key", "kr-dev-key")
                        .header(TraceIdFilter.HEADER, "unknown-error-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(5000))
                .andExpect(jsonPath("$.message").value("系统异常"))
                .andExpect(jsonPath("$.traceId").value("unknown-error-trace"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.contains("sensitive-internal-message"));
        assertFalse(responseBody.contains("IllegalStateException"));
        assertTrue(output.getAll().contains("unknown-error-trace"));
        assertTrue(output.getAll().contains(
                "java.lang.IllegalStateException: sensitive-internal-message"));
        assertTrue(output.getAll().contains("ConfigControllerIntegrationTest"));
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

    @Test
    void featureWrites_missingApiKeyReturn403() throws Exception {
        mockMvc.perform(post("/api/features")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_FEATURE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4031));

        mockMvc.perform(post("/api/features/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_FEATURE_ROLLBACK))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4031));

        verifyNoInteractions(featureFlagService);
    }

    @Test
    void featureWrites_unauthorizedApiKeyReturn403() throws Exception {
        mockMvc.perform(post("/api/features")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_FEATURE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4031));

        mockMvc.perform(post("/api/features/rollback")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_FEATURE_ROLLBACK))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4031));

        verifyNoInteractions(featureFlagService);
    }

    @Test
    void featureWrites_allowedApiKeyReachService() throws Exception {
        allowConfigWrites();
        when(featureFlagService.upsert(any())).thenReturn(featureDto());
        when(featureFlagService.rollback(any())).thenReturn(featureDto());

        mockMvc.perform(post("/api/features")
                        .header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_FEATURE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("new-checkout"));

        mockMvc.perform(post("/api/features/rollback")
                        .header("X-API-Key", "kr-dev-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_FEATURE_ROLLBACK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("new-checkout"));
    }

    @Test
    void featureReadsAndEvaluation_remainUnauthenticated() throws Exception {
        when(featureFlagService.list("demo-app", "dev")).thenReturn(Collections.emptyList());
        when(featureFlagService.history("demo-app", "dev", "new-checkout"))
                .thenReturn(Collections.emptyList());
        when(featureFlagService.evaluate("demo-app", "dev", "new-checkout", "u1000"))
                .thenReturn(new FeatureEvalResult(
                        "new-checkout", "u1000", true, -1, "allowlist 命中"));

        mockMvc.perform(get("/api/features")
                        .param("app", "demo-app")
                        .param("env", "dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/features/history")
                        .param("app", "demo-app")
                        .param("env", "dev")
                        .param("name", "new-checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/features/evaluate")
                        .param("app", "demo-app")
                        .param("env", "dev")
                        .param("name", "new-checkout")
                        .param("userId", "u1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true));

        verifyNoInteractions(apiKeyService);
    }

    private void allowConfigWrites() {
        when(apiKeyService.allow("kr-dev-key", "demo-app", "dev")).thenReturn(true);
    }

    private String featureBody(String allowlistItems) {
        return """
                {
                  "app":"app",
                  "env":"dev",
                  "name":"feature",
                  "enabled":true,
                  "rolloutPercentage":50,
                  "allowlist":[%s]
                }
                """.formatted(allowlistItems);
    }

    private ConfigItemDto configDto() {
        return new ConfigItemDto("demo-app", "dev", "sample.key", "value", null, 1, Instant.now());
    }

    private FeatureFlagDto featureDto() {
        return new FeatureFlagDto(
                "demo-app", "dev", "new-checkout", true, 30,
                Collections.singletonList("u1000"), Instant.now(), 1);
    }
}
