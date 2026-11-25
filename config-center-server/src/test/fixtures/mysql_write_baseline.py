"""Compare concentrated and spread config updates on an owned native MySQL instance."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, closing
from dataclasses import dataclass
import hashlib
import http.client
import json
import math
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit

from identity_upgrade import Jars, private_mysql
from write_outcome import CONFIGS, JAR, ROOT


WORKERS = 8
WRITES = 200
WARM_WRITES = 20
SMOKE_WRITES = 3
PAIRS = 4
VALUE_BYTES = 256
REQUEST_TIMEOUT = 15
STRIPE_COUNT = 64
SCOPES = tuple((f"plan11-ns{i:02d}", "bench") for i in range(WORKERS + 1))
KEYS = tuple(f"key-{i:02d}" for i in range(WORKERS))
GROUPS = {
    "concentrated": tuple((*SCOPES[0], key) for key in KEYS),
    "spread": tuple((*scope, key) for scope, key in zip(SCOPES[1:], KEYS, strict=True)),
}


def lock_stripe(scope):
    # Objects.hash of the two ASCII strings, as used by NamespaceRevisionLock.
    # Java's int overflow does not change the residue modulo 64.
    result = 1
    for part in scope:
        string_hash = 0
        for char in part.encode("ascii"):
            string_hash = 31 * string_hash + char
        result = 31 * result + string_hash
    return result % STRIPE_COUNT


def value_at(phase, ordinal):
    return f"{phase}-{ordinal:06d}".ljust(VALUE_BYTES, "x")


def payload(target, value):
    app, env, key = target
    return json.dumps(dict(app=app, env=env, key=key, value=value), separators=(",", ":")).encode("ascii")


@dataclass(frozen=True)
class Sample:
    started: int
    finished: int
    outcome: str
    version: int | None = None


def post(connection, target, credential, value, body):
    headers = {"X-API-Key": credential, "Content-Type": "application/json"}
    started = time.perf_counter_ns()
    try:
        connection.request("POST", CONFIGS, body, headers)
        response = connection.getresponse()
        raw = response.read()
    except (OSError, http.client.HTTPException) as error:
        finished = time.perf_counter_ns()
        connection.close()
        return Sample(started, finished, f"transport:{type(error).__name__}")
    finished = time.perf_counter_ns()
    if response.status != 200:
        return Sample(started, finished, f"http:{response.status}")
    try:
        envelope = json.loads(raw)
        data = envelope["data"]
        if (envelope["code"] == 0 and (data["app"], data["env"], data["key"]) == target
                and data["value"] == value and type(data["version"]) is int):
            return Sample(started, finished, "ok", data["version"])
    except (ValueError, KeyError, TypeError):
        pass
    return Sample(started, finished, "invalid-success-response")


def exercise(port, grants, group, phase, writes):
    targets = GROUPS[group]
    values = [value_at(phase, ordinal) for ordinal in range(1, writes + 1)]
    gate = threading.Barrier(WORKERS)

    def worker(connection, target, bodies):
        token = grants[target[:2]]
        gate.wait(timeout=15)
        return [post(connection, target, token, value, body)
                for value, body in zip(values, bodies, strict=True)]

    with ExitStack() as sockets, ThreadPoolExecutor(max_workers=WORKERS) as pool:
        inputs = []
        for target in targets:
            connection = sockets.enter_context(closing(http.client.HTTPConnection(
                "127.0.0.1", port, timeout=REQUEST_TIMEOUT)))
            connection.connect()
            inputs.append((connection, target, [payload(target, value) for value in values]))
        jobs = [pool.submit(worker, *args) for args in inputs]
        return [job.result() for job in jobs]


@dataclass
class State:
    current: dict
    history: list
    revisions: dict
    receipts: int


def read_state(sql):
    current = {tuple(row[:3]): (row[3], int(row[4]), int(row[5]), row[6]) for row in sql.select(
        "SELECT app,env,config_key,id,version,lock_version,config_value FROM config_item")}
    history = [(tuple(row[:3]), int(row[3]), *row[4:]) for row in sql.select(
        "SELECT app,env,config_key,version,owner_id,config_value,action FROM config_item_history")]
    revisions = {tuple(row[:2]): int(row[2]) for row in sql.select(
        "SELECT app,env,revision FROM config_namespace_revision")}
    receipts = int(sql.select("SELECT COUNT(*) FROM write_receipt")[0][0])
    return State(current, sorted(history), revisions, receipts)


def seed_database(jars, port, sql):
    for scope in SCOPES:
        for key in KEYS:
            value = value_at("seed", 0)
            data = jars.post(port, CONFIGS, scope, key=key, value=value).expect()
            if data["version"] != 1:
                raise RuntimeError("The isolated seed must start from an empty database")
    seed = read_state(sql)
    if (len(seed.current) != len(SCOPES) * WORKERS or len(seed.history) != len(seed.current)
            or seed.revisions != dict.fromkeys(SCOPES, WORKERS) or seed.receipts):
        raise RuntimeError("Unexpected current rows, history, revisions or receipts in the seed")
    tables = ("config_item", "config_item_history", "config_namespace_revision")
    options = [arg for arg in sql.command[1:]
               if arg not in ("-BNr", "--database=config_center_it", "--connect-timeout=5")]
    dump = subprocess.check_output(
        ["mysqldump", *options, "--single-transaction", "--skip-lock-tables", "--skip-add-locks",
         "--no-tablespaces", "--set-gtid-purged=OFF", "--no-create-info", "--skip-triggers",
         "--compact", "config_center_it", *tables], env=sql.environment, timeout=35)
    deletes = "\n".join(f"DELETE FROM {table};" for table in
                        ("config_item_history", "config_item", "config_namespace_revision"))
    reset = b"START TRANSACTION;\n" + deletes.encode("ascii") + b"\n" + dump + b"\nCOMMIT;\n"
    return seed, reset


def restore_seed(sql, seed, reset):
    # Only called while private_mysql owns this dedicated config_center_it.
    subprocess.run(sql.command, input=reset, env=sql.environment, check=True, timeout=35)
    restored = read_state(sql)
    if restored != seed:
        raise RuntimeError("Current rows, history, revisions or receipts differ from the common seed")
    return restored


def check_readback(before, after, targets, workers, phase):
    current, history, revisions = before.current.copy(), before.history.copy(), before.revisions.copy()
    replies_match = True
    keys = []
    for target, samples in zip(targets, workers, strict=True):
        owner, initial, lock_version, value = current[target]
        version = initial
        for ordinal, sample in enumerate(samples, 1):
            if sample.outcome == "ok":
                version += 1
                replies_match &= sample.version == version
                value = value_at(phase, ordinal)
                history.append((target, version, owner, value, "UPSERT"))
        delta = version - initial
        current[target] = (owner, version, lock_version + delta, value)
        revisions[target[:2]] += delta
        row = after.current.get(target)
        keys.append({"target": target, "before": initial, "after": row[1] if row else None, "success": delta})
    checks = {
        "reply_versions": replies_match, "current": current == after.current,
        "history": sorted(history) == after.history, "revision": revisions == after.revisions,
        "no_receipts": before.receipts == after.receipts == 0,
    }
    return {
        "checks": checks, "keys": keys, "history_delta": len(after.history) - len(before.history),
        "revision_deltas": {f"{app}/{env}": after.revisions.get((app, env), 0) - prior
                            for (app, env), prior in before.revisions.items()},
    }


def summarize(workers):
    samples = [sample for worker in workers for sample in worker]
    first = min(sample.started for sample in samples)
    seconds = (max(sample.finished for sample in samples) - first) / 1e9
    failures = Counter(sample.outcome for sample in samples if sample.outcome != "ok")
    success_ms = sorted((sample.finished - sample.started) / 1e6 for sample in samples if sample.outcome == "ok")
    return {
        "attempts": len(samples), "success": len(success_ms), "failures": dict(failures), "seconds": seconds,
        "success_per_second": len(success_ms) / seconds,
        "p50_ms": success_ms[math.ceil(len(success_ms) * 0.50) - 1] if success_ms else None,
        "p95_ms": success_ms[math.ceil(len(success_ms) * 0.95) - 1] if success_ms else None,
    }


def write_record(output, record):
    with output.open("a", encoding="utf-8") as file:
        file.write(json.dumps(record, separators=(",", ":")) + "\n")


def run_phase(sql, before, port, grants, output, label, group, phase, writes):
    workers = exercise(port, grants, group, phase, writes)
    metrics = summarize(workers)
    try:
        after = read_state(sql)
        readback = check_readback(before, after, GROUPS[group], workers, phase)
        valid = not metrics["failures"] and all(readback["checks"].values())
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        readback = {"error": type(error).__name__}
        valid = False
    first = min(sample.started for worker in workers for sample in worker)
    result = {"label": label, "group": group, "phase": phase, "workers": WORKERS, "writes_per_worker": writes,
              **metrics, "valid": valid, "readback": readback}
    write_record(output, {**result, "samples": [
        [[s.started - first, s.finished - first, s.outcome, s.version] for s in worker]
        for worker in workers
    ]})
    print(json.dumps(result), flush=True)
    if not valid:
        raise RuntimeError(f"{label} is invalid. Review failures and SQL readback in {output}")
    return after


def benchmark(jars, port, sql, output, *, smoke_only):
    seed, reset = seed_database(jars, port, sql)
    write_record(output, {"seed": {"current": len(seed.current), "history": len(seed.history),
                                    "scopes": len(seed.revisions), "revision_per_scope": WORKERS,
                                    "restore_sql_sha256": hashlib.sha256(reset).hexdigest()}})
    for group in GROUPS:
        before = restore_seed(sql, seed, reset)
        run_phase(sql, before, port, jars.keys, output, f"smoke-{group}", group, "smoke", SMOKE_WRITES)
    if smoke_only:
        return

    for pair in range(1, PAIRS + 1):
        order = tuple(GROUPS) if pair % 2 else tuple(reversed(GROUPS))
        for group in order:
            label = f"pair-{pair}-{group}"
            warmed = restore_seed(sql, seed, reset)
            for warm_group in GROUPS:
                warmed = run_phase(sql, warmed, port, jars.keys, output, f"{label}-warm-{warm_group}",
                                   warm_group, "warm", WARM_WRITES)
            before = restore_seed(sql, seed, reset)
            run_phase(sql, before, port, jars.keys, output, label, group, "measure", WRITES)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--smoke-only", action="store_true", help="only validate the two small runs")
    args = parser.parse_args()
    if not JAR.is_file():
        parser.error("Build the server with the Maven wrapper's clean verify first.")
    for program in ("mysqld", "mysql", "mysqladmin", "mysqldump"):
        if shutil.which(program) is None:
            parser.error(f"Put {program} on PATH.")
    stripes = {"/".join(scope): lock_stripe(scope) for scope in SCOPES}
    if len(set(stripes.values())) != len(SCOPES):
        raise RuntimeError("The selected namespaces collide on a process-local lock stripe")
    body_sizes = {len(payload(target, value_at("measure", 1))) for targets in GROUPS.values() for target in targets}
    if len(body_sizes) != 1:
        raise RuntimeError("Concentrated and spread requests must use equal payload sizes")
    qa = ROOT / "docs/delete/Config-Plan11"
    qa.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="write-baseline-", dir=qa))
    output = directory / "rounds.jsonl"
    print(f"Artifacts: {directory}", flush=True)
    jars = Jars(JAR, directory, SCOPES)
    with private_mysql(directory / "mysql") as (sql, env, _):
        # Use the owned datasource and the JAR's bundled configuration on both sides of the comparison.
        overrides = ("SPRING_", "SERVER_", "SECURITY_", "LOGGING_", "RATE_LIMIT_", "MANAGEMENT_",
                     "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
        env = {key: value for key, value in env.items() if not key.upper().startswith(overrides)}
        settings = ("version", "transaction_isolation", "innodb_flush_log_at_trx_commit",
                    "innodb_buffer_pool_size", "innodb_lock_wait_timeout", "max_connections", "log_bin")
        mysql = dict(zip(settings, sql.select("SELECT " + ",".join(f"@@{key}" for key in settings))[0], strict=True))
        java_output = subprocess.check_output([str(jars.java), "-version"], stderr=subprocess.STDOUT,
                                              env=env, text=True, errors="replace", timeout=10)
        java = next(line for line in java_output.splitlines() if line.startswith(("java version ", "openjdk version ")))
        metadata = {
            "source_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
            "fixture_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "os": platform.platform(), "python": platform.python_version(), "java": java, "mysql": mysql,
            "jdbc_options": urlsplit(env["CONFIG_CENTER_DB_URL"].removeprefix("jdbc:")).query,
            "workers": WORKERS, "pairs": PAIRS, "value_bytes": VALUE_BYTES, "measure_body_bytes": body_sizes.pop(),
            "timeout_seconds": REQUEST_TIMEOUT, "stripes": stripes, "targets": GROUPS, "rate_limit": False,
            "samples": ["start_offset_ns", "finish_offset_ns", "outcome", "version"],
        }
        write_record(output, metadata)
        print(json.dumps(metadata), flush=True)
        with jars.serving(JAR, env, "server", ("--spring.config.location=classpath:/",)) as port:
            benchmark(jars, port, sql, output, smoke_only=args.smoke_only)


if __name__ == "__main__":
    main()
