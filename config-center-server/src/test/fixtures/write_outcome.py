"""Demonstrate readback after a lost write response against the packaged server."""
import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.parse import quote, urlencode, urlsplit

ROOT = Path(__file__).resolve().parents[4]
JAR = ROOT / "config-center-server/target/config-center-server-1.0.0.jar"
CONFIGS = "/api/configs"
ENV = "dev"
TRACE = "X-Trace-Id"


@dataclass
class Reply:
    status: int
    trace: str
    body: dict

    def expect(self, status=200, code=0):
        assert self.status == status and self.body["code"] == code, self
        assert self.trace == self.body["traceId"], self
        return self.body["data"]


def request(port, method, route, body=None, headers=None):
    client = http.client.HTTPConnection("127.0.0.1", port, timeout=8)
    try:
        client.request(method, route, body=body, headers=headers or {})
        response = client.getresponse()
        return Reply(response.status, response.getheader(TRACE), json.loads(response.read()))
    finally:
        client.close()


class MysqlReadback:
    def __init__(self, environment):
        url = environment["CONFIG_CENTER_DB_URL"]
        assert url.startswith("jdbc:mysql://"), "Expected the dedicated JDBC MySQL URL"
        address = urlsplit(url.removeprefix("jdbc:"))
        assert address.hostname == "127.0.0.1" and address.path == "/config_center_it", (
            "Use loopback and the isolated config_center_it database")
        assert not address.username and not address.password and address.port
        username = environment["CONFIG_CENTER_DB_USERNAME"]
        assert username.lower() != "root"
        mysql = shutil.which("mysql")
        if mysql is None:
            raise RuntimeError("Put the MySQL command-line client on PATH for independent SQL readback")
        self.command = [mysql, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                        f"--port={address.port}", "--user=" + username, "--database=config_center_it",
                        "--connect-timeout=5", "--default-character-set=utf8mb4", "-BNr"]
        self.environment = {**environment, "MYSQL_PWD": environment["CONFIG_CENTER_DB_PASSWORD"]}
        catalog, authenticated, version, isolation = self.select(
            "SELECT DATABASE(), CURRENT_USER(), VERSION(), @@transaction_isolation")[0]
        assert catalog == "config_center_it" and authenticated.split("@")[0].lower() != "root"
        print(f"Independent SQL: catalog={catalog} MySQL={version} isolation={isolation}", flush=True)

    def select(self, sql):
        result = subprocess.run([*self.command, "-e", sql], env=self.environment, check=True, timeout=12,
                                capture_output=True, text=True, encoding="utf-8")
        return [line.split("\t") for line in result.stdout.splitlines()]


def resolve_java():
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], check=True, timeout=10,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace").stdout
    home = re.search(r"(?m)^\s*java\.home = (.+)$", settings)
    version = re.search(r"(?m)^\s*java\.version = (17[^\s]*)$", settings)
    if not home or not version:
        raise RuntimeError("This fixture requires JDK 17 on PATH")
    executable = Path(home[1].strip()) / "bin" / ("java.exe" if os.name == "nt" else "java")
    print("JDK", version[1], flush=True)
    return executable


