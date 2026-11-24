package com.example.configcenter.migration;

import com.example.configcenter.config.ApiKeyProperties;
import com.example.configcenter.migration.IdentityMigrationPlan.Assignment;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class V4__ExactNames extends BaseJavaMigration {

    private static final Logger LOG = LoggerFactory.getLogger(V4__ExactNames.class);
    private static final String EXACT = " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL";
    private final ApiKeyProperties authorization;
    private final String mappingFile;

    public V4__ExactNames(ApiKeyProperties authorization, String mappingFile) {
        this.authorization = authorization;
        this.mappingFile = mappingFile;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false; // MySQL implicitly commits ALTER TABLE.
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        var assignments = IdentityMigrationPlan.inspect(db, authorization)
                .resolve(IdentityMigrationPlan.readMappings(mappingFile));

        // Resolve the complete plan before the first schema change.
        execute(db, "ALTER TABLE config_item_history ADD COLUMN owner_id BIGINT NULL");
        execute(db, "ALTER TABLE feature_flag_history ADD COLUMN owner_id BIGINT NULL");
        attachHistory(db, assignments, "config_item_history");
        attachHistory(db, assignments, "feature_flag_history");

        moveScope(db, assignments, "config_item");
        moveScope(db, assignments, "feature_flag");
        moveScope(db, assignments, "config_namespace_revision");

        convertCurrentIdentity(db, "config_item", "config_key");
        convertCurrentIdentity(db, "feature_flag", "name");
        convertCurrentIdentity(db, "config_namespace_revision", null);

        finishHistory(db, "config_item_history", "idx_cfg_hist_app_env_key",
                "uk_cfg_hist_app_env_key_ver", "uk_cfg_hist_owner_ver");
        finishHistory(db, "feature_flag_history", "idx_ff_hist_app_env_name",
                "uk_ff_hist_app_env_name_ver", "uk_ff_hist_owner_ver");
    }

    private static void attachHistory(Connection db, List<Assignment> assignments, String table) throws SQLException {
        try (var update = db.prepareStatement("UPDATE " + table + " SET owner_id=? WHERE id=?")) {
            int pending = 0;
            for (Assignment group : assignments) {
                for (var row : group.rows()) {
                    if (!row.source().equals(table)) {
                        continue;
                    }
                    update.setLong(1, row.ownerId());
                    update.setLong(2, row.id());
                    update.addBatch();
                    if (++pending == 500) {
                        checkBatch(update.executeBatch());
                        pending = 0;
                    }
                }
            }
            if (pending > 0) {
                checkBatch(update.executeBatch());
            }
        }
    }

    private static void moveScope(Connection db, List<Assignment> assignments, String table) throws SQLException {
        try (var update = db.prepareStatement("UPDATE " + table + " SET app=?, env=? WHERE id=?")) {
            for (Assignment group : assignments) {
                for (var row : group.rows()) {
                    if (!row.source().equals(table) || row.scope().equals(group.target())) {
                        continue;
                    }
                    update.setString(1, group.target().app());
                    update.setString(2, group.target().env());
                    update.setLong(3, row.id());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("V4 scope update matched an unexpected row count in " + table);
                    }
                    LOG.info("V4 group {} moved {}#{} from {} to {}", group.fingerprint(),
                            table, row.id(), row.scope().diagnostic(), group.target().diagnostic());
                }
            }
        }
    }

    private static void checkBatch(int[] results) throws SQLException {
        for (int count : results) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new SQLException("V4 history attachment matched an unexpected row count");
            }
        }
    }

    private static void convertCurrentIdentity(Connection db, String table, String nameColumn) throws SQLException {
        String ddl = "ALTER TABLE " + table + " MODIFY COLUMN app VARCHAR(100)" + EXACT
                + ", MODIFY COLUMN env VARCHAR(50)" + EXACT;
        if (nameColumn != null) {
            ddl += ", MODIFY COLUMN " + nameColumn + " VARCHAR(200)" + EXACT;
        }
        execute(db, ddl);
    }

    private static void finishHistory(Connection db, String table, String oldLookup, String oldUnique, String exactUnique)
            throws SQLException {
        execute(db, "ALTER TABLE " + table + " MODIFY COLUMN owner_id BIGINT NOT NULL"
                + ", DROP INDEX " + oldLookup + ", DROP INDEX " + oldUnique
                + ", ADD CONSTRAINT " + exactUnique + " UNIQUE (owner_id, version)");
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
