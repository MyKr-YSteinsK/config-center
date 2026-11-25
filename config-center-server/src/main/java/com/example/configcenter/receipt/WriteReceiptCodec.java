package com.example.configcenter.receipt;

import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.dto.response.FeatureFlagDto;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
class WriteReceiptCodec {
    static final short VERSION = 1;
    private static final int MAX_JSON_LENGTH = 60_000; // ASCII JSON fits within MySQL TEXT.
    private final ObjectWriter writer;
    private final ObjectReader reader;

    WriteReceiptCodec(ObjectMapper mapper) {
        writer = mapper.writer()
                .without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .with(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature());
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    String encode(Object data) {
        try {
            String json = writer.writeValueAsString(data);
            if (json.length() <= MAX_JSON_LENGTH) {
                return json;
            }
        } catch (JsonProcessingException failure) {
            // Codec exceptions can contain parts of the private payload.
            throw new IllegalStateException("Cannot encode a write receipt");
        }
        throw new IllegalStateException("Write receipt exceeds snapshot capacity");
    }

    <T> T decode(String json, WriteIntent intent, Class<T> type) {
        try {
            require(json != null && json.length() <= MAX_JSON_LENGTH);
            JsonNode root = reader.readTree(json);
            boolean config = intent.operation().config();
            require(root.isObject() && root.size() == (config ? 7 : 8));
            require(intent.app().equals(text(root.path("app"))) && intent.env().equals(text(root.path("env"))));
            require(intent.objectName().equals(text(root.path(config ? "key" : "name"))));
            var version = root.path("version");
            require(version.isIntegralNumber() && version.canConvertToLong());
            Instant updatedAt = Instant.parse(text(root.path("updatedAt")));
            if (config) {
                JsonNode description = root.path("description");
                return type.cast(new ConfigItemDto(intent.app(), intent.env(), intent.objectName(),
                        text(root.path("value")), description.isNull() ? null : text(description),
                        version.longValue(), updatedAt));
            }
            var enabled = root.path("enabled");
            var rollout = root.path("rolloutPercentage");
            require(enabled.isBoolean() && rollout.isIntegralNumber() && rollout.canConvertToInt());
            return type.cast(new FeatureFlagDto(intent.app(), intent.env(), intent.objectName(), enabled.booleanValue(),
                    rollout.intValue(), list(root.path("allowlist")), updatedAt, version.longValue()));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalStateException("Invalid write receipt snapshot");
        }
    }

    private static List<String> list(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        require(node.isArray());
        List<String> values = new ArrayList<>(node.size());
        node.forEach(entry -> values.add(text(entry)));
        return values;
    }

    private static String text(JsonNode node) {
        require(node.isTextual());
        return node.textValue();
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Unexpected snapshot field");
        }
    }
}
