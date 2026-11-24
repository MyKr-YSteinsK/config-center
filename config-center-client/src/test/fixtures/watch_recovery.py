"""Run the built server and Java CLI on loopback with a controlled outage."""
import argparse
import hashlib
import http.client
import json
import os
import re
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[4]
JAR = ROOT / "config-center-server/target/config-center-server-1.0.0.jar"
QUERY = "?app=demo-app&env=dev"
CONFIGS = "/api/configs"
WATCH = CONFIGS + "/watch"
RECOVERY_SECONDS = 6
READ_TIMEOUT_MS = 900
SCENARIOS = ("recover", "restart", "503", "connection", "timeout")


class LocalServer:
    def __init__(self, directory, env):
        self.directory = directory
        self.env = env
        self.process = None
        settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], check=True, timeout=10,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
        java_home = re.search(r"(?m)^\s*java\.home = (.+)$", settings)
        if not java_home:
            raise RuntimeError("Cannot find the actual Java executable for the fixture")
        self.java = Path(java_home[1].strip()) / "bin" / ("java.exe" if os.name == "nt" else "java")
        self.starts = 0
        self.port = None

    def start(self):
        self.starts += 1
        log = self.directory / f"server-{self.starts}.log"
        with log.open("wb") as output:
            self.process = subprocess.Popen(
                [str(self.java), "-jar", str(JAR), "--spring.profiles.active=local",
                 "--server.address=127.0.0.1", "--server.port=0", "--rate-limit.enabled=false",
                 "--spring.datasource.url=jdbc:h2:mem:watch-smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
                 "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa",
                 "--spring.datasource.password=", "--spring.flyway.enabled=false"],
                cwd=ROOT, env=self.env, stdout=output, stderr=subprocess.STDOUT)
        limit = time.monotonic() + 75
        while self.process.poll() is None and time.monotonic() < limit:
            match = re.search(rb"Tomcat started on port (\d+)", log.read_bytes())
            if match:
                self.port = int(match[1])
                return
            time.sleep(0.2)
        raise RuntimeError(f"Server startup failed. Read {log}")

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            self.process.wait(15)

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=65)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def seed_feature(self):
        self.put("/api/features", name="new-checkout", enabled=True, rolloutPercentage=0, allowlist=["u3000"])

    def put(self, path, **fields):
        body = json.dumps(dict(app="demo-app", env="dev", **fields))
        status, _, payload = self.request("POST", path, body,
                                         {"X-API-Key": "kr-dev-key", "Content-Type": "application/json"})
        assert status == 200, (status, payload)
        return json.loads(payload)["data"]["version"]


