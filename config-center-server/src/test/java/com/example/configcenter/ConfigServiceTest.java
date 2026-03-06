package com.example.configcenter;

import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.repository.ConfigItemRepository;
import com.example.configcenter.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class ConfigServiceTest {

    @Autowired
    ConfigService service;

    @Autowired
    ConfigItemRepository repo;

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
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
}