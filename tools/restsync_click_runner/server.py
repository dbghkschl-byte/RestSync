"""Loopback-only launcher for the fixed RestSync development-emulator click tour.

No HTTP input is interpreted as a command, path, device serial or script name.
The runner module validates that its target is an Android development emulator.
"""
from __future__ import annotations

import json
import threading
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOST = "127.0.0.1"
PORT = 8765
SERVICE = "restsync-emulator-click-runner"
HEADER_VALUE = "emulator-only-v1"
BASE_DIR = Path(__file__).resolve().parent


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class RunStopped(RuntimeError):
    """Cooperative cancellation; never launch a second UI injector over this run."""


class Controller:
    def __init__(self, manager: "RunManager", run_dir: Path):
        self.manager = manager
        self.run_dir = run_dir
        self.stop_event = threading.Event()
        self.pause_event = threading.Event()

    def checkpoint(self) -> None:
        while True:
            if self.stop_event.is_set():
                raise RunStopped("시연 자동 클릭을 중지했어요.")
            if not self.pause_event.is_set():
                return
            if self.stop_event.wait(0.15):
                raise RunStopped("시연 자동 클릭을 중지했어요.")

    def update(self, fields: dict[str, Any]) -> None:
        if not isinstance(fields, dict):
            raise TypeError("Controller.update expects a dictionary")
        # The trusted runner may publish progress, but cannot replace ownership fields.
        values = {key: value for key, value in fields.items()
                  if key not in {"runId", "runDir", "running", "paused", "state"}}
        self.manager.runner_update(self, values)


class RunManager:
    def __init__(self, base_dir: Path = BASE_DIR):
        self.base_dir = base_dir
        self.lock = threading.RLock()
        self.operation_lock = threading.Lock()
        self.controller: Controller | None = None
        self.worker: threading.Thread | None = None
        self.ticket = 0
        self.operation_pending = False
        self.status: dict[str, Any] = {
            "state": "idle", "running": False, "paused": False,
            "service": SERVICE, "version": 1, "updatedAt": utc_now(),
            "message": "RestSync 설정에서 ‘시연 영상’을 누르면 시작해요.",
        }

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            return json.loads(json.dumps(self.status, ensure_ascii=False))

    def _publish(self, **values: Any) -> None:
        with self.lock:
            revised = dict(self.status)
            revised.update(values)
            revised["updatedAt"] = utc_now()
            encoded = json.dumps(revised, ensure_ascii=False, indent=2, default=str)
            self.status = json.loads(encoded)
            temporary = self.base_dir / "status.json.tmp"
            temporary.write_text(encoded, encoding="utf-8")
            temporary.replace(self.base_dir / "status.json")

    def runner_update(self, controller: Controller, values: dict[str, Any]) -> None:
        with self.lock:
            if controller is not self.controller:
                return
            # Validate serializability before changing the public status file.
            json.dumps(values, ensure_ascii=False)
            self._publish(**values)

    def request(self, action: str) -> tuple[int, dict[str, Any]]:
        if action not in {"start", "pause", "resume", "restart", "stop"}:
            raise ValueError("Unsupported fixed action")
        with self.lock:
            live = self.worker is not None and self.worker.is_alive()
            if action in {"pause", "resume"}:
                if self.controller is not None and live:
                    if action == "pause":
                        self.controller.pause_event.set()
                    else:
                        self.controller.pause_event.clear()
                    self._publish(state="paused" if action == "pause" else "running",
                                  running=True, paused=action == "pause")
                return 200, self.snapshot()
            if action == "start" and (live or self.operation_pending):
                return 200, self.snapshot()
            self.ticket += 1
            ticket = self.ticket
            self.operation_pending = True
            if action in {"restart", "stop"} and self.controller is not None:
                self.controller.stop_event.set()
                self.controller.pause_event.clear()
            self._publish(state={"start": "starting", "restart": "restarting", "stop": "stopping"}[action],
                          running=live, paused=False,
                          message={"start": "실제 화면 자동 클릭을 준비하고 있어요.",
                                   "restart": "이전 클릭 실행이 끝난 뒤 다시 시작해요.",
                                   "stop": "진행 중인 클릭을 안전하게 마치고 중지해요."}[action])
            threading.Thread(target=self._operate, args=(action, ticket), daemon=True,
                             name=f"restsync-control-{ticket}").start()
            return 202, self.snapshot()

    def _operate(self, action: str, ticket: int) -> None:
        # Only this lock may replace a worker. A restart never overlaps UI injectors.
        with self.operation_lock:
            with self.lock:
                if ticket != self.ticket:
                    return
                previous = self.worker
                previous_controller = self.controller
            if previous is not None and previous.is_alive():
                if previous_controller is not None:
                    previous_controller.stop_event.set()
                    previous_controller.pause_event.clear()
                previous.join(timeout=55)
                if previous.is_alive():
                    with self.lock:
                        if ticket == self.ticket:
                            self.operation_pending = False
                            self._publish(state="failed", running=True, paused=False,
                                          message="이전 실행을 중지하는 중이라 새 자동 클릭을 시작하지 않았어요.")
                    return
            with self.lock:
                if ticket != self.ticket:
                    return
                self.operation_pending = False
                if action == "stop":
                    self._publish(state="stopped", running=False, paused=False,
                                  message="자동 클릭을 중지했어요. 실제 경로는 앱에서 확인하세요.")
                    return
                run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
                run_dir = self.base_dir / "runs" / run_id
                run_dir.mkdir(parents=True, exist_ok=False)
                controller = Controller(self, run_dir)
                self.controller = controller
                self._publish(state="running", running=True, paused=False, runId=run_id,
                              runDir=str(run_dir), error=None, result=None,
                              message="RestSync와 Pleos의 실제 화면을 자동 클릭하고 있어요.")
                worker = threading.Thread(target=self._run, args=(controller,), daemon=True,
                                          name="restsync-click-runner")
                self.worker = worker
                worker.start()

    def _run(self, controller: Controller) -> None:
        try:
            import importlib
            import runner
            if getattr(runner,"__spec__",None) is not None:
                runner=importlib.reload(runner)
            run_demo=runner.run_demo
            controller.checkpoint()
            result = run_demo(controller, controller.run_dir, record=True)
            with self.lock:
                if controller is self.controller and not self.operation_pending:
                    stopped = controller.stop_event.is_set()
                    self._publish(state="stopped" if stopped else "finished", running=False, paused=False,
                                  result=result,
                                  message="자동 클릭을 중지했어요." if stopped else "실제 연동 시연과 녹화를 마쳤어요.")
        except Exception as exc:
            with self.lock:
                if controller is self.controller and not self.operation_pending:
                    stopped = controller.stop_event.is_set() or isinstance(exc, RunStopped)
                    self._publish(state="stopped" if stopped else "failed", running=False, paused=False,
                                  error=None if stopped else str(exc)[:600],
                                  message="자동 클릭을 중지했어요." if stopped else "자동 클릭을 멈췄어요. 마지막 화면과 오류를 확인하세요.")


