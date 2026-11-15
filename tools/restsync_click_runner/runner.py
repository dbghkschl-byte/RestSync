"""Fixed, emulator-only UI automation for RestSync's real integrated demo.

This module clicks the existing application screens. It does not edit app
preferences, invent navigation results, grant permissions, or change GPS/P gear.
Only the explicitly confirmed development emulator is eligible.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET


class AutomationError(RuntimeError):
    pass


class AutomationStopped(AutomationError):
    pass


_NATIVE_COMPONENT = re.compile(r"\bai\.umos\.maps(?:\.[A-Za-z0-9_]+)*/")


def is_native_component(line):
    """Accept both SDK shorthand and installed Pleos navigation package names."""
    return bool(_NATIVE_COMPONENT.search(line))


def has_native_foreground(activity_dump):
    """AAOS can report several resumed displays; any real map display counts."""
    return any("topResumedActivity=" in line and is_native_component(line)
               for line in activity_dump.splitlines())


def is_own_component(line):
    return bool(re.search(r"(?<![A-Za-z0-9_.])com\.restsync\.demo/", line))


def is_own_main_component(line):
    match = re.search(r"(?<![A-Za-z0-9_.])com\.restsync\.demo/([A-Za-z0-9_.$]+)", line)
    return bool(match and match.group(1) in (
        ".MainActivity", ".FullScreenActivity",
        "com.restsync.demo.MainActivity", "com.restsync.demo.FullScreenActivity"))


def _find_adb(explicit=None):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    elif os.environ.get("RESTSYNC_ADB"):
        candidates.append(Path(os.environ["RESTSYNC_ADB"]))
    else:
        if os.environ.get("LOCALAPPDATA"):
            candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
        found = shutil.which("adb")
        if found:
            candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate.resolve())
    raise AutomationError("Android SDK의 adb를 찾지 못했어요. RESTSYNC_ADB에 adb 경로를 지정해 주세요.")


class _Runner:
    PACKAGE = "com.restsync.demo"

    def __init__(self, controller, run_dir, *, record, serial, adb_path):
        self.controller = controller
        self.run_dir = Path(run_dir).resolve()
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.adb_path = _find_adb(adb_path)
        self.serial = serial
        self.user = None
        self.record = bool(record)
        self.started = False
        self.pause_seconds = 0.0
        self.last_pause_poll = 0.0
        self.recorder = None
        self.record_log = None
        self.record_pids = set()
        self.remote_video = None
        self.emulator_video = None
        self.recording_started_at = None
        self.log = (self.run_dir / "runner.log").open("a", encoding="utf-8", buffering=1)
        self.observed = []
        self.native_observed = []
        self.selected_stop = ""
        self.final_destination = ""
        self.pending_destination = False
        self.pending_destination_since = 0.0
        self.pending_waypoint = False
        self.pending_waypoint_since = 0.0
        self.manual_waypoint_wait_started = 0.0
        self.live_search_seen = False
        self.last_step = None
        self.step_entered_at = 0.0
        self.handled = set()
        self.result = {"passed": False, "actualUiClicks": True, "physicalDevicesAllowed": False}

    def update(self, stage, message, progress, **extra):
        self.log.write(f"{time.strftime('%H:%M:%S')} {stage}: {message}\n")
        self.log_event("stage", stage=stage, message=message)
        self.controller.update(dict(stage=stage, message=message, progress=progress, **extra))

    def log_event(self, kind, **fields):
        started = getattr(self, "recording_started_at", None)
        if started is None:
            return
        event = dict(kind=kind, time=round(time.monotonic()-started, 3), **fields)
        with (self.run_dir / "pointer-events.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event, ensure_ascii=False)+"\n")

    def checkpoint(self):
        begin = time.monotonic()
        self.controller.checkpoint()
        self.pause_seconds += max(0.0, time.monotonic() - begin)
        now = time.monotonic()
        if not self.started or self.user is None or now - self.last_pause_poll < .35:
            return
        self.last_pause_poll = now
        state = self.raw_pref("restsync_recording_tour_v1", missing_ok=True)
        if self.started and state.get("active") == "false" and state.get("step") != "DONE":
            raise AutomationStopped("앱에서 시연 안내가 종료되어 자동 클릭을 멈췄어요.")
        while state.get("active") == "true" and state.get("paused") == "true":
            begin = time.monotonic()
            self.controller.checkpoint()
            time.sleep(.15)
            self.pause_seconds += max(0.0, time.monotonic() - begin)
            state = self.raw_pref("restsync_recording_tour_v1", missing_ok=True)

    def active_time(self):
        return time.monotonic() - self.pause_seconds

    def sleep(self, seconds):
        end = self.active_time() + seconds
        while self.active_time() < end:
            self.checkpoint()
            time.sleep(min(.15, max(.01, end - self.active_time())))

    def adb(self, *args, check=True, timeout=35, checkpoint=True):
        if checkpoint:
            self.checkpoint()
        command = [self.adb_path]
        if self.serial:
            command += ["-s", self.serial]
        result = subprocess.run(command + list(map(str, args)), capture_output=True, timeout=timeout)
        if check and result.returncode:
            detail = (result.stdout + result.stderr).decode("utf-8", errors="replace")[-1200:]
            raise AutomationError(f"ADB 요청 실패: {detail}")
        return result.stdout

    def raw_pref(self, name, missing_ok=False):
        # These two application state files contain no API key; no other prefs are read.
        if name not in ("restsync_session_v1", "restsync_recording_tour_v1"):
            raise AutomationError("허용되지 않은 시연 상태 파일")
        data = self.adb("shell", "run-as", self.PACKAGE, "--user", self.user,
                        "cat", "shared_prefs/" + name + ".xml", check=not missing_ok,
                        checkpoint=False)
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            if missing_ok:
                return {}
            raise AutomationError("앱의 시연 상태를 읽지 못했어요.")
        return {node.get("name"): node.get("value", node.text) for node in root}

    def session(self):
        self.checkpoint()
        return self.raw_pref("restsync_session_v1")

    def tour(self):
        self.checkpoint()
        return self.raw_pref("restsync_recording_tour_v1", missing_ok=True)

    def preflight(self):
        if self.serial is None:
            lines = self.adb("devices", checkpoint=False).decode(errors="replace").splitlines()
            serials = [line.split()[0] for line in lines if len(line.split()) >= 2
                       and line.split()[1] == "device" and line.split()[0].startswith("emulator-")]
            if len(serials) != 1:
                raise AutomationError("실행 중인 개발용 에뮬레이터가 한 대여야 해요. 실제 차량·휴대폰은 제어하지 않습니다.")
            self.serial = serials[0]
        if not re.fullmatch(r"emulator-\d+", str(self.serial)):
            raise AutomationError("이 자동 클릭은 개발용 에뮬레이터에서만 실행할 수 있어요.")
        qemu = self.adb("shell", "getprop", "ro.kernel.qemu", checkpoint=False).decode().strip()
        if qemu != "1":
            raise AutomationError("Android 에뮬레이터 확인에 실패했어요. 실제 기기에서는 실행하지 않습니다.")
        user = self.adb("shell", "am", "get-current-user", checkpoint=False).decode().strip()
        if not user.isdigit():
            raise AutomationError("현재 Android 사용자를 확인하지 못했어요.")
        self.user = user
        installed = self.adb("shell", "pm", "path", "--user", user, self.PACKAGE, checkpoint=False)
        if b"package:" not in installed:
            raise AutomationError("현재 에뮬레이터 사용자에게 RestSync를 설치해 주세요.")
        session = self.raw_pref("restsync_session_v1")
        tour = self.raw_pref("restsync_recording_tour_v1", missing_ok=True)
        automated = tour.get("automated") == "true" or tour.get("autoClick") == "true"
        if session.get("stage") in ("RESTING", "DEPARTURE") and not (tour.get("active") == "true" and automated):
            raise AutomationError("실제 휴식이 진행 중이에요. 현재 휴식을 마친 뒤 자동 시연을 시작해 주세요.")
        self.result.update(emulator=self.serial, androidUser=int(user), actualSourceBefore=session.get("source"))
        self.update("ready", "개발용 Pleos 에뮬레이터를 확인했어요.", 1)

    def ui(self):
        self.checkpoint()
        remote = "/sdcard/restsync-auto-click-ui.xml"
        # A failed idle wait must never cause a stale XML snapshot to be clicked.
        self.adb("shell", "rm", "-f", remote)
        output = self.adb("shell", "uiautomator", "dump", "--compressed", remote,
                          check=False, timeout=25)
        if b"dumped to" not in output:
            raise AutomationError("현재 화면이 갱신 중이라 버튼 위치를 다시 확인하고 있어요.")
        data = self.adb("shell", "cat", remote)
        root = ET.fromstring(data)
        return root

    @staticmethod
    def texts(tree):
        return [node.get("text", "") for node in tree.iter("node") if node.get("text")]

    @staticmethod
    def enabled(node):
        if node.get("enabled") == "false":
            return False
        bounds = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
        return len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1] and bounds[0] >= 0 and bounds[1] >= 0

    def tap_node(self, node, expected_step=None):
        self.checkpoint()
        if expected_step and self.tour().get("step") != expected_step:
            return False
        if node.get("package") != self.PACKAGE or not self.enabled(node):
            return False
        # A route request can foreground native navigation between XML and input.
        # Do not send a stale RestSync coordinate to that newly foreground app.
        if not is_own_component(self.component()):
            return False
        x0, y0, x1, y1 = map(int, re.findall(r"-?\d+", node.get("bounds")))
        x, y = (x0 + x1) // 2, (y0 + y1) // 2
        self.log_event("move", x=x, y=y, label=node.get("text", ""))
        # Draw a mouse-shaped recording cue in the focused app/dialog window.
        # This broadcast draws only; the original UI input below still performs the action.
        self.adb("shell", "am", "broadcast", "--user", self.user, "-a",
                 "com.restsync.demo.DEMO_POINTER", "-p", self.PACKAGE,
                 "--ei", "x", x, "--ei", "y", y)
        self.sleep(.7)
        if expected_step and self.tour().get("step") != expected_step:
            return False
        if not is_own_component(self.component()):
            return False
        # Position comes from the current UI XML, never from a fixed screen coordinate.
        self.adb("shell", "input", "tap", x, y)
        self.log_event("click", x=x, y=y, label=node.get("text", ""))
        return True

    def tap_text(self, text, *, contains=False, expected_step=None, timeout=25, prefer_button=False):
        deadline = self.active_time() + timeout
        last_error = None
        while self.active_time() < deadline:
            self.checkpoint()
            if expected_step and self.tour().get("step") != expected_step:
                return False
            try:
                tree = self.ui()
                matches = [node for node in tree.iter("node") if self.enabled(node)
                           and (node.get("text") == text or contains and text in node.get("text", ""))]
                if prefer_button:
                    matches.sort(key=lambda n: n.get("class") != "android.widget.Button")
                if matches:
                    return self.tap_node(matches[0], expected_step)
            except (AutomationError, ET.ParseError, subprocess.TimeoutExpired) as exc:
                last_error = exc
            self.sleep(.3)
        raise AutomationError(f"현재 화면에서 ‘{text}’ 버튼을 찾지 못했어요." + (f" ({last_error})" if last_error else ""))

    def component(self):
        text = self.adb("shell", "dumpsys", "activity", "activities").decode(errors="replace")
        lines = [line for line in text.splitlines() if "topResumedActivity=" in line]
        own = next((line for line in lines if self.PACKAGE + "/" in line), None)
        if own:
            return own
        return next((line for line in lines if is_native_component(line)), "")

    def native_hold(self, name, timeout=12):
        deadline = self.active_time() + timeout
        while self.active_time() < deadline:
            text = self.adb("shell", "dumpsys", "activity", "activities").decode(errors="replace")
            if has_native_foreground(text):
                self.sleep(2)
                self.shot(name)
                self.native_observed.append(name)
                return True
            self.sleep(.3)
        # Do not fabricate a native screen when the SDK handoff did not happen.
        raise AutomationError("Pleos 실제 내비 화면 전환을 확인하지 못했어요. 내비 연결 상태를 확인해 주세요.")

    def open_main(self, action=None):
        args = ["shell", "am", "start", "--user", self.user, "-f", "0x24000000", "-n", self.PACKAGE + "/.MainActivity"]
        if action:
            args += ["--es", "recordingTourAction", action]
        self.adb(*args)
        self.sleep(.5)

    def shot(self, name):
        filename = re.sub(r"[^A-Za-z0-9_-]", "_", name) + ".png"
        (self.run_dir / filename).write_bytes(self.adb("exec-out", "screencap", "-p"))

    def destination(self, step):
        if self.pending_destination:
            if self.active_time() - self.pending_destination_since > 28:
                raise AutomationError("Pleos 목적지 등록 확인이 늦어져 자동 클릭을 멈췄어요. 실제 내비를 확인해 주세요.")
            self.sleep(.3)
            return
        component = self.component()
        if "DestinationSearchActivity" not in component:
            if step == "ROUTE_RESET" and "destination_native" not in self.handled:
                self.native_hold("01_native_destination")
                self.handled.add("destination_native")
            self.open_main()
            self.tap_text("다음 장면", expected_step=step)
            self.sleep(.6)
            return
        tree = self.ui()
        texts = self.texts(tree)
        if "목적지 설정" in texts and any("Pleos 내비 목적지로 설정할까요" in value for value in texts):
            if self.tap_text("설정", expected_step=step, prefer_button=True):
                self.pending_destination = True
                self.pending_destination_since = self.active_time()
            return
        actual_results = any("검색 결과" in value and "OpenStreetMap" in value for value in texts)
        if not actual_results:
            if any("인터넷 연결" in value or "HTTP" in value or "검색 결과가 없" in value for value in texts):
                raise AutomationError("실제 목적지 검색 결과를 받지 못했어요. 시연 예시 장소로 대체하지 않습니다.")
            self.sleep(.5)
            return
        # Only a real result cell named 부산역 is eligible, never the preloaded demo examples.
        parents = {child: parent for parent in tree.iter() for child in parent}
        names = [node for node in tree.iter("node") if node.get("text") == "부산역"]
        target_button = None
        for name in names:
            parent = parents.get(name)
            for _ in range(3):
                if parent is None:
                    break
                target_button = next((n for n in parent.iter("node") if "Pleos 내비에 목적지 설정" in n.get("text", "") and self.enabled(n)), None)
                if target_button is not None:
                    break
                parent = parents.get(parent)
            if target_button is not None:
                break
        if target_button is None:
            raise AutomationError("실제 검색 결과에서 부산역을 확인하지 못했어요. 다른 장소를 자동 선택하지 않습니다.")
        self.shot("00_actual_destination_search" if step == "DESTINATION" else "02_actual_route_reset")
        if self.tap_node(target_button, step):
            if self.tap_text("설정", expected_step=step, prefer_button=True):
                self.pending_destination = True
                self.pending_destination_since = self.active_time()

    def recommendations(self):
        if self.pending_waypoint:
            if self.active_time() - self.pending_waypoint_since > 28:
                raise AutomationError("실제 경유지 등록 확인이 끝나지 않았어요. 같은 경유지를 다시 추가하지 않고 멈춥니다.")
            self.sleep(.3)
            return
        component = self.component()
        if "LiveRouteActivity" not in component:
            if self.live_search_seen:
                if not self.manual_waypoint_wait_started:
                    self.manual_waypoint_wait_started = self.active_time()
                if self.active_time() - self.manual_waypoint_wait_started > 28:
                    raise AutomationError("앱을 벗어난 뒤 경유지 등록 확인이 끝나지 않았어요. 실제 내비를 확인해 주세요.")
                self.sleep(.3)
                return
            if "route_reset_native" not in self.handled:
                self.native_hold("03_native_route_reset")
                self.handled.add("route_reset_native")
            self.open_main()
            self.tap_text("다음 장면", expected_step="REST_SEARCH")
            self.sleep(.5)
            return
        self.live_search_seen = True
        tree = self.ui()
        texts = self.texts(tree)
        if any("설정 키" in value or "검색 키를 저장" in value or "HTTP 401" in value or "HTTP 403" in value for value in texts):
            raise AutomationError("실제 휴게소 추천에 필요한 저장된 길찾기 키 설정을 확인해 주세요.")
        buttons = [node for node in tree.iter("node") if "이 휴게소로 안내" in node.get("text", "") and self.enabled(node)]
        if buttons:
            self.shot("04_actual_rest_recommendations")
            if self.tap_node(buttons[0], "REST_SEARCH"):
                self.pending_waypoint = True
                self.pending_waypoint_since = self.active_time()
        elif any("찾지 못했" in value or "실패" in value or "응답이 없" in value for value in texts):
            raise AutomationError("실제 휴게소 추천을 완료하지 못했어요. 확인되지 않은 휴게소는 선택하지 않습니다.")
        else:
            self.sleep(.4)

    def start_recording(self):
        if not self.record:
            return
        # Emulator console recording supports >180 seconds and includes app cursor cues.
        target = self.run_dir / "RestSync_full_demo.webm"
        self.recording_started_at = time.monotonic()
        response = self.adb("emu", "screenrecord", "start", "--size", "1920x1080",
                            "--bit-rate", "4M", "--fps", "24", "--time-limit", "900",
                            str(target).replace("\\", "/"), check=False).decode(errors="replace")
        if "OK" in response and "KO:" not in response:
            self.emulator_video = target
            self.sleep(.8)
            return
        old = set(self.adb("shell", "pidof", "screenrecord", check=False).decode().split())
        stamp = str(int(time.time() * 1000))
        self.remote_video = "/sdcard/restsync-auto-click-" + stamp + ".mp4"
        self.record_log = (self.run_dir / "screenrecord.log").open("wb")
        self.recorder = subprocess.Popen([self.adb_path, "-s", self.serial, "shell", "screenrecord",
                                          "--size", "1920x1080", "--bit-rate", "2500000", "--time-limit", "180",
                                          self.remote_video], stdout=self.record_log, stderr=subprocess.STDOUT)
        self.sleep(.8)
        new = set(self.adb("shell", "pidof", "screenrecord", check=False).decode().split())
        self.record_pids = {pid for pid in new - old if pid.isdigit()}

    def finish_recording(self):
        if self.emulator_video is not None:
            self.adb("emu", "screenrecord", "stop", check=False, checkpoint=False)
            target = self.emulator_video
            if target.is_file() and target.read_bytes()[:4] == bytes.fromhex("1a45dfa3"):
                self.result.update(video=target.name, videoBytes=target.stat().st_size,
                                   videoHasAudio=False, videoFullWalkthrough=self.result.get("passed") is True,
                                   videoBackend="emulator-console", videoMaximumSeconds=900)
            self.emulator_video = None
            return
        if self.recorder is None:
            return
        # This cleanup intentionally bypasses pause/stop checkpoints so MP4 can finalize.
        for pid in self.record_pids:
            self.adb("shell", "kill", "-2", pid, check=False, checkpoint=False)
        try:
            self.recorder.wait(timeout=12)
        except subprocess.TimeoutExpired:
            self.recorder.terminate()
            self.recorder.wait(timeout=5)
        if self.record_log:
            self.record_log.close()
        target = self.run_dir / "RestSync_실제앱_자동클릭_시연.mp4"
        pulled = subprocess.run([self.adb_path, "-s", self.serial, "pull", self.remote_video, str(target)], capture_output=True, timeout=30)
        if pulled.returncode == 0 and target.is_file() and b"ftyp" in target.read_bytes()[:32]:
            self.result.update(video=target.name, videoBytes=target.stat().st_size, videoHasAudio=False,
                               videoFullWalkthrough=False, videoMaximumSeconds=180)
        self.recorder = None

    def run(self):
        self.preflight()
        self.start_recording()
        self.update("starting", "실제 앱의 자동 클릭 시연을 시작해요.", 3)
        before = self.raw_pref("restsync_recording_tour_v1", missing_ok=True)
        self.open_main("runner-start")
        # A stopped/covered Activity can receive its intent after a task transition.
        # Wait for a fresh app acknowledgement before enabling the stop-state watchdog.
        deadline = self.active_time() + 20
        while True:
            state = self.raw_pref("restsync_recording_tour_v1", missing_ok=True)
            if self.start_acknowledged(before, state):
                break
            if self.active_time() >= deadline:
                raise AutomationError("앱의 시연 시작 응답을 확인하지 못했어요. 현재 화면을 확인해 주세요.")
            self.sleep(.2)
        self.started = True
        end = self.active_time() + 420
        stage_progress = {"DESTINATION": 5, "ROUTE_RESET": 20, "REST_SEARCH": 35, "ARRIVAL": 50,
                          "WAIT_ETA": 57, "REST30": 63, "REST35": 69, "TRAFFIC": 76,
                          "ACCELERATE": 83, "ALARM": 89, "COMPLETE": 94, "DONE": 100,
                          "CLEANUP": 2, "RESTART_PENDING": 2}
        messages = {"DESTINATION": "실제 목적지 검색 결과를 선택해요.", "ROUTE_RESET": "실제 경로를 다시 설정해요.",
                    "REST_SEARCH": "실제 경로의 휴게소를 찾아 경유지로 선택해요.", "ARRIVAL": "에뮬레이터에서 도착·정차 확인 화면을 누르고 있어요.",
                    "WAIT_ETA": "실제 Pleos ETA를 기다리고 있어요.", "REST30": "30분 휴식을 보여드린 뒤 +5분을 눌러요.",
                    "REST35": "35분 휴식에서 정체 +20분 시연을 눌러요.", "TRAFFIC": "정체 반영 약 15분 휴식을 보여드려요.",
                    "ACCELERATE": "녹화용 시간 가속 뒤 실제 출발 알림을 기다려요.", "ALARM": "실제 출발 알림의 확인 버튼을 눌러요.",
                    "COMPLETE": "휴식 완료 후 실제 내비 안내를 이어가요.", "DONE": "실제 앱 자동 클릭 시연을 마쳤어요.",
                    "CLEANUP": "기존 시연 경유지를 확인·정리하고 있어요.", "RESTART_PENDING": "기존 시연을 정리한 뒤 다시 시작해요."}
        while self.active_time() < end:
            self.checkpoint()
            tour = self.tour()
            step = tour.get("step")
            session = self.session()
            if step != self.last_step:
                self.last_step = step
                self.step_entered_at = self.active_time()
                self.observed.append(step)
                self.pending_destination = False
                self.update(step.lower() if step else "waiting", messages.get(step, "실제 앱 응답을 기다리고 있어요."), stage_progress.get(step, 3))
                if step in ("ROUTE_RESET", "REST_SEARCH", "ARRIVAL", "COMPLETE"):
                    self.sleep(.7)
            elapsed = self.active_time() - self.step_entered_at
            if step in ("CLEANUP", "RESTART_PENDING"):
                if elapsed > 35:
                    raise AutomationError("기존 경유지 정리를 확인하지 못했어요. 실제 내비에서 확인해 주세요.")
                self.sleep(.4)
            elif step in ("DESTINATION", "ROUTE_RESET"):
                if elapsed > 70:
                    raise AutomationError("실제 목적지 검색·등록을 기다리는 시간이 초과됐어요.")
                self.destination(step)
            elif step == "REST_SEARCH":
                if elapsed > 80:
                    raise AutomationError("실제 휴게소 검색·등록을 기다리는 시간이 초과됐어요.")
                self.recommendations()
            elif step == "ARRIVAL":
                self.selected_stop = session.get("stop", "")
                self.final_destination = session.get("destinationName", "")
                if session.get("source") != "PLEOS" or not self.selected_stop:
                    raise AutomationError("실제 경유지 확인 전에는 도착 확인을 자동 클릭하지 않습니다.")
                if "waypoint_native" not in self.handled:
                    self.native_hold("05_native_waypoint")
                    self.handled.add("waypoint_native")
                component = self.component()
                if not is_own_main_component(component):
                    self.open_main()
                texts = self.texts(self.ui())
                # A person may already have opened the duration/P confirmation.
                if "정차·P단 확인 · 휴식 시작" in texts:
                    self.tap_text("정차·P단 확인 · 휴식 시작", expected_step="ARRIVAL", prefer_button=True)
                elif "선택한 시간으로 계속" in texts:
                    self.tap_text("선택한 시간으로 계속", expected_step="ARRIVAL")
                else:
                    self.tap_text("휴게소 도착", expected_step="ARRIVAL")
            elif step == "WAIT_ETA":
                if elapsed > 30:
                    raise AutomationError("첫 실제 Pleos ETA를 받지 못했어요. 가상 ETA로 대체하지 않습니다.")
                self.sleep(.35)
            elif step == "REST30":
                if session.get("stage") != "RESTING":
                    self.sleep(.2); continue
                if session.get("requested") == "30":
                    if "rest30" not in self.handled:
                        self.shot("06_actual_rest_30min"); self.sleep(3); self.handled.add("rest30")
                    self.tap_text("+ 5분 연장", expected_step="REST30")
                elif session.get("requested") != "35":
                    raise AutomationError("휴식 설정이 30/35분 시나리오와 달라져 멈췄어요.")
                self.sleep(.3)
            elif step == "REST35":
                if int(session.get("simulatedDelayMinutes", "0")) < 20:
                    if "rest35" not in self.handled:
                        self.shot("07_actual_rest_35min"); self.sleep(3); self.handled.add("rest35")
                    if "교통 정체 +20분 · 시연" not in self.texts(self.ui()):
                        self.tap_text("설정", expected_step="REST35")
                    self.tap_text("교통 정체 +20분 · 시연", expected_step="REST35")
                self.sleep(.3)
            elif step == "TRAFFIC":
                if int(session.get("simulatedDelayMinutes", "0")) != 20:
                    raise AutomationError("정체 가산값이 20분과 달라져 중복 적용 없이 멈췄어요.")
                if "traffic" not in self.handled:
                    self.shot("08_actual_traffic_15min"); self.sleep(4); self.handled.add("traffic")
                self.tap_text("다음 장면", expected_step="TRAFFIC")
            elif step == "ACCELERATE":
                if "accelerated" not in self.handled:
                    self.shot("09_recording_time_acceleration"); self.handled.add("accelerated")
                if elapsed > 25:
                    raise AutomationError("실제 출발 알림 전환을 확인하지 못했어요.")
                self.sleep(.25)
            elif step == "ALARM":
                if session.get("stage") == "DEPARTURE":
                    if "alarm" not in self.handled:
                        self.shot("10_actual_departure_alarm"); self.sleep(3); self.handled.add("alarm")
                    self.tap_text("확인 · 휴식 마치기", expected_step="ALARM")
                self.sleep(.2)
            elif step == "COMPLETE":
                component = self.component()
                if "NavigationHandoffActivity" in component or is_native_component(component):
                    if elapsed > 30:
                        raise AutomationError("선택 경유지 정리·실제 내비 복귀 확인이 끝나지 않았어요.")
                    self.sleep(.3)
                elif session.get("stage") == "FINISHED":
                    if "complete" not in self.handled:
                        self.shot("11_actual_rest_complete"); self.sleep(3); self.handled.add("complete")
                    self.tap_text("내비 안내 계속하기", contains=True, expected_step="COMPLETE")
                else:
                    self.sleep(.25)
            elif step == "DONE":
                if session.get("stage") != "NAVIGATING" or session.get("source") != "PLEOS" or session.get("stop"):
                    raise AutomationError("최종 실제 내비 복귀 상태가 확인되지 않았어요.")
                if self.final_destination and session.get("destinationName") != self.final_destination:
                    raise AutomationError("휴식 후 최종 목적지가 달라져 확인이 필요해요.")
                self.native_hold("12_native_final_route")
                self.result.update(passed=True, stages=self.observed, actualDestinationConfirmations=int(tour.get("destinationVisits", "0")),
                                   selectedWaypoint=self.selected_stop, finalDestination=session.get("destinationName"),
                                   selectedWaypointStateCleared=True, finalStage=session.get("stage"),
                                   actualSource=session.get("source"), nativeScreensObserved=self.native_observed,
                                   arrivalAndParkConfirmation="ADB clicks in validated development emulator only",
                                   gpsOrGearChanged=False, permissionsOverridden=False)
                return self.result
            else:
                if elapsed > 15:
                    raise AutomationError("앱의 자동 시연 시작 응답을 확인하지 못했어요.")
                self.sleep(.3)
        raise AutomationError("자동 시연 전체 시간이 초과됐어요. 실제 앱 응답을 확인해 주세요.")

    @staticmethod
    def start_acknowledged(before, state):
        return (state.get("active") == "true" and state.get("automated") == "true"
                and (before.get("active") != "true" or state.get("since") != before.get("since")
                     or state.get("step") != before.get("step")))

    def close(self):
        try:
            self.finish_recording()
        finally:
            (self.run_dir / "result.json").write_text(json.dumps(self.result, ensure_ascii=False, indent=2), encoding="utf-8")
            self.log.close()


def run_demo(controller, run_dir=None, *, record=True, serial=None, adb_path=None):
    """Run the fixed flow; controller needs checkpoint(), update(dict), and run_dir."""
    target = run_dir if run_dir is not None else getattr(controller, "run_dir", None)
    if target is None:
        raise AutomationError("자동 시연 결과 폴더가 지정되지 않았어요.")
    runner = _Runner(controller, target, record=record, serial=serial, adb_path=adb_path)
    try:
        result = runner.run()
        runner.update("done", "실제 앱 자동 클릭 시연과 내비 복귀를 확인했어요.", 100)
        return result
    except BaseException as exc:
        runner.result.update(passed=False, error=str(exc), stages=runner.observed)
        try:
            runner.shot("error_last_screen")
        except BaseException:
            pass
        raise
    finally:
        runner.close()
