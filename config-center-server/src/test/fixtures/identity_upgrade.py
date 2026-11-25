"""Exercise V3 backup, V4 preflight/failed DDL, restore and exact identity on private native MySQL."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, contextmanager
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlencode, urlsplit, parse_qs

from write_outcome import JAR, ROOT, MysqlReadback, request, resolve_java

ORIGINAL = ("plan06-alpha", "dev")
TARGET = ("Plan06-alpha", "Dev")
TABLES = ("config_item", "config_item_history", "feature_flag", "feature_flag_history", "config_namespace_revision")
EXACT_CACHE = ".config-center-client-cache-exact-v1.json"


def wait_until(ready, seconds, label):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = ready()
        if value is not None and value is not False:
            return value
        time.sleep(0.18)
    raise RuntimeError(f"Timed out waiting for {label}")


@contextmanager
def private_mysql(directory):
    directory.mkdir()
    data, tmp = directory / "data", directory / "tmp"
    tmp.mkdir()
    passwords = [secrets.token_hex(20) for _ in range(3)]
    app_password, limited_password, stop_password = passwords
    with socket.socket() as address:
        address.bind(("127.0.0.1", 0))
        port = address.getsockname()[1]
    host = ["--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}"]
    seed = directory / "start.sql"
    seed.write_text(f"""ALTER USER 'root'@'localhost' ACCOUNT LOCK;
