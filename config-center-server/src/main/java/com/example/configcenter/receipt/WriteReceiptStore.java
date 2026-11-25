package com.example.configcenter.receipt;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;

@Component
class WriteReceiptStore {
    private final JdbcTemplate jdbc;

    public WriteReceiptStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(5);
    }

    Optional<Stored> find(WriteIntent intent, byte[] key) {
        try {
            return jdbc.query("""
                    SELECT operation, intent_version, intent_digest, snapshot_version, result_json
                    FROM write_receipt WHERE app=? AND env=? AND request_key=?
                    """, (rs, row) -> new Stored(rs.getString(1), rs.getShort(2), rs.getString(3),
                    rs.getShort(4), rs.getString(5)), intent.app(), intent.env(), key).stream().findFirst();
        } catch (DataAccessException failure) {
            throw accessFailure("read", failure);
        }
    }

    void claim(WriteIntent intent, byte[] key) {
        try {
            jdbc.update("""
                    INSERT INTO write_receipt
                        (app, env, request_key, operation, intent_version, intent_digest, snapshot_version, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                    """, intent.app(), intent.env(), key, intent.operation().name(),
                    WriteIntent.VERSION, intent.digest(), WriteReceiptCodec.VERSION);
        } catch (DuplicateKeyException duplicate) {
            // This statement supplies no generated ID and has exactly one caller-controlled unique key.
            throw new ClaimConflict("receipt unique constraint");
        } catch (PessimisticLockingFailureException | QueryTimeoutException contention) {
            throw new ClaimConflict("receipt claim lock or statement timeout");
        } catch (DataAccessException failure) {
            throw accessFailure("claim", failure);
        }
    }

    void complete(WriteIntent intent, byte[] key, String snapshot) {
        try {
            int updated = jdbc.update("""
                    UPDATE write_receipt SET result_json=?
                    WHERE app=? AND env=? AND request_key=? AND result_json IS NULL
                    """, snapshot, intent.app(), intent.env(), key);
            if (updated != 1) {
                throw new IllegalStateException("Expected one claimed write receipt");
            }
        } catch (DataAccessException failure) {
            throw accessFailure("complete", failure);
        }
    }

    private static IllegalStateException accessFailure(String action, DataAccessException failure) {
        String details = failure.getCause() instanceof SQLException sql
                ? " (SQLSTATE " + sql.getSQLState() + ", vendor code " + sql.getErrorCode() + ")" : "";
        return new IllegalStateException("Cannot " + action + " the write receipt" + details);
    }

    record Stored(String operation, short intentVersion, String digest, short snapshotVersion, String snapshot) {}

    static final class ClaimConflict extends RuntimeException {
        ClaimConflict(String kind) {
            super(kind);
        }
    }
}