class FaultProxy(ThreadingHTTPServer):
    """Forward scoped GETs to one local JAR and stage faults after a Watch notification."""
    def __init__(self, backend, scenario):
        super().__init__(("127.0.0.1", 0), ProxyHandler)
        self.backend = backend
        self.scenario = scenario
        self.origin = f"http://127.0.0.1:{self.server_port}"
        self.started = time.monotonic()
        self.watches = []
        self.config_status = []
        self.update_versions = []
        self.fault_times = []
        self.recovered_at = None
        self.release = threading.Event()

    def log(self, message):
        print(f"{time.monotonic() - self.started:7.3f}s {message}", flush=True)

    def get(self, path, validator):
        route = urlsplit(path).path
        if route == WATCH:
            since = int(parse_qs(urlsplit(path).query)["sinceVersion"][0])
            if len(self.watches) == 1:
                if self.scenario == "restart":
                    self.backend.stop()
                    self.backend.start()
                    self.log("RESTART local H2 with an empty configuration namespace")
                    self.backend.seed_feature()
                else:
                    self.write("recovered")
            elif len(self.watches) == 2 and self.scenario == "restart":
                self.write("after-restart")

            status, headers, body = self.backend.request("GET", path)
            assert status == 200, (status, body)
            data = json.loads(body)["data"]
            self.watches.append((since, data["changed"], data["latestVersion"]))
            self.log(f"WATCH since={since} -> changed={data['changed']} revision={data['latestVersion']}")
            if self.scenario == "connection" and len(self.watches) == 2:
                # Preserve this response, then make subsequent connections to the test port fail.
                self.shutdown()
                self.server_close()
                self.log("CLOSED listener; new connections receive connection refused")
            return status, headers, body

        if route == CONFIGS:
            if len(self.watches) >= 2 and self.scenario in ("recover", "503", "timeout"):
                if self.scenario == "timeout" or self.scenario == "503" or len(self.fault_times) < 3:
                    self.fault_times.append(time.monotonic())
                    if self.scenario == "timeout":
                        self.log("CONFIG GET -> withholding response beyond read timeout")
                        self.release.wait(25)
                        return None
                    self.log("CONFIG GET -> injected 503")
                    return 503, {}, b"temporary read failure"
                if self.recovered_at is None:
                    self.recovered_at = time.monotonic()

            request_headers = {"If-None-Match": validator} if validator else {}
            status, headers, body = self.backend.request("GET", path, headers=request_headers)
            self.config_status.append(status)
            values = [row["value"] for row in json.loads(body)["data"]] if status == 200 else []
            self.log(f"CONFIG GET validator={bool(validator)} -> {status} values={values}")
            return status, headers, body

        if route == "/api/features/evaluate":
            return self.backend.request("GET", path)
        return 404, {}, b"unknown fixture route"

    def write(self, value):
        version = self.backend.put(CONFIGS, key="sample", value=value)
        self.update_versions.append(version)
        self.log(f"WRITE value={value} version={version}")


class ProxyHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        reply = self.server.get(self.path, self.headers.get("If-None-Match"))
        if reply is None:
            return
        status, headers, body = reply
        self.send_response(status)
        for header in ("Content-Type", "ETag", "X-Trace-Id"):
            if header in headers:
                self.send_header(header, headers[header])
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


def run_cli(proxy, directory, env):
    cache_dir = directory / "cache"
    cache_dir.mkdir()
    opts = [f"--demo.baseUrl={proxy.origin}", "--demo.watch.timeoutSeconds=1", "--spring.main.banner-mode=off"]
    if proxy.scenario == "restart":
        opts += ["--demo.watch.rounds=3", "--demo.watch.readTimeoutMarginMs=75000"]
    elif proxy.scenario != "recover":
        opts += ["--demo.watch.rounds=2", f"--demo.watch.recoveryTimeoutSeconds={RECOVERY_SECONDS}",
                 "--demo.watch.recoveryRetryIntervalMs=250", f"--demo.http.readTimeoutMs={READ_TIMEOUT_MS}"]
    wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    log = directory / "cli.log"
    with log.open("wb") as output:
        result = subprocess.run(
            [str(wrapper), "-q", "-B", "-pl", "config-center-client", "spring-boot:run",
             f'-Dspring-boot.run.jvmArguments=-Duser.home="{cache_dir.as_posix()}" -Dfile.encoding=UTF-8',
             "-Dspring-boot.run.arguments=" + " ".join(opts)],
            cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=210)
    text = log.read_text(encoding="utf-8", errors="replace")
    for line in text.splitlines():
        if line.startswith(("Watch round", "Watch ended", "WARN: Watch recovery", "Using stale", "cacheHit=")):
            print(line)
    cache = json.loads((cache_dir / ".config-center-client-cache-exact-v1.json").read_text(encoding="utf-8"))
    config = json.loads(cache[proxy.origin + CONFIGS + QUERY]["body"])["data"]
    print(f"CLI exit={result.returncode}; cached values={[row['value'] for row in config]}; logs={directory}")
    return result.returncode, text, config