@contextmanager
def server(profile, app, key, directory, environment):
    env = {**environment, "JAVA_TOOL_OPTIONS": f'-Djava.io.tmpdir="{directory.as_posix()}"'}
    java = resolve_java()
    log = directory / "server.log"
    with log.open("wb") as output:
        process = subprocess.Popen(
            [str(java), "-jar", str(JAR), "--spring.profiles.active=" + profile,
             "--server.address=127.0.0.1", "--server.port=0", "--rate-limit.enabled=false",
             "--security.api-keys[0].key=" + key,
             "--security.api-keys[0].app=" + app, "--security.api-keys[0].env=" + ENV],
            cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            until = time.monotonic() + 80
            while process.poll() is None and time.monotonic() < until:
                match = re.search(rb"Tomcat started on port (\d+)", log.read_bytes())
                if match:
                    yield int(match[1])
                    return
                time.sleep(0.2)
            raise RuntimeError(f"Packaged server failed to start. Read {log}")
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(15)


class DropWriteResponse(HTTPServer):
    def __init__(self, upstream, forward):
        super().__init__(("127.0.0.1", 0), DropHandler)
        self.upstream = upstream
        self.forward = forward
        self.attempts = 0
        self.forwarded = 0
        self.receipt = None
        self.done = threading.Event()

    def drop(self, payload, headers):
        self.attempts += 1
        assert self.attempts == 1, "The fixture routes one attempt"
        if self.forward:
            self.forwarded += 1
            self.receipt = request(self.upstream, "POST", CONFIGS, payload, headers)
            self.receipt.expect()
            assert self.receipt.trace == headers[TRACE]
        self.done.set()


class DropHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        assert self.path == CONFIGS
        payload = self.rfile.read(int(self.headers["Content-Length"]))
        headers = {name: self.headers[name] for name in ("Content-Type", "X-API-Key", TRACE)}
        self.server.drop(payload, headers)
        # Close the caller connection without sending a response.
        self.close_connection = True

    def log_message(self, fmt, *args):
        pass


def drop(port, payload, headers, forward):
    with DropWriteResponse(port, forward) as proxy:
        worker = threading.Thread(target=proxy.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        worker.start()
        try:
            caller_error = None
            try:
                request(proxy.server_port, "POST", CONFIGS, payload, headers)
            except http.client.RemoteDisconnected as lost:
                caller_error = type(lost).__name__
            assert caller_error == "RemoteDisconnected"
            print(f"Caller: trace={headers[TRACE]} {caller_error}, write result unknown")
            assert proxy.done.wait(3)
            assert proxy.forwarded == int(forward)
            if proxy.receipt:
                print(f"Injector: forwarded=1 upstream_status={proxy.receipt.status} upstream_trace={proxy.receipt.trace}")
            else:
                print("Injector: forwarded=0")
        finally:
            proxy.shutdown()
            worker.join(timeout=3)


class Exercise:
    def __init__(self, port, app, key, sql):
        self.port, self.app, self.key, self.sql = port, app, key, sql
        self.query = urlencode({"app": app, "env": ENV})

    def submission(self, name, value, reason, expected=None):
        fields = dict(app=self.app, env=ENV, key=name, value=value,
                      description="synthetic fixture", operator="demo-writer", reason=reason)
        if expected is not None:
            fields["expectedVersion"] = expected
        return json.dumps(fields).encode()

    def headers(self, trace):
        return {"Content-Type": "application/json", "X-API-Key": self.key, TRACE: trace}

    def post(self, body, trace):
        reply = request(self.port, "POST", CONFIGS, body, self.headers(trace))
        assert trace == reply.trace
        return reply

    def readback(self, name, label):
        # Each request opens a new connection. This reader never consults the drop proxy.
        current = request(self.port, "GET", f"{CONFIGS}/{quote(name)}?{self.query}")
        history = request(self.port, "GET", f"{CONFIGS}/history?{self.query}&key={quote(name)}")
        watch = request(self.port, "GET", f"{CONFIGS}/watch?{self.query}&sinceVersion=0&timeoutSeconds=1")
        row = current.expect()
        journal = history.expect()
        revision = watch.expect()["latestVersion"]
        entries = [(item["version"], item["value"], item["action"], item["operator"], item["reason"])
                   for item in journal]
        current_value = (row["version"], row["value"])
        assert entries[0][:2] == current_value
        print(f"Reader {label}: HTTP={current.status}/{history.status}/{watch.status}"
              f" traces={current.trace}/{history.trace}/{watch.trace}"
              f" current={current_value} history={entries} revision={revision}")
        if self.sql:
            # Only names generated inside this fixture reach these read-only queries.
            assert re.fullmatch(r"[a-z0-9-]+", self.app) and re.fullmatch(r"[a-z0-9-]+", name)
            scope = f"app='{self.app}' AND env='dev'"
            exact = f"{scope} AND config_key='{name}'"
            db_row = self.sql.select("SELECT version,config_value FROM config_item WHERE " + exact)
            db_history = self.sql.select(
                "SELECT version,config_value,action,operator,reason FROM config_item_history WHERE "
                + exact + " ORDER BY version DESC")
            db_revision = self.sql.select("SELECT revision FROM config_namespace_revision WHERE " + scope)
            assert db_row == [list(map(str, current_value))]
            assert db_history == [list(map(str, entry)) for entry in entries]
            assert db_revision == [[str(revision)]]
            print(f"Independent SQL {label}: current={db_row} history={db_history} revision={db_revision}")
        return current_value, entries, revision

    def run(self):
        for scenario in ("committed", "not-arrived", "later-write", "ambiguous"):
            name = scenario
            self.post(self.submission(name, "old", "seed"), f"{name}-seed").expect()
            baseline = self.readback(name, "baseline")
            target = self.submission(name, "target", "caller-noted", expected=1)
            forward = scenario in ("committed", "later-write")
            drop(self.port, target, self.headers(f"{name}-attempt"), forward)

            if scenario == "later-write":
                self.post(self.submission(name, "later", "later-writer", expected=2), "later-writer").expect()
            elif scenario == "ambiguous":
                competing = self.post(target, "competing-writer")
                competing.expect()
                print(f"Injector: identical payload committed by trace={competing.trace}")

            current, history, revision = self.readback(name, scenario)
            if scenario == "not-arrived":
                assert (current, history, revision) == baseline
                print("Reader conclusion: target effect was not observed at this read")
            elif scenario == "later-write":
                assert current == (3, "later") and [item[0] for item in history] == [3, 2, 1]
                assert history[1][1:] == ("target", "UPSERT", "demo-writer", "caller-noted")
                assert revision == baseline[2] + 2
                print("Reader conclusion: target effect found in history after another write")
            else:
                assert current == (2, "target") and [item[0] for item in history] == [2, 1]
                assert history[0][1:] == ("target", "UPSERT", "demo-writer", "caller-noted")
                assert revision == baseline[2] + 1
                if scenario == "ambiguous":
                    assert history == committed_history
                else:
                    committed_history = history
                print("Reader conclusion: target effect observed; no unique request attribution; stop writing")

        name = "replay"
        self.post(self.submission(name, "old", "seed"), "replay-seed").expect()
        exact = self.submission(name, "target", "repeat-note", expected=1)
        self.post(exact, "first-attempt").expect()
        before = self.readback(name, "before deliberate replay")
        replay = self.post(exact, "replay-with-old-version")
        replay.expect(409, 4091)
        print(f"Deliberate replay: HTTP={replay.status}/4091 trace={replay.trace}")
        assert self.readback(name, "replay with old expectedVersion") == before
        self.post(self.submission(name, "target", "repeat-note"), "replay-unconditional").expect()
        current, entries, revision = self.readback(name, "unconditional repeat")
        assert current == (3, "target") and entries[0][1:] == entries[1][1:]
        assert revision == before[2] + 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=["local", "mysql"], default="local")
    args = parser.parse_args()
    if not JAR.is_file():
        parser.error("Run the Maven wrapper's clean verify before this fixture")
    qa = ROOT / "docs/delete/Config-Plan05"
    qa.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=args.profile + "-readback-", dir=qa))
    env = dict(os.environ)
    if args.profile == "local":
        env.update(SPRING_DATASOURCE_URL="jdbc:h2:mem:plan05-outcome;MODE=MySQL;DB_CLOSE_DELAY=-1",
                   SPRING_DATASOURCE_DRIVER_CLASS_NAME="org.h2.Driver", SPRING_DATASOURCE_USERNAME="sa",
                   SPRING_DATASOURCE_PASSWORD="", SPRING_FLYWAY_ENABLED="false")
    sql = MysqlReadback(env) if args.profile == "mysql" else None
    app, key = "plan05-readback-" + secrets.token_hex(5), secrets.token_hex(16)
    print(f"Profile={args.profile} app={app} Server JAR SHA-256={hashlib.sha256(JAR.read_bytes()).hexdigest()}"
          f" logs={directory}", flush=True)
    with server(args.profile, app, key, directory, env) as port:
        Exercise(port, app, key, sql).run()


if __name__ == "__main__":
    main()
