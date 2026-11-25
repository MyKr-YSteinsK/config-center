"""Start fixture-owned JVMs and preserve their logs on every exit path."""
from contextlib import contextmanager
import os
from pathlib import Path
import re
import subprocess
import sys
import time


TOMCAT_PORT = re.compile(rb"Tomcat started on port (\d+)")


def resolve_java(*, report=True):
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], check=True, timeout=10,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace").stdout
    home = re.search(r"(?m)^\s*java\.home = (.+)$", settings)
    version = re.search(r"(?m)^\s*java\.version = (17[^\s]*)$", settings)
    if not home or not version:
        raise RuntimeError("This fixture requires JDK 17 on PATH")
    if report:
        print("JDK", version[1], flush=True)
    # The first java on PATH can be a Windows launcher, so own the JVM process itself.
    return Path(home[1].strip()) / "bin" / ("java.exe" if os.name == "nt" else "java")


class LoggedProcess:
    def __init__(self, command, log, *, stage, cwd, env, stop_timeout):
        self.command = command
        self.log = log.resolve()
        self.stage = stage
        self.cwd = cwd
        self.env = env
        self.stop_timeout = stop_timeout

    def __enter__(self):
        try:
            with self.log.open("wb") as output:
                self.process = subprocess.Popen(self.command, cwd=self.cwd, env=self.env,
                                                stdout=output, stderr=subprocess.STDOUT)
        except (OSError, ValueError) as error:
            raise RuntimeError(self._diagnostic(f"could not launch child ({type(error).__name__})")) from None
        return self

    def _diagnostic(self, message):
        return f"{self.stage}: {message}. Log: {self.log}"

    @contextmanager
    def serving(self, timeout):
        with self:
            yield self.wait_for_port(timeout)

    def wait_for_port(self, timeout):
        deadline = time.monotonic() + timeout
        while True:
            try:
                match = TOMCAT_PORT.search(self.log.read_bytes())
            except OSError as error:
                raise RuntimeError(self._diagnostic(f"could not read log ({type(error).__name__})")) from None
            code = self.process.poll()
            if code is not None:
                raise RuntimeError(self._diagnostic(f"child exited before port handoff (exit code {code})"))
            if match:
                return int(match[1])
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(self._diagnostic(f"no port in log after {timeout:g}s"))
            time.sleep(min(0.2, remaining))

    def wait(self, timeout):
        try:
            return self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            raise RuntimeError(self._diagnostic(f"command did not exit within {timeout:g}s")) from None

    def __exit__(self, kind, primary_error, traceback):
        action = "already stopped"
        try:
            code = self.process.poll()
            if code is None:
                action = "terminate"
                try:
                    self.process.terminate()
                    code = self.process.wait(timeout=self.stop_timeout)
                except subprocess.TimeoutExpired:
                    action = f"kill after a {self.stop_timeout:g}s terminate wait"
                    self.process.kill()
                    code = self.process.wait(timeout=self.stop_timeout)
        except (OSError, subprocess.TimeoutExpired) as error:
            code = self.process.poll()
            if code is None:
                detail = f"{action} did not confirm child exit ({type(error).__name__})"
                if primary_error is None:
                    raise RuntimeError(self._diagnostic(detail)) from None
                self._report_cleanup(primary_error, detail)
                return
        if primary_error is not None or action.startswith("kill"):
            self._report_cleanup(primary_error, f"{action} confirmed child exit (exit code {code})")

    def _report_cleanup(self, primary_error, message):
        diagnostic = self._diagnostic(message)
        note = getattr(primary_error, "add_note", None)
        if note is None:
            print(diagnostic, file=sys.stderr)
        else:
            note(diagnostic)
