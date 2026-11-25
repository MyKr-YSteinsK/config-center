package com.example.configcenter.receipt;

import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.RollbackFeatureRequest;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.request.UpsertFeatureRequest;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record WriteIntent(Operation operation, String app, String env, String objectName, String digest) {
    static final short VERSION = 1;

    public static WriteIntent of(UpsertConfigRequest req) {
        return create(Operation.CONFIG_UPSERT, req.getApp(), req.getEnv(), req.getKey(),
                req.getValue(), req.getDescription(), req.getExpectedVersion(), req.getOperator(), req.getReason());
    }

    public static WriteIntent of(RollbackConfigRequest req) {
        return create(Operation.CONFIG_ROLLBACK, req.getApp(), req.getEnv(), req.getKey(),
                req.getTargetVersion(), req.getOperator(), req.getReason());
    }

    public static WriteIntent of(UpsertFeatureRequest req) {
        return create(Operation.FEATURE_UPSERT, req.getApp(), req.getEnv(), req.getName(),
                req.getEnabled(), req.getRolloutPercentage(), req.getAllowlist(), req.getExpectedVersion(),
                req.getOperator(), req.getReason());
    }

    public static WriteIntent of(RollbackFeatureRequest req) {
        return create(Operation.FEATURE_ROLLBACK, req.getApp(), req.getEnv(), req.getName(),
                req.getTargetVersion(), req.getOperator(), req.getReason());
    }

    private static WriteIntent create(Operation operation, String app, String env, String objectName, Object... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                out.writeShort(VERSION);
                write(out, operation.name());
                write(out, app);
                write(out, env);
                write(out, objectName);
                for (Object field : fields) {
                    write(out, field);
                }
            }
            return new WriteIntent(operation, app, env, objectName, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot compute the write intent");
        }
    }

    private static void write(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(0);
        } else if (value instanceof String text) {
            out.writeByte(1);
            out.writeInt(text.length());
            out.writeChars(text); // Length and data are exact UTF-16 code units, including isolated surrogates.
        } else if (value instanceof Boolean bool) {
            out.writeByte(2);
            out.writeBoolean(bool);
        } else if (value instanceof Integer number) {
            out.writeByte(3);
            out.writeInt(number);
        } else if (value instanceof Long number) {
            out.writeByte(4);
            out.writeLong(number);
        } else if (value instanceof List<?> list) {
            out.writeByte(5);
            out.writeInt(list.size());
            for (Object entry : list) {
                write(out, entry);
            }
        } else {
            throw new IllegalArgumentException("Unsupported write intent field type");
        }
    }

    public enum Operation {
        CONFIG_UPSERT, CONFIG_ROLLBACK, FEATURE_UPSERT, FEATURE_ROLLBACK;

        boolean config() {
            return this == CONFIG_UPSERT || this == CONFIG_ROLLBACK;
        }
    }
}
