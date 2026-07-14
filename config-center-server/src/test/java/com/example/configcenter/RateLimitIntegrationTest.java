package com.example.configcenter;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rate-limit.enabled=true",
        "rate-limit.capacity=1",
        "rate-limit.refill-per-second=0"
})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void blockedRequest_returns429AndIncrementsInstanceMetric() throws Exception {
        var gauge = meterRegistry.find("config_center_rate_limit_blocked_total").gauge();
        assertNotNull(gauge);
        double before = gauge.value();

        mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(4290));

        assertEquals(before + 1, gauge.value());
    }
}
