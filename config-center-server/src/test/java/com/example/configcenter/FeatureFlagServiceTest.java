package com.example.configcenter;

import com.example.configcenter.dto.request.RollbackFeatureRequest;
import com.example.configcenter.dto.request.UpsertFeatureRequest;
import com.example.configcenter.repository.FeatureFlagHistoryRepository;
import com.example.configcenter.repository.FeatureFlagRepository;
import com.example.configcenter.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FeatureFlagServiceTest {

    @Autowired
    private FeatureFlagService service;

    @Autowired
    private FeatureFlagRepository repository;

    @Autowired
    private FeatureFlagHistoryRepository historyRepository;

    @BeforeEach
    void cleanup() {
        historyRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void upsertAndHistory_preserveFeatureSnapshots() {
        UpsertFeatureRequest request = feature(false, 10, List.of("u1"));
        request.setOperator("alice");
        request.setReason("create");
        service.upsert(request);

        request.setEnabled(true);
        request.setRolloutPercentage(50);
        request.setAllowlist(List.of("u2"));
        request.setReason("expand rollout");
        var updated = service.upsert(request);

        var history = service.history("app", "dev", "checkout");
        assertEquals(2, updated.getVersion());
        assertTrue(updated.isEnabled());
        assertEquals(2, history.size());
        assertEquals(2, history.get(0).getVersion());
        assertEquals(50, history.get(0).getRolloutPercentage());
        assertEquals(List.of("u2"), history.get(0).getAllowlist());
        assertEquals("expand rollout", history.get(0).getReason());
        assertEquals(1, history.get(1).getVersion());
        assertFalse(history.get(1).isEnabled());
    }

    @Test
    void rollback_restoresFeatureSnapshotAsNewVersion() {
        UpsertFeatureRequest request = feature(false, 10, List.of("u1"));
        service.upsert(request);
        request.setEnabled(true);
        request.setRolloutPercentage(80);
        request.setAllowlist(List.of("u2"));
        service.upsert(request);

        RollbackFeatureRequest rollback = new RollbackFeatureRequest();
        rollback.setApp("app");
        rollback.setEnv("dev");
        rollback.setName("checkout");
        rollback.setTargetVersion(1L);
        rollback.setOperator("bob");
        rollback.setReason("restore stable rule");

        var restored = service.rollback(rollback);
        var history = service.history("app", "dev", "checkout");

        assertEquals(3, restored.getVersion());
        assertFalse(restored.isEnabled());
        assertEquals(10, restored.getRolloutPercentage());
        assertEquals(List.of("u1"), restored.getAllowlist());
        assertEquals("ROLLBACK", history.get(0).getAction());
        assertEquals("rollback-to=1, restore stable rule", history.get(0).getReason());
    }

    @Test
    void allowlistDoesNotRetainCallerCollectionOrExposeMutableResponse() {
        List<String> requestAllowlist = new ArrayList<>(List.of("u1"));
        var created = service.upsert(feature(true, 100, requestAllowlist));
        requestAllowlist.add("u2");

        assertEquals(List.of("u1"), created.getAllowlist());
        assertEquals(List.of("u1"), service.list("app", "dev").get(0).getAllowlist());
        assertEquals(List.of("u1"), service.history("app", "dev", "checkout").get(0).getAllowlist());
        assertThrows(UnsupportedOperationException.class, () -> created.getAllowlist().add("u3"));
    }

    private UpsertFeatureRequest feature(boolean enabled, int rollout, List<String> allowlist) {
        UpsertFeatureRequest request = new UpsertFeatureRequest();
        request.setApp("app");
        request.setEnv("dev");
        request.setName("checkout");
        request.setEnabled(enabled);
        request.setRolloutPercentage(rollout);
        request.setAllowlist(allowlist);
        return request;
    }
}
