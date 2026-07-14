package com.example.configcenter;

import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.repository.ConfigItemHistoryRepository;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.repository.ConfigNamespaceRevisionRepository;
import com.example.configcenter.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class ConfigServiceTest {

    @Autowired
    ConfigService service;

    @Autowired
    ConfigItemRepository repo;

    @Autowired
    ConfigItemHistoryRepository historyRepo;

    @Autowired
    ConfigNamespaceRevisionRepository revisionRepo;

    @BeforeEach
    void cleanup() {
        historyRepo.deleteAll();
        repo.deleteAll();
        revisionRepo.deleteAll();
    }

    @Test
    void create_versionIs1() {
        UpsertConfigRequest req = new UpsertConfigRequest();
        req.setApp("a");
        req.setEnv("dev");
        req.setKey("k1");
        req.setValue("v1");

        var dto = service.upsert(req);
        assertEquals(1, dto.getVersion());
        assertEquals("v1", dto.getValue());
    }

    @Test
    void update_versionAutoIncrement() {
        UpsertConfigRequest req = new UpsertConfigRequest();
        req.setApp("a");
        req.setEnv("dev");
        req.setKey("k1");
        req.setValue("v1");

        var dto1 = service.upsert(req);

        req.setValue("v2");
        var dto2 = service.upsert(req);

        assertEquals(dto1.getVersion() + 1, dto2.getVersion());
        assertEquals("v2", dto2.getValue());
    }

    @Test
    void upsert_sameUniqueKey_onlyOneRow() {
        UpsertConfigRequest req = new UpsertConfigRequest();
        req.setApp("a");
        req.setEnv("dev");
        req.setKey("k1");
        req.setValue("v1");

        service.upsert(req);
        req.setValue("v2");
        service.upsert(req);

        assertEquals(1, repo.count());
    }

    @Test
    void history_preservesEveryUpsertSnapshot() {
        UpsertConfigRequest req = request("v1", "first");
        req.setOperator("alice");
        req.setReason("create");
        service.upsert(req);

        req.setValue("v2");
        req.setDescription("second");
        req.setReason("update");
        service.upsert(req);

        var history = service.history("a", "dev", "k1");
        assertEquals(2, history.size());
        assertEquals(2, history.get(0).getVersion());
        assertEquals("v2", history.get(0).getValue());
        assertEquals("UPSERT", history.get(0).getAction());
        assertEquals("update", history.get(0).getReason());
        assertEquals(1, history.get(1).getVersion());
        assertEquals("v1", history.get(1).getValue());
    }

    @Test
    void rollback_restoresSnapshotAsNewVersion() {
        UpsertConfigRequest req = request("v1", "first");
        service.upsert(req);
        req.setValue("v2");
        req.setDescription("second");
        service.upsert(req);

        RollbackConfigRequest rollback = new RollbackConfigRequest();
        rollback.setApp("a");
        rollback.setEnv("dev");
        rollback.setKey("k1");
        rollback.setTargetVersion(1L);
        rollback.setOperator("bob");
        rollback.setReason("restore stable value");

        var restored = service.rollback(rollback);
        var history = service.history("a", "dev", "k1");

        assertEquals(3, restored.getVersion());
        assertEquals("v1", restored.getValue());
        assertEquals("first", restored.getDescription());
        assertEquals(3, history.size());
        assertEquals("ROLLBACK", history.get(0).getAction());
        assertEquals("bob", history.get(0).getOperator());
        assertEquals("rollback-to=1, restore stable value", history.get(0).getReason());
    }

    @Test
    void jpaOptimisticLock_rejectsStaleDetachedEntity() {
        service.upsert(request("v1", "first"));
        var firstWriter = repo.findByAppAndEnvAndConfigKey("a", "dev", "k1").orElseThrow();
        var staleWriter = repo.findByAppAndEnvAndConfigKey("a", "dev", "k1").orElseThrow();

        firstWriter.setDescription("writer-one");
        repo.saveAndFlush(firstWriter);

        staleWriter.setDescription("writer-two");
        assertThrows(OptimisticLockingFailureException.class,
                () -> repo.saveAndFlush(staleWriter));
    }

    private UpsertConfigRequest request(String value, String description) {
        UpsertConfigRequest req = new UpsertConfigRequest();
        req.setApp("a");
        req.setEnv("dev");
        req.setKey("k1");
        req.setValue(value);
        req.setDescription(description);
        return req;
    }
}
