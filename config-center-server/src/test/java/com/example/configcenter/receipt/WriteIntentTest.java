package com.example.configcenter.receipt;

import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.RollbackFeatureRequest;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.request.UpsertFeatureRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WriteIntentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void configUpsertVectorAndEveryField() throws Exception {
        vector("""
                {"app":"Demo","env":"dev ","key":"café","value":"v😀","description":"",
                 "expectedVersion":9,"operator":"writer","reason":null}
                """, UpsertConfigRequest.class, WriteIntent::of,
                "e2ce9043e3252e7542fdfba8d6d67fd81d6951a4906db1de5abfd43756ddc3df",
                Map.of("app", "demo", "env", "dev", "key", "cafe\u0301", "value", "v",
                        "description", "desc", "expectedVersion", 8L, "operator", "other", "reason", ""));
    }

    @Test
    void configRollbackVectorAndEveryField() throws Exception {
        vector("""
                {"app":"Demo","env":"dev ","key":"café","targetVersion":41,"operator":"writer","reason":"原因"}
                """, RollbackConfigRequest.class, WriteIntent::of,
                "3ce9b185089d984489edb560d0a8b5d12523ee50351aaccb17198963f3090998",
                Map.of("app", "demo", "env", "dev", "key", "Cafe", "targetVersion", 1L, "operator", "", "reason", ""));
    }

    @Test
    void featureUpsertVectorAndEveryField() throws Exception {
        vector("""
                {"app":"Demo","env":"dev ","name":"café","enabled":true,"rolloutPercentage":17,
                 "allowlist":["b","a","b"],"expectedVersion":null,"operator":"writer","reason":""}
                """, UpsertFeatureRequest.class, WriteIntent::of,
                "6874a2c1edf1976eebc3b70549b714f52080e627f0456b234790d5f70e5f33fa",
                Map.of("app", "demo", "env", "dev", "name", "Cafe", "enabled", false, "rolloutPercentage", 18,
                        "allowlist", List.of("a", "b", "b"), "expectedVersion", 1L, "operator", "other", "reason", "c"));
    }

    @Test
    void featureRollbackVectorAndEveryField() throws Exception {
        vector("""
                {"app":"Demo","env":"dev ","name":"café","targetVersion":41,"operator":"writer","reason":"原因"}
                """, RollbackFeatureRequest.class, WriteIntent::of,
                "55c470e028968160949823454922126895808a4306e272428b34384c6c292345",
                Map.of("app", "demo", "env", "dev", "name", "Cafe", "targetVersion", 1L, "operator", "", "reason", ""));
    }

    @Test
    void nullableStringsListsAndInvalidUtf16StayDistinct() {
        var req = new UpsertConfigRequest();
        String missing = WriteIntent.of(req).digest();
        req.setDescription("");
        assertNotEquals(missing, WriteIntent.of(req).digest());
        req.setValue("\ud800");
        String unpaired = WriteIntent.of(req).digest();
        req.setValue("\ud801");
        assertNotEquals(unpaired, WriteIntent.of(req).digest());
        req.setValue("?");
        assertNotEquals(unpaired, WriteIntent.of(req).digest());

        var feature = new UpsertFeatureRequest();
        String absentList = WriteIntent.of(feature).digest();
        feature.setAllowlist(List.of());
        assertNotEquals(absentList, WriteIntent.of(feature).digest());
        feature.setAllowlist(List.of("ab", "c"));
        String first = WriteIntent.of(feature).digest();
        feature.setAllowlist(List.of("a", "bc"));
        assertNotEquals(first, WriteIntent.of(feature).digest());
        feature.setAllowlist(List.of("ab", "c", "c"));
        assertNotEquals(first, WriteIntent.of(feature).digest());
    }

    private <T> void vector(String body, Class<T> type, Function<T, WriteIntent> intent,
                            String digest, Map<String, ?> changes) throws Exception {
        var original = (ObjectNode) json.readTree(body);
        assertEquals(digest, intent.apply(json.treeToValue(original, type)).digest());
        for (var field : changes.entrySet()) {
            var changed = original.deepCopy();
            changed.set(field.getKey(), json.valueToTree(field.getValue()));
            assertNotEquals(digest, intent.apply(json.treeToValue(changed, type)).digest(), field.getKey());
        }
    }
}