def verify(proxy, exit_code, text, config):
    assert proxy.config_status[:2] == [200, 304], proxy.config_status
    assert proxy.watches[0] == (0, True, 3 if proxy.scenario == "restart" else 1), proxy.watches
    assert proxy.watches[1][0] == proxy.watches[0][2], proxy.watches
    if proxy.scenario == "recover":
        assert exit_code == 0 and [row["value"] for row in config] == ["recovered"]
        assert "Evaluating feature..." in text and '"enabled" : true' in text
        assert proxy.update_versions == [2]
        assert proxy.watches[1:3] == [(1, True, 2), (2, False, 2)], proxy.watches
        assert len(proxy.watches) == 5 and proxy.config_status == [200, 304, 200]
        assert len(proxy.fault_times) == 3 and proxy.recovered_at - proxy.fault_times[-1] >= 4.8
        assert "Using stale configuration" in text and "CIRCUIT_OPEN: state=OPEN" in text
        assert "observedRevision=2, confirmedCursor=1, pendingSync=true, source=stale-cache" in text
        assert "Watch round 2/5 complete: observedRevision=2, confirmedCursor=2, pendingSync=false" in text
        print(f"Breaker probe sent {proxy.recovered_at - proxy.fault_times[-1]:.3f}s after the last 503")
    elif proxy.scenario == "restart":
        assert exit_code == 0 and [row["value"] for row in config] == ["after-restart"]
        assert "Evaluating feature..." in text and '"enabled" : true' in text
        assert proxy.watches[1:] == [(3, False, 0), (0, True, 1)], proxy.watches
        assert proxy.config_status == [200, 304, 200, 200] and proxy.update_versions == [1]
        assert "Watch round 2/3 complete: observedRevision=0, confirmedCursor=0, pendingSync=false" in text
        assert "Watch round 3/3 complete: observedRevision=1, confirmedCursor=1, pendingSync=false" in text
    else:
        assert exit_code != 0 and [row["value"] for row in config] == ["old"]
        assert proxy.update_versions == [2] and proxy.watches[1:] == [(1, True, 2)]
        match = re.search(r"reason=recovery-budget-exhausted, observedRevision=2, confirmedCursor=1, "
                          r"pendingSync=true, source=stale-cache, recoveryElapsedMs=(\d+)", text)
        assert match and "Evaluating feature..." not in text
        elapsed = int(match[1])
        assert RECOVERY_SECONDS * 1000 <= elapsed < RECOVERY_SECONDS * 1000 + 5000, elapsed
        marker = {"503": "HTTP_503", "timeout": "SocketTimeoutException", "connection": "ConnectException"}
        assert marker[proxy.scenario] in text and "CIRCUIT_OPEN: state=OPEN" in text
        print(f"{proxy.scenario}: recovery window and in-flight retries ended after {elapsed}ms")


def exercise(scenario):
    qa = ROOT / "docs/delete/Config-Plan04"
    qa.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=scenario + "-", dir=qa))
    env = {**os.environ, "CONFIG_CENTER_API_KEY": "kr-dev-key",
           "JAVA_TOOL_OPTIONS": f'-Djava.io.tmpdir="{directory.as_posix()}"'}
    backend = LocalServer(directory, env)
    try:
        backend.start()
        for _ in range(3 if scenario == "restart" else 1):
            backend.put(CONFIGS, key="sample", value="old")
        backend.seed_feature()
        with FaultProxy(backend, scenario) as proxy:
            worker = threading.Thread(target=proxy.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
            worker.start()
            try:
                verify(proxy, *run_cli(proxy, directory, env))
            finally:
                proxy.release.set()
                proxy.shutdown()
                worker.join()
    finally:
        backend.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=(*SCENARIOS, "all"))
    args = parser.parse_args()
    if not JAR.is_file():
        parser.error("Build the server with the Maven wrapper's clean verify first.")
    print(f"Server JAR SHA-256 {hashlib.sha256(JAR.read_bytes()).hexdigest()}", flush=True)
    for scenario in SCENARIOS if args.scenario == "all" else [args.scenario]:
        print(f"\n=== {scenario} ===", flush=True)
        exercise(scenario)


if __name__ == "__main__":
    main()
