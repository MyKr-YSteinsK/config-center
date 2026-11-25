"""Exercise V4-to-V5, lost write responses, and MySQL replay across two real server processes."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import secrets
import tempfile
from urllib.parse import quote, urlencode

from identity_upgrade import private_mysql, rows, structure, versions, wait_until
from write_outcome import JAR, ROOT, TRACE, drop, request, server

APP = "plan07-proof"
CONFIGS = "/api/configs"
FLAGS = "/api/features"
SCOPE = urlencode({"app": APP, "env": "dev"})


@dataclass(frozen=True)
class Write:
    path: str
    number: str
    fields: dict

    def body(self):
        return json.dumps({"app": APP, "env": "dev", **self.fields}).encode()

    def headers(self, credential, trace):
        headers = {"Content-Type": "application/json", "X-API-Key": credential, TRACE: trace}
        if self.number:
            headers["Idempotency-Key"] = self.number
        return headers

    def send(self, port, credential, trace):
        reply = request(port, "POST", self.path, self.body(), self.headers(credential, trace))
        assert reply.trace == trace
        return reply

    def through_drop(self, port, credential, trace, forward):
        return drop(port, self.body(), self.headers(credential, trace), forward, self.path)


def config(name, value, number="", **fields):
    return Write(CONFIGS, number, {"key": name, "value": value, **fields})


def flag(name, percentage, number=""):
    return Write(FLAGS, number, {"name": name, "enabled": True, "rolloutPercentage": percentage,
                                 "allowlist": ["a+b", "测试🧪"]})


def succeeds(reply, version, replayed):
    data = reply.expect()
    assert (data["version"], reply.replayed) == (version, replayed)
    return data


def legacy_data(port, credential):
    old = config("v4-control", "same value", "baseline-repeat")
    first = succeeds(old.send(port, credential, "v4-first"), 1, None)
    second = succeeds(old.send(port, credential, "v4-duplicate"), 2, None)
    assert first["value"] == second["value"]
    flag("v4-flag", 10).send(port, credential, "v4-flag").expect()
    print("V4 control: identical numbered requests produced v1 and v2, no replay header.")


def business_structure(sql):
    return [row for row in structure(sql) if row[0] != "write_receipt"]


def read_effect(port, sql, domain, name, expected, effects):
    # The effect reader opens new HTTP and MySQL sessions, independently of the forwarding proxy.
    assert re.fullmatch(r"[a-z0-9-]+", name)
    is_config = domain == CONFIGS
    field = "key" if is_config else "name"
    query = f"{SCOPE}&{field}={quote(name)}"
    if is_config:
        current = request(port, "GET", f"{domain}/{name}?{SCOPE}").expect()
    else:
        current, = [item for item in request(port, "GET", f"{domain}?{SCOPE}").expect() if item["name"] == name]
    journal = request(port, "GET", f"{domain}/history?{query}").expect()
    payload = "value" if is_config else "rolloutPercentage"
    assert current["version"] == expected[0] and [entry["version"] for entry in journal] == expected
    assert current[payload] == effects[0] and [entry[payload] for entry in journal] == effects
    table, name_column = ("config_item", "config_key") if is_config else ("feature_flag", "name")
    payload_column = "config_value" if is_config else "rollout_percentage"
    select = (f"FROM {table} c WHERE c.app='{APP}' AND c.env='dev' "
              f"AND c.{name_column}='{name}'")
    assert sql.select(f"SELECT c.version,c.{payload_column} " + select) == [[str(expected[0]), str(effects[0])]]
    assert sql.select(f"SELECT h.version,h.{payload_column} FROM {table}_history h WHERE h.owner_id="
                      f"(SELECT c.id {select}) ORDER BY h.version DESC") == [
                          [str(version), str(effect)] for version, effect in zip(expected, effects)]
    print(f"Reader {field}={name}: independent HTTP/SQL current=v{current['version']} history={expected}",
          flush=True)


def receipt(sql, command):
    assert command.number
    number_hex = command.number.encode("ascii").hex()
    found = sql.select(f"SELECT operation,intent_version,snapshot_version,result_json FROM write_receipt "
                       f"WHERE app='{APP}' AND env='dev' AND request_key=X'{number_hex}'")
    if not found:
        return None
    (operation, intent_version, snapshot_version, snapshot), = found
    assert (intent_version, snapshot_version) == ("1", "1")
    return operation, json.loads(snapshot)


def counters(sql):
    tables = ("config_item", "config_item_history", "feature_flag", "feature_flag_history", "write_receipt")
    counts = [sql.select(f"SELECT COUNT(*) FROM {table}")[0][0] for table in tables]
    return counts + [sql.select(f"SELECT revision FROM config_namespace_revision WHERE app='{APP}' AND env='dev'")[0][0]]


def pending(port):
    result = request(port, "GET", "/actuator/metrics/config_center_watch_pending").body
    return int(result["measurements"][0]["value"])


def process_id(log):
    match = re.search(rb"with PID (\d+)", log.read_bytes())
    assert match
    return int(match[1])


def exercise(directory, baseline, candidate):
    with private_mysql(directory / "mysql") as (sql, env, _):
        credential, rotated = secrets.token_hex(20), secrets.token_hex(20)
        old_dir, first_dir, restarted_dir = (directory / name for name in ("v4", "first-v5", "restarted-v5"))
        for part in (old_dir, first_dir, restarted_dir):
            part.mkdir()

        with server("mysql", APP, credential, old_dir, env, jar=baseline) as port:
            legacy_data(port, credential)
            read_effect(port, sql, CONFIGS, "v4-control", [2, 1], ["same value", "same value"])
            read_effect(port, sql, FLAGS, "v4-flag", [1], [10])
        old_rows = rows(sql)
        old_schema = business_structure(sql)
        old_versions = versions(sql)
        assert [row[0] for row in old_versions] == ["1", "2", "3", "4"]

        config_intent = config("lost-config", "accepted", "config-lost", expectedVersion=1)
        flag_intent = Write(FLAGS + "/rollback", "flag-lost", {"name": "lost-flag", "targetVersion": 1})
        absent = config("never-arrived", "first attempt", "absent-request")
        twin = config("same-content", "identical", "identical-1")
        same_content = config("same-content", "identical", "identical-2")

        with server("mysql", APP, credential, first_dir, env, jar=candidate) as port:
            assert (rows(sql), business_structure(sql), versions(sql)[:4]) == (old_rows, old_schema, old_versions)
            assert versions(sql)[4][::2] == ["5", "1"]
            assert sql.select("SELECT COUNT(*) FROM write_receipt") == [["0"]]
            print("Real V4-to-V5: preserved five old tables, columns, indexes and migration identities; zero invented receipts.")
            config("lost-config", "seed").send(port, credential, "seed-config").expect()
            flag("lost-flag", 12).send(port, credential, "seed-flag").expect()
            flag("lost-flag", 67).send(port, credential, "advance-flag").expect()

            original_config = succeeds(config_intent.through_drop(port, credential, "config-dropped", True), 2, "false")
            original_flag = succeeds(flag_intent.through_drop(port, credential, "flag-dropped", True), 3, "false")
            assert receipt(sql, config_intent) == ("CONFIG_UPSERT", original_config)
            assert receipt(sql, flag_intent) == ("FEATURE_ROLLBACK", original_flag)
            read_effect(port, sql, CONFIGS, "lost-config", [2, 1], ["accepted", "seed"])
            read_effect(port, sql, FLAGS, "lost-flag", [3, 2, 1], [12, 67, 12])

            before_absent = counters(sql)
            assert absent.through_drop(port, credential, "zero-forwarded", False) is None
            assert receipt(sql, absent) is None and counters(sql) == before_absent
            assert request(port, "GET", f"{CONFIGS}/never-arrived?{SCOPE}").status == 404

            succeeds(config("lost-config", "later").send(port, credential, "later-config"), 3, None)
            succeeds(flag("lost-flag", 90).send(port, credential, "later-flag"), 4, None)
            original_twin = succeeds(twin.send(port, credential, "identical-first"), 1, "false")
            second_twin = succeeds(same_content.send(port, credential, "identical-second"), 2, "false")
            assert (original_twin["value"], second_twin["value"]) == ("identical", "identical")
            assert receipt(sql, twin) == ("CONFIG_UPSERT", original_twin)
            assert receipt(sql, same_content) == ("CONFIG_UPSERT", second_twin)
            before_restart = (rows(sql), counters(sql))

        with server("mysql", APP, rotated, restarted_dir, env, jar=candidate) as port:
            first_pid = process_id(first_dir / "server.log")
            restarted_pid = process_id(restarted_dir / "server.log")
            assert first_pid != restarted_pid
            assert (rows(sql), counters(sql)) == before_restart
            for intent in (config_intent, flag_intent):
                denied = intent.send(port, credential, "revoked-after-restart")
                assert denied.expect(403, 4031) is None and denied.replayed is None

            revision = int(before_restart[1][-1])
            assert pending(port) == 0
            with ThreadPoolExecutor(max_workers=1) as pool:
                watch = pool.submit(request, port, "GET",
                                    f"{CONFIGS}/watch?{SCOPE}&sinceVersion={revision}&timeoutSeconds=4",
                                    headers={TRACE: "replay-watch"})
                wait_until(lambda: pending(port) == 1, 3, "real JAR Watch registration")
                assert succeeds(config_intent.send(port, rotated, "config-replayed"), 2, "true") == original_config
                assert succeeds(flag_intent.send(port, rotated, "flag-replayed"), 3, "true") == original_flag
                assert succeeds(twin.send(port, rotated, "same-intent-replayed"), 1, "true") == original_twin
                assert pending(port) == 1 and not watch.done()
                unchanged = watch.result(timeout=7)
                assert unchanged.trace == "replay-watch"
                assert unchanged.expect() == {"changed": False, "latestVersion": revision}
            assert pending(port) == 0
            assert (rows(sql), counters(sql)) == before_restart
            read_effect(port, sql, CONFIGS, "lost-config", [3, 2, 1], ["later", "accepted", "seed"])
            read_effect(port, sql, FLAGS, "lost-flag", [4, 3, 2, 1], [90, 12, 67, 12])
            read_effect(port, sql, CONFIGS, "same-content", [2, 1], ["identical", "identical"])
            print(f"Different server PIDs {first_pid} -> {restarted_pid}; original snapshots replayed exactly, "
                  f"no added history or receipt, Watch unchanged at {revision}.")

            first_arrival = succeeds(absent.send(port, rotated, "first-real-arrival"), 1, "false")
            assert receipt(sql, absent) == ("CONFIG_UPSERT", first_arrival)
            assert succeeds(absent.send(port, rotated, "now-replayed"), 1, "true") == first_arrival
            read_effect(port, sql, CONFIGS, "never-arrived", [1], ["first attempt"])
            assert int(counters(sql)[-1]) == revision + 1
            print("Zero-forward control: retry executed once and bound its own receipt; a second retry only replayed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-jar", type=Path, required=True, help="V4 server JAR built from 5e13538")
    parser.add_argument("--candidate-jar", type=Path, default=JAR, help="current server JAR (defaults to Maven output)")
    args = parser.parse_args()
    baseline = args.baseline_jar.resolve()
    candidate = args.candidate_jar.resolve()
    if not baseline.is_file() or not candidate.is_file() or baseline == candidate:
        parser.error("Build separate V4 baseline and current JARs before running the rehearsal.")
    qa = ROOT / "docs/delete/Config-Plan07"
    qa.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="replay-", dir=qa))
    print(f"Baseline V4 JAR SHA-256={hashlib.sha256(baseline.read_bytes()).hexdigest()}", flush=True)
    print(f"Candidate V5 JAR SHA-256={hashlib.sha256(candidate.read_bytes()).hexdigest()}", flush=True)
    print(f"Private rehearsal artifacts: {directory}", flush=True)
    exercise(directory, baseline, candidate)


if __name__ == "__main__":
    main()
