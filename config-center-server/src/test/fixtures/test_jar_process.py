"""Direct lifecycle regression using controlled children and a shutdown double."""
from contextlib import redirect_stderr
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, call, patch

from identity_upgrade import Jars, ROOT
from jar_process import LoggedProcess


class LoggedProcessTest(unittest.TestCase):
    def setUp(self):
        root = ROOT / "docs/delete/Config-Plan09"
        root.mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix=f"{self._testMethodName}-", dir=root))

    def child(self, stage, source):
        return LoggedProcess([sys.executable, "-c", source], self.directory / f"{stage}.log",
                             stage=stage, cwd=ROOT, env=os.environ, stop_timeout=3)

    def check_diagnostic(self, error, child, detail):
        self.assertIn(child.stage + ": ", str(error))
        self.assertIn(detail, str(error))
        self.assertTrue(child.log.is_absolute())
        self.assertIn(str(child.log), str(error))
        self.assertTrue(child.log.is_file())
        print(error, flush=True)

    def check_stopped(self, child):
        code = child.process.poll()
        self.assertIsNotNone(code)
        print(f"Confirmed pid={child.process.pid} exited with code {code}. Log: {child.log}", flush=True)

    def test_exited_child_with_or_without_port(self):
        for stage, output in (("dead-with-port", "Tomcat started on port 43123"), ("dead-without-port", "no port")):
            with self.subTest(stage=stage):
                child = self.child(stage, f"print({output!r}, flush=True); raise SystemExit(23)")
                with child:
                    self.assertEqual(child.wait(10), 23)
                    with self.assertRaises(RuntimeError) as caught:
                        child.wait_for_port(1)
                self.check_diagnostic(caught.exception, child, "exit code 23")
                self.assertEqual(child.log.read_text().strip(), output)
                self.check_stopped(child)

    def test_live_child_without_port_times_out_and_is_reaped(self):
        child = self.child("no-port", "import time; print('still running', flush=True); time.sleep(60)")
        with self.assertRaises(RuntimeError) as caught:
            with child.serving(1.5):
                self.fail("The child did not publish a port")
        self.check_diagnostic(caught.exception, child, "no port in log after 1.5s")
        self.assertEqual(child.log.read_text().strip(), "still running")
        self.assertIn("terminate confirmed child exit", " ".join(caught.exception.__notes__))
        self.check_stopped(child)

    def test_normal_exit_and_scenario_errors_reap_live_children(self):
        for stage, error in (("normal", None), ("scenario-error", ValueError("first failure")),
                             ("interrupted", KeyboardInterrupt("scenario interrupted"))):
            with self.subTest(stage=stage):
                child = self.child(stage, "import time; print('Tomcat started on port 43123', flush=True); time.sleep(60)")
                try:
                    with child.serving(5) as port:
                        self.assertEqual(port, 43123)
                        self.assertIsNone(child.process.poll())
                        if error is not None:
                            raise error
                except (ValueError, KeyboardInterrupt) as caught:
                    self.assertIs(caught, error)
                    self.assertIn("terminate confirmed child exit", " ".join(caught.__notes__))
                    self.assertIn(str(child.log), " ".join(caught.__notes__))
                    print(f"{caught}. {caught.__notes__[0]}", flush=True)
                else:
                    self.assertIsNone(error)
                self.check_stopped(child)

    def test_launch_failure_keeps_log_and_hides_arguments(self):
        child = LoggedProcess([str(self.directory / "not-an-executable"), "fixture-secret"],
                              self.directory / "launch.log", stage="missing-executable",
                              cwd=ROOT, env=os.environ, stop_timeout=3)
        with self.assertRaises(RuntimeError) as caught:
            with child.serving(1):
                self.fail("The executable does not exist")
        self.check_diagnostic(caught.exception, child, "could not launch child (FileNotFoundError)")
        self.assertNotIn("fixture-secret", str(caught.exception))

    def test_one_shot_returns_expected_nonzero_and_full_log(self):
        jars = Jars.__new__(Jars)
        child = self.child("preflight", "import sys; print('preflight', flush=True); "
                           "print('expected rejection', file=sys.stderr); sys.exit(7)")
        with patch.object(jars, "launch", return_value=child):
            code, output = jars.command(os.environ, "preflight")
        self.assertEqual(code, 7)
        self.assertEqual(output.splitlines(), ["preflight", "expected rejection"])
        self.check_stopped(child)

    def test_terminate_timeout_forces_stop_with_a_second_bounded_wait(self):
        child = self.child("force-stop", "")
        proc = Mock(spec=subprocess.Popen)
        proc.poll.return_value = None
        proc.wait.side_effect = [subprocess.TimeoutExpired(["fixture-secret"], 3), -9]
        output = io.StringIO()
        with patch("jar_process.subprocess.Popen", return_value=proc), redirect_stderr(output):
            with child:
                pass
        self.assertEqual(proc.method_calls, [call.poll(), call.terminate(), call.wait(timeout=3),
                                             call.kill(), call.wait(timeout=3)])
        self.assertIn("kill after a 3s terminate wait confirmed child exit (exit code -9)", output.getvalue())
        self.assertNotIn("fixture-secret", output.getvalue())
        print(f"Shutdown double ({os.name}): {output.getvalue().strip()}", flush=True)

    def test_failed_force_stop_preserves_primary_error(self):
        child = self.child("failed-force-stop", "")
        proc = Mock(spec=subprocess.Popen)
        proc.poll.return_value = None
        proc.wait.side_effect = subprocess.TimeoutExpired(["fixture-secret"], 3)
        primary = ValueError("first failure")
        with patch("jar_process.subprocess.Popen", return_value=proc):
            with self.assertRaises(ValueError) as caught:
                with child:
                    raise primary
        self.assertIs(caught.exception, primary)
        note = primary.__notes__[0]
        self.assertIn("did not confirm child exit (TimeoutExpired)", note)
        self.assertIn(str(child.log), note)
        self.assertNotIn("fixture-secret", note)
        print(f"Shutdown double ({os.name}): {primary}. {note}", flush=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
