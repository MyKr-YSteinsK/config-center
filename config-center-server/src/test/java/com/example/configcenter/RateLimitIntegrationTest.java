package com.example.configcenter;

import com.example.configcenter.web.RateLimitInterceptor;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rate-limit.enabled=true",
        "rate-limit.capacity=1",
        "rate-limit.refill-per-second=0",
        "rate-limit.max-buckets=3"
})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Test
    void blockedRequest_returns429AndIncrementsInstanceMetric() throws Exception {
        FunctionCounter counter = meterRegistry.find("config_center_rate_limit_blocked").functionCounter();
        assertNotNull(counter);
        double before = counter.count();

        mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev")
                        .with(remoteAddress("192.0.2.1")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev")
                        .with(remoteAddress("192.0.2.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(4290));

        mockMvc.perform(get("/api/configs")
                        .param("app", "app")
                        .param("env", "dev")
                        .with(remoteAddress("192.0.2.1")))
                .andExpect(status().isTooManyRequests());

        assertEquals(before + 2, counter.count());
    }

    @Test
    void dynamicConfigKeys_shareMatchedRouteBucket() throws Exception {
        mockMvc.perform(get("/api/configs/first")
                        .param("app", "app")
                        .param("env", "dev")
                        .with(remoteAddress("192.0.2.2")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/configs/second")
                        .param("app", "app")
                        .param("env", "dev")
                        .with(remoteAddress("192.0.2.2")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(4290));
    }

    @Test
    void bucketCount_staysWithinConfiguredBound() throws Exception {
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(get("/api/ping")
                            .with(remoteAddress("198.51.100." + i)))
                    .andExpect(status().isOk());
        }

        assertEquals(3, rateLimitInterceptor.getBucketCount());
    }

    private RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
