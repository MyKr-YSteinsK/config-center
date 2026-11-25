"""Compare two real write chains and replay the baseline's receipts in its original V5 database."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime
import hashlib
import http.client
import json
from pathlib import Path
import re
from struct import pack
import tempfile

from identity_upgrade import (ORIGINAL, TABLES, TARGET, Jars, backup, check_preserved, exercise_cli, get,
                              private_mysql, query, restore, rows, seed_v3, structure, wait_until)
from write_outcome import JAR, ROOT, TRACE

CONFIG = "/api/configs"
FLAG = "/api/features"
SCOPE = ("write-chain-08", "dév")
OTHER = ("Write-chain-08", "dév")
SCOPES = (ORIGINAL, TARGET, SCOPE, OTHER)
KEY = "Idempotency-Key"
REPLAYED = "Idempotency-Replayed"


def exchange(port, path, label, payload=None, headers=None, numbers=()):
    trace = "plan08-" + label
    with closing(http.client.HTTPConnection("127.0.0.1", port, timeout=12)) as conn:
        conn.putrequest("GET" if payload is None else "POST", path)
        conn.putheader(TRACE, trace)
        for name, value in (headers or {}).items():
            conn.putheader(name, value)
        for number in numbers:
            conn.putheader(KEY, number)
        body = json.dumps(payload).encode() if payload is not None else None
        if body is not None:
            conn.putheader("Content-Type", "application/json")
            conn.putheader("Content-Length", str(len(body)))
        conn.endheaders(body)
        response = conn.getresponse()
        raw = response.read().decode("utf-8")
        assert response.getheader(TRACE) == trace, label
        parsed = json.loads(raw) if raw else None
        if parsed is not None and "traceId" in parsed:
            assert parsed["traceId"] == trace, label
        return response.status, parsed, {name: response.getheader(name) for name in
                                        (TRACE, "Content-Type", "ETag", REPLAYED)}, raw


def data_bytes(raw):
    start = re.search(r'"data"\s*:\s*', raw).end()
    _, length = json.JSONDecoder().raw_decode(raw[start:])
    return raw[start:start + length].encode("utf-8")


def version_rows(sql):
    return sql.select("SELECT * FROM flyway_schema_history ORDER BY installed_rank")


def stored_rows(sql):
    # MySQL's raw client would print bits and binary indexes as arbitrary bytes.
    snapshot = {}
    for table in (*TABLES, "write_receipt"):
        columns = [row[0] for row in sql.select(
            "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() "
            f"AND table_name='{table}' ORDER BY ordinal_position")]
        expressions = ["HEX(request_key)" if c == "request_key" else "CAST(enabled AS UNSIGNED)"
                       if c == "enabled" else c for c in columns]
        snapshot[table] = [dict(zip(columns, entry, strict=True)) for entry in sql.select(
            f"SELECT {','.join(expressions)} FROM {table} ORDER BY id")]
    return snapshot


def instant_token(value):
    datetime.fromisoformat(value)
    return "<instant>"


def normalized(value):
    if isinstance(value, list):
        return [normalized(item) for item in value]
    if isinstance(value, dict):
        return {key: instant_token(field) if key in ("updatedAt", "createdAt") else normalized(field)
                for key, field in value.items()}
    return value


def comparable_rows(snapshot):
    result = {}
    for table, data in snapshot.items():
        entries, previous = [], {}
        for row in data:
            entry = dict(row)
            # Legacy rows originate from the same dump. Compare their original bytes and times.
            if (row["app"], row["env"]) in (SCOPE, OTHER):
                for column in ("created_at", "updated_at"):
                    if column in row:
                        instant = row[column]
                        series = row.get("owner_id", table)
                        earlier = previous.get(series, instant)
                        assert datetime.fromisoformat(instant) >= datetime.fromisoformat(earlier), (table, series)
                        previous[series] = instant
                        entry[column] = instant_token(instant)
                if table == "write_receipt":
                    original = row["result_json"]
                    timestamp = json.loads(original)["updatedAt"]
                    fragment = f'"updatedAt":"{timestamp}"'
                    assert original.count(fragment) == 1
                    entry["result_json"] = original.replace(
                        fragment, f'"updatedAt":"{instant_token(timestamp)}"', 1)
            entries.append(entry)
        result[table] = entries
    return result


def list_etag(data):
    fields = [str(row[key]) if key == "version" else row[key] for row in data
              for key in ("app", "env", "key", "value", "description", "version", "updatedAt")]
    digest = hashlib.sha256(pack(">i", len(fields)))
    for value in fields:
        part = value.encode() if value is not None else b""
        digest.update(pack(">i", len(part) if value is not None else -1) + part)
    return f'W/"{digest.hexdigest()}"'


class Scenario:
    def __init__(self, port, grants):
        self.port, self.grants = port, grants
        self.transcript, self.successes = {}, []

    def call(self, label, path, *, status=200, code=0, replay=None, payload=None, headers=None, numbers=()):
        actual, body, current, raw = exchange(self.port, path, label, payload, headers, numbers)
        assert (actual, current[REPLAYED]) == (status, replay), (label, actual, current, body)
        if status == 304:
            assert raw == ""
        else:
            assert body["code"] == code and set(body) == {"code", "message", "data", "traceId"}, (label, body)
        compared = dict(current)
        if current["ETag"] is not None:
            assert re.fullmatch(r'W/"[a-f0-9]{64}"', current["ETag"]), label
            if status == 200:
                assert current["ETag"] == list_etag(body["data"])
            else:
                assert current["ETag"] == headers["If-None-Match"]
            compared["ETag"] = "weak SHA-256 validated on this snapshot"
        self.transcript[label] = [actual, normalized(body), compared]
        return (body["data"] if body else None), raw, current

    def write(self, label, path, fields, *, scope=SCOPE, grant=None, number=None,
              status=200, code=0, replay=None, malformed=()):
        payload = {"app": scope[0], "env": scope[1], **fields}
        headers = {"X-API-Key": self.grants[grant or scope]}
        numbers = (number,) if number is not None else malformed
        data, raw, _ = self.call(label, path, status=status, code=code, replay=replay, payload=payload,
                                 headers=headers, numbers=numbers)
        if replay == "false":
            self.successes.append((path, payload, headers, numbers, data_bytes(raw)))
        return data

    def read(self, label, path, *, scope=SCOPE, **fields):
        return self.call(label, path + query(scope, **fields))[0]

    def watch(self, label, since, timeout, expected):
        assert self.read(label, CONFIG + "/watch", sinceVersion=since, timeoutSeconds=timeout) == expected

    def pending(self):
        _, body, _, _ = exchange(self.port, "/actuator/metrics/config_center_watch_pending", "waiter-poll")
        return int(body["measurements"][0]["value"])

    def replay_saved(self, saved):
        for index, (path, payload, headers, numbers, original) in enumerate(saved):
            _, raw, _ = self.call(f"replayed-{index + 1}", path, payload=payload, headers=headers,
                                  numbers=numbers, replay="true")
            assert data_bytes(raw) == original, path


def exercise(port, grants, sql):
    case = Scenario(port, grants)
    config = {"key": "prefs", "value": "青🧪", "description": None}
    disabled = {"name": "rollout", "enabled": False, "rolloutPercentage": 0, "allowlist": ["a+b"]}
    cfg_v1 = case.write("config-create", CONFIG, {**config, "expectedVersion": 7})
    flag_v1 = case.write("flag-create", FLAG, {**disabled, "expectedVersion": 7})
    assert cfg_v1["version"] == flag_v1["version"] == 1
    assert not case.read("off", FLAG + "/evaluate", name="rollout", userId="a+b")["enabled"]

    config_v2 = {**config, "value": "next", "description": "new", "expectedVersion": 1}
    with ThreadPoolExecutor(max_workers=1) as pool:
        watch = pool.submit(case.watch, "watch-change", 1, 8, {"changed": True, "latestVersion": 2})
        wait_until(lambda: case.pending() == 1, 5, "registered Watch")
        updated = case.write("config-numbered-update", CONFIG, config_v2, number="update", replay="false")
        watch.result(timeout=10)
    assert updated["version"] == 2 and case.pending() == 0

    prior = stored_rows(sql)
    case.write("version-conflict", CONFIG, config_v2, number="new-stale", status=409, code=4091)
    case.write("number-conflict", CONFIG, {**config_v2, "reason": "different"}, number="update", status=409, code=4091)
    case.write("auth-before-header", CONFIG, config, grant=OTHER, malformed=("bad key",), status=403, code=4031)
    case.write("dto-before-auth", CONFIG, {"key": "prefs"}, grant=OTHER, malformed=("one", "two"),
               status=400, code=4001)
    case.write("duplicate-header", FLAG, disabled, malformed=("one", "two"), status=400, code=4001)
    case.write("bad-header", CONFIG, config, malformed=("",), status=400, code=4001)
    case.write("rollback-missing", FLAG + "/rollback", {"name": "rollout", "targetVersion": 42},
               number="missing", status=404, code=4041)
    case.call("exact-key-absent", CONFIG + "/Prefs" + query(SCOPE), status=404, code=4041)
    case.call("exact-name-absent", FLAG + "/evaluate" + query(SCOPE, name="Rollout", userId="a+b"), status=404, code=4041)
    assert case.read("other-scope-empty", CONFIG, scope=OTHER) == []
    assert stored_rows(sql) == prior

    cfg_v3 = case.write("config-numbered-rollback", CONFIG + "/rollback",
                        {"key": "prefs", "targetVersion": 1, "reason": "", "operator": "fixture"},
                        number="restore-config", replay="false")
    rules = {**disabled, "enabled": True, "rolloutPercentage": 37, "expectedVersion": 1}
    flag_v2 = case.write("flag-numbered-update", FLAG, rules, number="update-flag", replay="false")
    assert case.read("allowlist", FLAG + "/evaluate", name="rollout", userId="a+b")["bucket"] == -1
    for i in range(2):
        decision = case.read(f"bucket-{i}", FLAG + "/evaluate", name="rollout", userId="sample")
        expected_bucket = (int.from_bytes(hashlib.sha256(b"sample:rollout").digest()[:4], "big") & 0x7fffffff) % 100
        assert decision["bucket"] == expected_bucket and decision["enabled"] == (expected_bucket < 37)
    flag_v3 = case.write("flag-numbered-rollback", FLAG + "/rollback",
                         {"name": "rollout", "targetVersion": 1, "reason": "   ", "operator": "fixture"},
                         number="restore-flag", replay="false")
    assert (cfg_v3["version"], cfg_v3["value"], flag_v2["version"], flag_v3["version"], flag_v3["enabled"]) == (
        3, "青🧪", 2, 3, False)
    before_replay = stored_rows(sql)
    with ThreadPoolExecutor(max_workers=1) as pool:
        quiet = pool.submit(case.watch, "watch-replay", 3, 3, {"changed": False, "latestVersion": 3})
        wait_until(lambda: case.pending() == 1, 2, "registered replay Watch")
        case.replay_saved(case.successes)
        quiet.result(timeout=5)
    assert stored_rows(sql) == before_replay and case.pending() == 0

    case.write("other-scope-write", CONFIG, {**config, "key": "Prefs"}, scope=OTHER)
    assert (case.read("case-separated", CONFIG + "/Prefs", scope=OTHER)["version"],
            case.read("current-config", CONFIG + "/prefs")["version"],
            case.read("current-flag", FLAG)[0]["version"]) == (1, 3, 3)
    for path, field, name, suffix in ((CONFIG, "key", "prefs", ""), (FLAG, "name", "rollout", "   ")):
        page = case.read(field + "-history", path + "/history", **{field: name, "limit": 2})
        last = case.read(field + "-older", path + "/history", **{field: name, "beforeVersion": 2})
        assert [r["version"] for r in page + last] == [3, 2, 1]
        assert page[0]["reason"] == "rollback-to=1, " + suffix
        times = [datetime.fromisoformat(r["createdAt"]) for r in page + last]
        assert times == sorted(times, reverse=True)
    listing = CONFIG + query(SCOPE)
    _, _, headers = case.call("list-with-etag", listing)
    case.call("conditional", listing, status=304, headers={"If-None-Match": headers["ETag"]})
    status, schema, _, _ = exchange(port, "/v3/api-docs", "openapi")
    assert status == 200
    for server in schema["servers"]:
        server["url"] = server["url"].replace(f"127.0.0.1:{port}", "127.0.0.1:<port>")
    case.transcript["openapi"] = schema
    final = stored_rows(sql)
    assert len(case.successes) == len(final["write_receipt"]) == 4
    for _, _, _, (number,), response in case.successes:
        saved, = [r for r in final["write_receipt"] if r["request_key"] == number.encode().hex().upper()]
        assert json.loads(saved["result_json"]) == json.loads(response)
    print(f"HTTP/OpenAPI sequence sampled {len(case.transcript)} cases, including four raw replays.", flush=True)
    return case.transcript, comparable_rows(final), case.successes


def legacy_setup(jars, sql, env, legacy, baseline, output):
    with jars.serving(legacy, env, "legacy-v3") as port:
        assert [row[1] for row in version_rows(sql)] == ["1", "2", "3"]
        seed_v3(jars, port)
        prior_response = get(port, CONFIG, ORIGINAL).body
    code, report = jars.command(env, "v4-preflight", ("--check-identity-migration",), jar=baseline)
    assert code != 0 and "explicit mapping required" in report
    groups = [json.loads(line.removeprefix("IDENTITY_V4_GROUP "))
              for line in report.splitlines() if line.startswith("IDENTITY_V4_GROUP ")]
    group, = [g for g in groups if g["needsMapping"]]
    path = output / "synthetic-mapping.json"
    path.write_text(json.dumps({"mappings": [{"fingerprint": group["fingerprint"],
                                             "target": dict(zip(("app", "env"), TARGET))}]}), encoding="utf-8")
    return rows(sql), sql.select("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank"), (
        f"--config-center.identity-mapping-file={path}",), prior_response


def same_database(jars, sql, env, candidate, saved, before, startup_state, output, previous_response):
    with jars.serving(candidate, env, "candidate-on-old-v5") as port:
        assert (stored_rows(sql), structure(sql), version_rows(sql)) == (before, *startup_state)
        exercise_cli(port, output, env, previous_response)
        case = Scenario(port, jars.keys)
        with ThreadPoolExecutor(max_workers=1) as pool:
            quiet = pool.submit(case.watch, "switch-watch", 3, 3, {"changed": False, "latestVersion": 3})
            wait_until(lambda: case.pending() == 1, 2, "registered switch Watch")
            case.replay_saved(saved)
            quiet.result(timeout=5)
        assert (stored_rows(sql), structure(sql), version_rows(sql)) == (before, *startup_state)
        print("Stopped the baseline writer, switched on the same V5 data: four byte-identical data objects, "
              "all rows, indexes and Flyway records unchanged; Watch remained at revision 3.", flush=True)
        for path, field in ((CONFIG, "key"), (FLAG, "name")):
            legacy = case.read(field + "-migrated", path + "/history", scope=TARGET, **{field: "Cafe"})
            assert [(r["app"], r["env"], r[field]) for r in legacy if r["version"] == 1] == [(*ORIGINAL, "cafe")]
            restored = case.write(field + "-legacy-rollback", path + "/rollback",
                                  {field: "Cafe", "targetVersion": 1}, scope=TARGET)
            assert restored["version"] == 4
            if path == CONFIG:
                assert restored["value"] == "first"
            else:
                assert restored["allowlist"] == ["a+b"]
        new = case.write("new-after-switch", CONFIG, {"key": "prefs", "value": "new after replay", "expectedVersion": 3},
                         number="next-after-switch", replay="false")
        assert new["version"] == 4
        assert len(stored_rows(sql)["write_receipt"]) == 5 and version_rows(sql) == startup_state[1]
        print("The candidate accepted a new numbered write and rolled back both migrated legacy objects.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-jar", required=True, type=Path)
    parser.add_argument("--legacy-jar", required=True, type=Path, help="V3 server JAR from 52e887d")
    parser.add_argument("--candidate-jar", default=JAR, type=Path)
    args = parser.parse_args()
    baseline, legacy, candidate = (path.resolve() for path in (args.baseline_jar, args.legacy_jar, args.candidate_jar))
    if len({baseline, legacy, candidate}) != 3 or not all(path.is_file() for path in (baseline, legacy, candidate)):
        parser.error("Build three separate server artifacts first.")
    qa = ROOT / "docs/delete/Config-Plan08"
    qa.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="comparison-", dir=qa))
    print(f"Artifacts: {output}", flush=True)
    for label, jar in (("baseline V5", baseline), ("legacy V3", legacy), ("candidate V5", candidate)):
        print(f"{label} SHA-256={hashlib.sha256(jar.read_bytes()).hexdigest()}", flush=True)
    jars = Jars(legacy, output, SCOPES)
    dump = output / "initial-v5.sql"
    with private_mysql(output / "same-v5") as (sql, env, _):
        old_rows, old_versions, mapping, old_list = legacy_setup(jars, sql, env, legacy, baseline, output)
        with jars.serving(baseline, env, "baseline-v5", mapping) as port:
            check_preserved(sql, old_rows, old_versions)
            initial = stored_rows(sql)
            assert not initial["write_receipt"]
            startup_state = structure(sql), version_rows(sql)
            backup(sql, dump)
            transcript, comparable, saved = exercise(port, jars.keys, sql)
            before = stored_rows(sql)
        same_database(jars, sql, env, candidate, saved, before, startup_state, output, old_list)
    with private_mysql(output / "separate-v5") as (sql, env, _):
        restore(sql, dump)
        assert (stored_rows(sql), structure(sql), version_rows(sql)) == (initial, *startup_state)
        with jars.serving(candidate, env, "candidate-v5") as port:
            after, compared, _ = exercise(port, jars.keys, sql)
            assert (structure(sql), version_rows(sql)) == startup_state
    for label, http, database in (("baseline", transcript, comparable), ("candidate", after, compared)):
        (output / f"{label}.json").write_text(json.dumps(
            {"http": http, "database": database}, ensure_ascii=False, indent=2), encoding="utf-8")
    assert after == transcript, [key for key in transcript if after.get(key) != transcript[key]]
    assert compared == comparable, [table for table in comparable if compared.get(table) != comparable[table]]
    print(f"{len(after)} HTTP/OpenAPI cases and six tables matched, including original ownership, "
          "business versions, lock versions, digests and receipt snapshots apart from new instants.")


if __name__ == "__main__":
    main()