MANAGER = RunManager()


class Handler(BaseHTTPRequestHandler):
    server_version = "RestSyncDemo/1"

    def log_message(self, *_args: Any) -> None:
        pass

    def _json(self, code: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(encoded)

    def _local_request(self) -> bool:
        host = self.headers.get("Host", "").lower().split(":", 1)[0]
        return not self.headers.get("Origin") and host in {"127.0.0.1", "localhost", "10.0.2.2"}

    def do_GET(self) -> None:
        if not self._local_request():
            self._json(403, {"ok": False, "error": "Loopback native-client requests only"})
        elif self.path == "/health":
            self._json(200, {"ok": True, "service": SERVICE, "version": 1})
        elif self.path == "/status":
            self._json(200, MANAGER.snapshot())
        else:
            self._json(404, {"ok": False, "error": "Unknown fixed endpoint"})

    def do_POST(self) -> None:
        if not self._local_request() or self.headers.get("X-RestSync-Demo") != HEADER_VALUE:
            self._json(403, {"ok": False, "error": "Development emulator client required"})
            return
        if self.path not in {"/start", "/pause", "/resume", "/restart", "/stop"}:
            self._json(404, {"ok": False, "error": "Unknown fixed endpoint"})
            return
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
            self._json(415, {"ok": False, "error": "application/json required"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 2048:
                self._json(413, {"ok": False, "error": "Request too large"})
                return
            body = self.rfile.read(length)
            value = json.loads(body or b"{}")
            if value != {} or not isinstance(value, dict):
                self._json(400, {"ok": False, "error": "Only an empty fixed-action object is accepted"})
                return
        except (ValueError, UnicodeDecodeError):
            self._json(400, {"ok": False, "error": "Invalid JSON request"})
            return
        status, payload = MANAGER.request(self.path[1:])
        self._json(status, payload)

    def do_OPTIONS(self) -> None:
        self._json(403, {"ok": False, "error": "Browser cross-origin actions are not supported"})


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"RestSync development-emulator click runner listening on http://{HOST}:{PORT}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        MANAGER.request("stop")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
