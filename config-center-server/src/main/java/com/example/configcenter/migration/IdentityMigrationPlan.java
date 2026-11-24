package com.example.configcenter.migration;

import com.example.configcenter.config.ApiKeyProperties;
import com.example.configcenter.validation.UnicodeNameValidator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IdentityMigrationPlan {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // All participating columns are verified to have V3's collation first. The joins and
    // DENSE_RANK use the old SQL equivalence, including PAD SPACE, rather than a Java approximation.
    private static final String MEMBERS = """
            WITH members AS (
                SELECT 'config_item' AS source, id AS row_id, app, env, config_key AS item_name,
                       version AS row_version, lock_version, NULL AS owner_id,
                       SHA2(JSON_ARRAY(config_value, description, CAST(updated_at AS CHAR)), 256) AS payload_hash
                FROM config_item
                UNION ALL
                SELECT 'feature_flag', id, app, env, name, version, lock_version, NULL,
                       SHA2(JSON_ARRAY(CAST(enabled AS UNSIGNED), rollout_percentage, allowlist_json,
                           CAST(updated_at AS CHAR)), 256)
                FROM feature_flag
                UNION ALL
                SELECT 'config_namespace_revision', id, app, env, NULL, revision, lock_version, NULL, NULL
                FROM config_namespace_revision
                UNION ALL
                SELECT 'config_item_history', h.id, h.app, h.env, h.config_key, h.version, NULL, c.id,
                       SHA2(JSON_ARRAY(h.config_value, h.description, h.action, h.operator, h.reason,
                           CAST(h.created_at AS CHAR)), 256)
                FROM config_item_history h LEFT JOIN config_item c
                    ON c.app = h.app AND c.env = h.env AND c.config_key = h.config_key
                UNION ALL
                SELECT 'feature_flag_history', h.id, h.app, h.env, h.name, h.version, NULL, f.id,
                       SHA2(JSON_ARRAY(CAST(h.enabled AS UNSIGNED), h.rollout_percentage, h.allowlist_json,
                           h.action, h.operator, h.reason, CAST(h.created_at AS CHAR)), 256)
                FROM feature_flag_history h LEFT JOIN feature_flag f
                    ON f.app = h.app AND f.env = h.env AND f.name = h.name
                UNION ALL
                SELECT 'grant', g.ordinal, CONVERT(UNHEX(g.app_hex) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
                       CONVERT(UNHEX(g.env_hex) USING utf8mb4) COLLATE utf8mb4_unicode_ci, NULL, NULL, NULL, NULL, NULL
                FROM JSON_TABLE(?, '$[*]' COLUMNS (
                    ordinal FOR ORDINALITY,
                    app_hex VARCHAR(800) PATH '$.appHex' ERROR ON EMPTY ERROR ON ERROR,
                    env_hex VARCHAR(400) PATH '$.envHex' ERROR ON EMPTY ERROR ON ERROR
                )) g
            )
            SELECT DENSE_RANK() OVER (ORDER BY app, env) AS namespace_group, source, row_id,
                   HEX(app) AS app_hex, HEX(env) AS env_hex, HEX(item_name) AS name_hex,
                   row_version, lock_version, owner_id, payload_hash
            FROM members
            ORDER BY namespace_group, source, row_id
            """;

    private static final Map<String, List<String>> KEY_COLUMNS = Map.of(
            "config_item", List.of("app", "env", "config_key"),
            "feature_flag", List.of("app", "env", "name"),
            "config_namespace_revision", List.of("app", "env"),
            "config_item_history", List.of("app", "env", "config_key"),
            "feature_flag_history", List.of("app", "env", "name"));

    private IdentityMigrationPlan() {}

    static Inspection inspect(Connection connection, ApiKeyProperties authorization) throws SQLException, IOException {
        requireV3Schema(connection);
        var scopes = authorization.getApiKeys().stream().map(item -> new Scope(item.getApp(), item.getEnv()))
                .distinct().sorted(Comparator.comparing(Scope::app).thenComparing(Scope::env)).toList();
        if (scopes.stream().anyMatch(scope -> !scope.valid())) {
            throw new IllegalStateException("V4 requires valid configured authorization scopes");
        }
        Map<Long, List<Row>> groups = new LinkedHashMap<>();
        try (var query = connection.prepareStatement(MEMBERS)) {
            query.setString(1, JSON.writeValueAsString(scopes.stream().map(Scope::diagnostic).toList()));
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    groups.computeIfAbsent(rs.getLong("namespace_group"), ignored -> new ArrayList<>()).add(read(rs));
                }
            }
        }
        List<Group> inspected = new ArrayList<>();
        for (List<Row> group : groups.values()) {
            inspected.add(group(group));
        }
        return new Inspection(List.copyOf(inspected));
    }

    static Map<String, Scope> readMappings(String file) throws IOException {
        if (file.isBlank()) {
            return Map.of();
        }
        var document = JSON.readValue(Path.of(file).toFile(), MappingDocument.class);
        if (document == null || document.mappings() == null) {
            throw new IllegalArgumentException("V4 mapping requires a mappings array");
        }
        Map<String, Scope> mappings = new LinkedHashMap<>();
        for (Mapping entry : document.mappings()) {
            if (entry == null || entry.fingerprint() == null || !entry.fingerprint().matches("[a-f0-9]{64}")
                    || entry.target() == null || !entry.target().valid()) {
                throw new IllegalArgumentException("V4 mapping needs a lowercase SHA-256 fingerprint and valid target app/env");
            }
            if (mappings.putIfAbsent(entry.fingerprint(), entry.target()) != null) {
                throw new IllegalArgumentException("V4 mapping repeats fingerprint " + entry.fingerprint());
            }
        }
        return mappings;
    }

    private static Group group(List<Row> members) throws IOException {
        List<Row> rows = members.stream().filter(row -> !row.source().equals("grant")).toList();
        List<Scope> persisted = rows.stream().map(Row::scope).distinct().toList();
        List<Scope> grants = members.stream().filter(row -> row.source().equals("grant")).map(Row::scope)
                .distinct().sorted(Comparator.comparing(Scope::app).thenComparing(Scope::env)).toList();

        String fingerprint = checksum(JSON.writeValueAsBytes(List.of(rows, grants)));
        List<String> problems = problems(rows);
        return new Group(fingerprint, persisted, grants, rows, problems);
    }

    private static List<String> problems(List<Row> rows) {
        List<String> problems = new ArrayList<>();
        Map<Long, Row> config = new HashMap<>();
        Map<Long, Row> flags = new HashMap<>();
        Set<OwnerVersion> versions = new HashSet<>();
        long revisions = rows.stream().filter(r -> r.source().equals("config_namespace_revision")).count();
        boolean hasConfig = rows.stream().anyMatch(r -> r.source().equals("config_item"));
        if (revisions != (hasConfig ? 1 : 0)) {
            problems.add("expected one positive revision for a populated configuration scope, found " + revisions);
        }
        Set<String> currentConfigNames = new HashSet<>();
        Set<String> currentFlagNames = new HashSet<>();
        for (Row row : rows) {
            if (!row.scope().valid() || row.id() <= 0 || row.version() < 1
                    || (row.lockVersion() != null && row.lockVersion() < 0)
                    || (row.nameHex() != null && !validText(decode(row.nameHex()), 200))) {
                problems.add("invalid identity/version in " + row.source() + "#" + row.id());
            }
            if (row.source().equals("config_item") && (!currentConfigNames.add(row.nameHex())
                    || config.putIfAbsent(row.id(), row) != null)) {
                problems.add("duplicate target configuration identity at id " + row.id());
            }
            if (row.source().equals("feature_flag") && (!currentFlagNames.add(row.nameHex())
                    || flags.putIfAbsent(row.id(), row) != null)) {
                problems.add("duplicate target flag identity at id " + row.id());
            }
        }
        for (Row row : rows) {
            if (row.source().endsWith("_history")) {
                var owner = (row.source().equals("config_item_history") ? config : flags).get(row.ownerId());
                if (owner == null) {
                    problems.add("orphan history " + row.source() + "#" + row.id());
                } else if (row.version() > owner.version()
                        || !versions.add(new OwnerVersion(row.source(), owner.id(), row.version()))) {
                    problems.add("invalid or duplicate owner/version in " + row.source() + "#" + row.id());
                }
            }
        }
        return List.copyOf(problems);
    }

    private static Row read(ResultSet rs) throws SQLException {
        var scope = new Scope(decode(rs.getString("app_hex")), decode(rs.getString("env_hex")));
        return new Row(rs.getString("source"), rs.getLong("row_id"), scope, rs.getString("name_hex"),
                rs.getLong("row_version"), rs.getObject("lock_version", Long.class),
                rs.getObject("owner_id", Long.class), rs.getString("payload_hash"));
    }

    private static void requireV3Schema(Connection connection) throws SQLException {
        try (var query = connection.createStatement();
             var rs = query.executeQuery("""
                     SELECT PAD_ATTRIBUTE, @@character_set_client, @@character_set_connection
                     FROM information_schema.collations
                     WHERE collation_name = 'utf8mb4_0900_bin' AND character_set_name = 'utf8mb4'
                     """)) {
            if (!rs.next() || !"NO PAD".equals(rs.getString(1)) || !"utf8mb4".equals(rs.getString(2))
                    || !"utf8mb4".equals(rs.getString(3))) {
                throw new IllegalStateException("V4 requires a utf8mb4 connection and MySQL utf8mb4_0900_bin NO PAD");
            }
        }
        Map<String, Set<String>> found = new HashMap<>();
        try (var query = connection.createStatement();
             var rs = query.executeQuery("""
                     SELECT table_name, column_name, data_type, character_maximum_length, collation_name, is_nullable
                     FROM information_schema.columns WHERE table_schema = DATABASE()
                     """)) {
            while (rs.next()) {
                String table = rs.getString(1);
                String column = rs.getString(2);
                var keys = KEY_COLUMNS.get(table);
                if (keys == null) {
                    continue;
                }
                if ("owner_id".equals(column)) {
                    throw new IllegalStateException("V4 requires intact V3 tables. Found owner_id in " + table);
                }
                if (!keys.contains(column)) {
                    continue;
                }
                int length = switch (column) {
                    case "app" -> 100;
                    case "env" -> 50;
                    default -> 200;
                };
                if (!"varchar".equals(rs.getString(3)) || rs.getInt(4) != length
                        || !"utf8mb4_unicode_ci".equals(rs.getString(5)) || !"NO".equals(rs.getString(6))) {
                    throw new IllegalStateException("V4 requires V3 identity column layout: " + table + "." + column);
                }
                found.computeIfAbsent(table, ignored -> new HashSet<>()).add(column);
            }
        }
        for (var table : KEY_COLUMNS.entrySet()) {
            if (!Set.copyOf(table.getValue()).equals(found.get(table.getKey()))) {
                throw new IllegalStateException("V4 requires intact V3 identity columns on " + table.getKey());
            }
        }
        requireV3Indexes(connection);
    }

    private static void requireV3Indexes(Connection connection) throws SQLException {
        Map<String, String> expected = Map.of(
                "config_item.uk_app_env_key", "0:app,env,config_key",
                "feature_flag.uk_app_env_name", "0:app,env,name",
                "config_namespace_revision.uk_config_namespace_app_env", "0:app,env",
                "config_item_history.uk_cfg_hist_app_env_key_ver", "0:app,env,config_key,version",
                "feature_flag_history.uk_ff_hist_app_env_name_ver", "0:app,env,name,version",
                "config_item_history.idx_cfg_hist_app_env_key", "1:app,env,config_key",
                "feature_flag_history.idx_ff_hist_app_env_name", "1:app,env,name");
        Map<String, String> actual = new HashMap<>();
        try (var query = connection.createStatement();
             var rs = query.executeQuery("""
                     SELECT table_name, index_name, non_unique,
                            GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_order
                     FROM information_schema.statistics WHERE table_schema = DATABASE()
                     GROUP BY table_name, index_name, non_unique
                     """)) {
            while (rs.next()) {
                actual.put(rs.getString(1) + "." + rs.getString(2), rs.getInt(3) + ":" + rs.getString(4));
            }
        }
        expected.forEach((index, columns) -> {
            if (!columns.equals(actual.get(index))) {
                throw new IllegalStateException("V4 requires V3 index " + index);
            }
        });
    }

    private static boolean validText(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length
                && UnicodeNameValidator.isWellFormed(value);
    }

    private static String decode(String hex) {
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }

    private static String hex(String value) {
        return HexFormat.of().withUpperCase().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record Scope(String app, String env) {
        boolean valid() {
            return validText(app, 100) && validText(env, 50);
        }

        Map<String, String> diagnostic() {
            return Map.of("app", app, "env", env, "appHex", hex(app), "envHex", hex(env));
        }
    }

    record Mapping(String fingerprint, Scope target) {}
    record MappingDocument(List<Mapping> mappings) {}
    record Row(String source, long id, Scope scope, String nameHex, long version, Long lockVersion, Long ownerId,
               String payloadHash) {
        Map<String, Object> diagnostic() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("table", source);
            fields.put("id", id);
            fields.put("scope", scope.diagnostic());
            fields.put("nameHex", nameHex);
            fields.put("version", version);
            fields.put("lockVersion", lockVersion);
            fields.put("ownerId", ownerId);
            return fields;
        }
    }

    private record OwnerVersion(String historyTable, long ownerId, long version) {}

    record Group(String fingerprint, List<Scope> persisted, List<Scope> grants, List<Row> rows, List<String> problems) {
        boolean needsMapping() {
            return !persisted.isEmpty() && (persisted.size() > 1 || grants.size() > 1
                    || (!grants.isEmpty() && !grants.contains(persisted.get(0))));
        }

        Map<String, Object> diagnostic() {
            return Map.of("fingerprint", fingerprint,
                    "persisted", persisted.stream().map(Scope::diagnostic).toList(),
                    "authorized", grants.stream().map(Scope::diagnostic).toList(),
                    "needsMapping", needsMapping(), "problems", problems,
                    "rows", rows.stream().map(Row::diagnostic).toList());
        }

        String reference() {
            return fingerprint + " persisted=" + persisted.stream().map(Scope::diagnostic).toList()
                    + " authorized=" + grants.stream().map(Scope::diagnostic).toList();
        }
    }

    record Assignment(String fingerprint, Scope target, List<Row> rows) {}

    record Inspection(List<Group> groups) {
        List<Assignment> resolve(Map<String, Scope> mappings) {
            var unused = new LinkedHashSet<>(mappings.keySet());
            List<Assignment> assignments = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            Set<Scope> assigned = new HashSet<>();
            for (Group group : groups) {
                if (group.persisted().isEmpty()) {
                    continue;
                }
                if (!group.problems().isEmpty()) {
                    issues.add(group.reference() + " " + group.problems());
                    continue;
                }
                Scope target;
                if (group.needsMapping()) {
                    unused.remove(group.fingerprint());
                    target = mappings.get(group.fingerprint());
                    if (target == null) {
                        issues.add("explicit mapping required: " + group.reference());
                        continue;
                    }
                    if (!group.persisted().contains(target) || !group.grants().contains(target)) {
                        issues.add("target must be both observed in this old group and exactly authorized: " + group.reference());
                        continue;
                    }
                } else {
                    target = group.persisted().get(0);
                }
                if (!assigned.add(target)) {
                    issues.add("multiple legacy groups mapped to " + target.diagnostic());
                } else {
                    assignments.add(new Assignment(group.fingerprint(), target, group.rows()));
                }
            }
            if (!unused.isEmpty()) {
                issues.add("unused or stale fingerprints " + unused);
            }
            if (!issues.isEmpty()) {
                throw new IllegalStateException("V4 identity preflight refused before business changes: " + String.join("; ", issues));
            }
            return assignments;
        }
    }
}