CREATE DATABASE config_center_it CHARACTER SET utf8mb4;
CREATE USER 'it_app'@'127.0.0.1' IDENTIFIED BY '{app_password}';
GRANT ALL ON config_center_it.* TO 'it_app'@'127.0.0.1' WITH GRANT OPTION;
CREATE USER 'it_limited'@'127.0.0.1' IDENTIFIED BY '{limited_password}';
GRANT SELECT, INSERT, UPDATE ON config_center_it.* TO 'it_limited'@'127.0.0.1';
CREATE USER 'it_stop'@'127.0.0.1' IDENTIFIED BY '{stop_password}';
GRANT SHUTDOWN ON *.* TO 'it_stop'@'127.0.0.1';
""", encoding="ascii")
    native = ["mysqld", "--no-defaults", f"--datadir={data}", f"--tmpdir={tmp}", f"--log-error={directory / 'mysql.log'}"]
    instance = None
    try:
        subprocess.run(native + ["--initialize-insecure"], check=True, timeout=150)
        instance = subprocess.Popen(native + [f"--port={port}", "--bind-address=127.0.0.1", "--mysqlx=OFF",
                                               "--skip-name-resolve", "--skip-log-bin", f"--init-file={seed}"])
        env = {**os.environ,
               "CONFIG_CENTER_API_KEY": secrets.token_hex(20),
               "CONFIG_CENTER_DB_URL": f"jdbc:mysql://127.0.0.1:{port}/config_center_it?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
               "CONFIG_CENTER_DB_USERNAME": "it_app", "CONFIG_CENTER_DB_PASSWORD": app_password,
               "JAVA_TOOL_OPTIONS": f'-Djava.io.tmpdir="{tmp.as_posix()}" -Dfile.encoding=UTF-8'}

        def connected():
            if instance.poll() is not None:
                raise RuntimeError(f"MySQL stopped. Read {directory / 'mysql.log'}")
            check = subprocess.run(["mysql", *host, "--user=it_app", "config_center_it", "-NBe",
                                    "SELECT DATABASE(), CURRENT_USER()"],
                                   env={**env, "MYSQL_PWD": app_password}, capture_output=True, timeout=4)
            return check.returncode == 0 and b"config_center_it\tit_app@127.0.0.1" in check.stdout

        wait_until(connected, 45, "the private MySQL credentials")
        seed.unlink()
        sql = MysqlReadback(env)
        limited = {**env, "CONFIG_CENTER_DB_USERNAME": "it_limited", "CONFIG_CENTER_DB_PASSWORD": limited_password}
        yield sql, env, limited
    finally:
        seed.unlink(missing_ok=True)
        if instance is not None and instance.poll() is None:
            stop = subprocess.run(["mysqladmin", *host, "--user=it_stop", "shutdown"],
                                  env={**os.environ, "MYSQL_PWD": stop_password}, timeout=20, capture_output=True)
            if stop.returncode and instance.poll() is None:
                instance.terminate()
            instance.wait(20)


def versions(sql):
    return sql.select("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")


def rows(sql):
    return {table: sql.select("SELECT * FROM " + table + " ORDER BY id") for table in TABLES}


def structure(sql):
    return sql.select("""
        SELECT table_name,column_name,column_type,is_nullable,collation_name
        FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'
        ORDER BY table_name, ordinal_position
        """) + sql.select("""
        SELECT table_name,index_name,seq_in_index,column_name,non_unique
        FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'
        ORDER BY table_name,index_name,seq_in_index
        """)


def backup(sql, file):
    options = [arg for arg in sql.command[1:] if arg not in ("-BNr", "--database=config_center_it", "--connect-timeout=5")]
    with file.open("wb") as output:
        subprocess.run(["mysqldump", *options, "--single-transaction", "--no-tablespaces", "--set-gtid-purged=OFF",
                        "--skip-lock-tables", "--skip-add-drop-table", "config_center_it"],
                       env=sql.environment, stdout=output, check=True, timeout=65)


def restore(sql, file):
    with file.open("rb") as input_data:
        subprocess.run(sql.command[:-1], env=sql.environment, stdin=input_data, check=True, timeout=65)


class Jars:
    def __init__(self, baseline, directory, scopes=(ORIGINAL, TARGET)):
        self.baseline, self.directory = baseline, directory
        self.java = resolve_java()
        self.keys = {scope: secrets.token_hex(16) for scope in scopes}
        self.args = ["--spring.profiles.active=mysql", "--server.address=127.0.0.1", "--server.port=0",
                     "--rate-limit.enabled=false"]
        for i, ((app, env), key) in enumerate(self.keys.items()):
            self.args.extend(f"--security.api-keys[{i}].{part}={value}"
                             for part, value in {"app": app, "env": env, "key": key}.items())

    @contextmanager
    def launch(self, jar, env, name, extra=()):
        log = self.directory / (name + ".log")
        with log.open("wb") as sink:
            proc = subprocess.Popen([str(self.java), "-jar", str(jar), *self.args, *extra],
                                    env=env, cwd=ROOT, stdout=sink, stderr=subprocess.STDOUT)
            try:
                yield proc, log
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    proc.wait(18)

    @contextmanager
    def serving(self, jar, env, name, extra=()):
        with self.launch(jar, env, name, extra) as (proc, log):
            def port():
                match = re.search(rb"Tomcat started on port (\d+)", log.read_bytes())
                if match:
                    return int(match[1])
                if proc.poll() is not None:
                    raise RuntimeError(f"JAR exited. Read {log}")
                return None

            yield wait_until(port, 80, str(log))

    def command(self, env, name, extra=(), *, jar=None):
        with self.launch(jar or JAR, env, name, extra) as (proc, log):
            code = proc.wait(85)
            return code, log.read_text(encoding="utf-8", errors="replace")

    def post(self, port, route, scope, key_scope=None, **fields):
        body = json.dumps({"app": scope[0], "env": scope[1], **fields}).encode()
        return request(port, "POST", route, body, {
            "Content-Type": "application/json", "X-API-Key": self.keys[key_scope or scope]})


def query(scope, **fields):
    return "?" + urlencode({"app": scope[0], "env": scope[1], **fields})


def get(port, path, scope, **fields):
    return request(port, "GET", path + query(scope, **fields))


def flag_value(port, scope, name, user="plan05-user-6"):
    return get(port, "/api/features/evaluate", scope, name=name, userId=user)


def seed_v3(jars, port):
    jars.post(port, "/api/configs", ORIGINAL, key="cafe", value="first", description="v3-original",
              operator="synthetic", reason="seed").expect()
    jars.post(port, "/api/configs", TARGET, key="Cafe", value="second", expectedVersion=1,
              reason="mixed-spelling").expect()
    jars.post(port, "/api/configs/rollback", TARGET, key="Cafe", targetVersion=1, reason="文" * 500).expect()
    jars.post(port, "/api/configs", ORIGINAL, key="other", value="another key").expect()
    jars.post(port, "/api/features", ORIGINAL, name="cafe", enabled=True,
              rolloutPercentage=50, allowlist=["a+b"]).expect()
    jars.post(port, "/api/features", TARGET, name="Cafe", enabled=True,
              rolloutPercentage=50, allowlist=["a+b"], reason="mixed flag").expect()
    jars.post(port, "/api/features/rollback", TARGET, name="Cafe", targetVersion=1, reason="界" * 500).expect()
    jars.post(port, "/api/features", ORIGINAL, name="other", enabled=False, rolloutPercentage=20).expect()


def check_preserved(sql, old_rows, old_versions):
    current = rows(sql)
    for table in TABLES:
        assert len(old_rows[table]) == len(current[table])
        for old, new in zip(old_rows[table], current[table]):
            if table.endswith("_history"):
                assert old == new[:-1], (table, old[0])
                assert int(new[-1]) > 0
            else:
                assert new[1:3] == list(TARGET)
                assert old[:1] + old[3:] == new[:1] + new[3:], (table, old[0])
    post = versions(sql)
    assert post[:3] == old_versions and [(row[0], row[2]) for row in post] == [(str(i), "1") for i in range(1, 6)]
    assert sql.select("SELECT app,env,revision,lock_version FROM config_namespace_revision") == [[*TARGET, "4", "3"]]
    assert sql.select("""
        SELECT COUNT(*) FROM config_item_history h JOIN config_item c ON h.owner_id=c.id
        WHERE c.app='Plan06-alpha' AND c.env='Dev' AND c.config_key='Cafe'
        """) == [["3"]]


def watch(port, scope, since, trace):
    route = "/api/configs/watch" + query(scope, sinceVersion=since, timeoutSeconds=14)
    # A scope stays pending only until the scheduled write in this fixture.
    result = request(port, "GET", route, headers={"X-Trace-Id": trace})
    assert result.trace == trace
    return result.expect()


def exercise_http(jars, port, sql, baseline_bucket):
    assert get(port, "/api/configs", ORIGINAL).expect() == []
    assert get(port, "/api/configs/Cafe", ORIGINAL).status == 404
    assert flag_value(port, ORIGINAL, "Cafe").status == 404
    assert flag_value(port, TARGET, "Cafe").expect() == baseline_bucket
    assert flag_value(port, TARGET, "cafe").status == 404

    for route, field in (("configs", "key"), ("features", "name")):
        page = get(port, f"/api/{route}/history", TARGET, **{field: "Cafe"}, limit=2).expect()
        assert [h["version"] for h in page] == [3, 2]
        assert len(page[0]["reason"]) == 515
        last_page = get(port, f"/api/{route}/history", TARGET,
                        **{field: "Cafe"}, limit=2, beforeVersion=2).expect()
        assert len(last_page) == 1
        assert (last_page[0]["app"], last_page[0]["env"], last_page[0][field]) == (*ORIGINAL, "cafe")

    def pending():
        return int(request(port, "GET", "/actuator/metrics/config_center_watch_pending").body["measurements"][0]["value"])

    # Async requests begin before either write; observation uses actual server waiter metrics.
    with ThreadPoolExecutor(max_workers=2) as pool:
        old_name = pool.submit(watch, port, ORIGINAL, 0, "separate-old-name")
        target = pool.submit(watch, port, TARGET, 4, "mapped-watch")
        wait_until(lambda: pending() == 2, 6, "two registered Watch requests")
        jars.post(port, "/api/configs/rollback", TARGET, key_scope=ORIGINAL, key="Cafe", targetVersion=1).expect(403, 4031)
        rebound = jars.post(port, "/api/configs/rollback", TARGET, key="Cafe", targetVersion=1).expect()
        assert rebound["version"] == 4 and rebound["value"] == "first"
        assert target.result(timeout=6) == {"changed": True, "latestVersion": 5}
        assert pending() == 1 and not old_name.done()
        fixture = jars.post(port, "/api/configs", ORIGINAL, key="cafe", value="fresh alias").expect()
        assert fixture["version"] == 1
        assert old_name.result(timeout=6) == {"changed": True, "latestVersion": 1}
        assert pending() == 0

    changed = jars.post(port, "/api/features/rollback", TARGET, name="Cafe", targetVersion=1).expect()
    assert changed["version"] == 4
    jars.post(port, "/api/features", ORIGINAL, name="cafe", enabled=True, rolloutPercentage=50).expect()
    assert flag_value(port, ORIGINAL, "cafe").expect()["bucket"] == 3
    assert flag_value(port, TARGET, "Cafe").expect() == baseline_bucket

    old_config = get(port, "/api/configs/history", ORIGINAL, key="cafe").expect()
    new_config = get(port, "/api/configs/history", TARGET, key="Cafe").expect()
    assert [h["version"] for h in old_config] == [1] and old_config[0]["value"] == "fresh alias"
    assert [h["version"] for h in new_config] == [4, 3, 2, 1]
    assert [h["version"] for h in get(port, "/api/features/history", ORIGINAL, name="cafe").expect()] == [1]
    assert [h["version"] for h in get(port, "/api/features/history", TARGET, name="Cafe").expect()] == [4, 3, 2, 1]
    assert sql.select("SELECT revision FROM config_namespace_revision ORDER BY revision") == [["1"], ["5"]]
    print("V4 HTTP/SQL: raw v1 belongs only to the mapped scope; rollback created v4; exact Watch 5 versus 1; bucket 68 versus 3.")


class CliProxy(BaseHTTPRequestHandler):
    def do_GET(self):
        target = self.server
        validator = self.headers.get("If-None-Match")
        route = urlsplit(self.path)
        status, headers, body = 503, [], b"temporarily unavailable"
        if not target.offline:
            with closing(http.client.HTTPConnection("127.0.0.1", target.upstream, timeout=6)) as upstream:
                upstream.request("GET", self.path, headers={"If-None-Match": validator} if validator else {})
                response = upstream.getresponse()
                status, headers, body = response.status, response.getheaders(), response.read()
        target.reads.append((route.path, parse_qs(route.query), validator, status))
        self.send_response(status)
        for name, value in headers:
            if name.lower() in ("content-type", "etag", "x-trace-id"):
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


def exercise_cli(port, directory, env, previous_response):
    cache = directory / "client-home"
    cache.mkdir()
    wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    with ThreadingHTTPServer(("127.0.0.1", 0), CliProxy) as proxy:
        proxy.upstream, proxy.offline, proxy.reads = port, True, []
        worker = threading.Thread(target=proxy.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        worker.start()
        try:
            base = f"http://127.0.0.1:{proxy.server_port}"
            alias_url = base + "/api/configs" + query(ORIGINAL)
            old_bytes = json.dumps({alias_url: {"etag": 'W/"old-aliased-data"', "body": json.dumps(previous_response)}})
            earlier = [cache / name for name in (".config-center-client-cache.json", ".config-center-demo-client-cache.json")]
            for old in earlier:
                old.write_text(old_bytes, encoding="utf-8")
            opts = (f"--demo.baseUrl={base} --demo.app={ORIGINAL[0]} --demo.env={ORIGINAL[1]}"
                    " --demo.userId=a+b --demo.watch.enabled=false")
            for label, offline, expected_status in (("offline", True, 503), ("online", False, 200), ("etag", False, 304)):
                proxy.offline = offline
                proxy.reads.clear()
                log = directory / f"client-{label}.log"
                with log.open("wb") as sink:
                    result = subprocess.run(
                        [str(wrapper), "-q", "-B", "-pl", "config-center-client", "spring-boot:run",
                         f'-Dspring-boot.run.jvmArguments=-Duser.home="{cache.as_posix()}" -Dfile.encoding=UTF-8',
                         f"-Dspring-boot.run.arguments={opts}"],
                        env=env, cwd=ROOT, stdout=sink, stderr=subprocess.STDOUT, timeout=100)
                config_reads = [r for r in proxy.reads if r[0] == "/api/configs"]
                assert config_reads and all(r[3] == expected_status for r in config_reads), log
                assert all((r[2] is not None) == (expected_status == 304) for r in config_reads), log
                assert all(r[1]["app"] == [ORIGINAL[0]] and r[1]["env"] == [ORIGINAL[1]]
                           for r in config_reads), log
                exact = cache / EXACT_CACHE
                if offline:
                    assert result.returncode != 0 and not exact.exists(), log
                else:
                    assert result.returncode == 0 and exact.is_file(), log
                    assert json.loads(json.loads(exact.read_text(encoding="utf-8"))[alias_url]["body"])["data"] == [], log
                    decisions = [r for r in proxy.reads if r[0] == "/api/features/evaluate"]
                    assert decisions[0][1]["userId"] == ["a+b"] and decisions[0][3] == 404, log
                assert all(old.read_text(encoding="utf-8") == old_bytes for old in earlier)
            print("Real CLI: offline old alias cache ignored, 200 empty exact list, then 304 only with the new validator.")
        finally:
            proxy.shutdown()
            worker.join(3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-jar", type=Path, required=True, help="V3 server JAR from 52e887d")
    args = parser.parse_args()
    baseline = args.baseline_jar.resolve()
    if not baseline.is_file() or not JAR.is_file():
        parser.error("Build the V3 baseline and current server JARs first.")
    for executable in ("mysqld", "mysql", "mysqladmin", "mysqldump"):
        if shutil.which(executable) is None:
            parser.error(f"Put {executable} on PATH")
    qa = ROOT / "docs/delete/Config-Plan06"
    qa.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="rehearsal-", dir=qa))
    print(f"V3 SHA-256={hashlib.sha256(baseline.read_bytes()).hexdigest()}")
    print(f"Candidate JAR SHA-256={hashlib.sha256(JAR.read_bytes()).hexdigest()}")
    print(f"Fixture artifacts: {directory}", flush=True)
    jars = Jars(baseline, directory)
    dump = directory / "backup-v3.sql"
    mapping = directory / "synthetic-map.json"
    mapped = (f"--config-center.identity-mapping-file={mapping}",)

    with private_mysql(directory / "source") as (sql, env, _):
        with jars.serving(baseline, env, "source-v3") as port:
            assert [row[0] for row in versions(sql)] == ["1", "2", "3"]
            seed_v3(jars, port)
            old_list = get(port, "/api/configs", ORIGINAL).body
            bucket = flag_value(port, ORIGINAL, "Cafe").expect()
            assert bucket["bucket"] == 68
        old_rows, old_structure, old_versions = rows(sql), structure(sql), versions(sql)
        backup(sql, dump)
        print(f"V3 logical backup bytes={dump.stat().st_size} sha256={hashlib.sha256(dump.read_bytes()).hexdigest()}")
        code, report = jars.command(env, "preflight-no-map", ("--check-identity-migration",))
        assert code != 0 and "explicit mapping required" in report
        groups = [json.loads(line.removeprefix("IDENTITY_V4_GROUP "))
                  for line in report.splitlines() if line.startswith("IDENTITY_V4_GROUP ")]
        group, = [g for g in groups if g["needsMapping"]]
        mapping.write_text(json.dumps({"mappings": [{"fingerprint": group["fingerprint"],
                                                     "target": dict(zip(("app", "env"), TARGET))}]}), encoding="utf-8")
        code, report = jars.command(env, "preflight-mapped", ("--check-identity-migration", *mapped))
        assert code == 0 and "IDENTITY_V4_READY" in report
        assert (rows(sql), structure(sql), versions(sql)) == (old_rows, old_structure, old_versions)
        code, reason = jars.command(env, "v4-refused")
        assert code != 0 and "explicit mapping required" in reason
        assert (rows(sql), structure(sql)) == (old_rows, old_structure)
        assert versions(sql)[:3] == old_versions and versions(sql)[-1][::2] == ["4", "0"]
        print("Default V4 refused at preflight. Business rows and DDL unchanged; Flyway version 4 success=0.")

    with private_mysql(directory / "ddl-failure") as (sql, env, limited):
        restore(sql, dump)
        assert (rows(sql), structure(sql), versions(sql)) == (old_rows, old_structure, old_versions)
        sql.select("""GRANT ALTER, CREATE ON config_center_it.config_item_history TO 'it_limited'@'127.0.0.1'""")
        code, reason = jars.command(limited, "v4-ddl-refused", mapped)
        assert code != 0 and "feature_flag_history" in reason and "denied" in reason.lower()
        assert sql.select("""SELECT table_name FROM information_schema.columns
                             WHERE table_schema=DATABASE() AND column_name='owner_id'""") == [["config_item_history"]]
        assert versions(sql)[-1][::2] == ["4", "0"]
        code, reason = jars.command(env, "v4-restart-refused", mapped)
        assert code != 0 and "failed migration" in reason.lower()
        print("Actual MySQL DDL failure left the first owner_id column committed. Normal restart refused the failed Flyway marker.")

    with private_mysql(directory / "restored") as (sql, env, _):
        restore(sql, dump)
        assert (rows(sql), structure(sql), versions(sql)) == (old_rows, old_structure, old_versions)
        with jars.serving(baseline, env, "restored-v3") as port:
            assert get(port, "/api/configs", ORIGINAL).expect() == old_list["data"]
            assert flag_value(port, ORIGINAL, "Cafe").expect() == bucket
        print("Restored the backup into a separate private instance; the V3 JAR read the original data.")
        with jars.serving(JAR, env, "restored-v4", mapped) as port:
            check_preserved(sql, old_rows, old_versions)
            exercise_cli(port, directory, env, old_list)
            exercise_http(jars, port, sql, bucket)
        with jars.serving(JAR, env, "v4-restarted") as port:
            assert get(port, "/api/configs/cafe", ORIGINAL).expect()["version"] == 1
            assert len(versions(sql)) == 5
        print("V4 restarted through Flyway and Hibernate without another migration.")


if __name__ == "__main__":
    main()
