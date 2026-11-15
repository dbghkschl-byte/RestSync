package com.restsync.demo

/**
 * Deterministic recording scenario. All state is local: no SessionStore, network,
 * vehicle controls or native-navigation requests are used by this model.
 * The UI supplies active playback time, so pausing never consumes a scene.
 */
internal object DemoPlayback {
    const val TOTAL_DURATION_MS = 90_000L

    enum class Phase {
        DESTINATION_ENTRY, DESTINATION_SELECTED, NAVIGATION, REOPEN_MENU,
        RESET_DESTINATION, RESELECTED_DESTINATION, NAVIGATION_RESUMED,
        VOICE_SEARCH, RECOMMENDATIONS, WAYPOINT_SELECTED, ARRIVAL,
        REST_DURATION, PARK_CONFIRM, RESTING, EXTENDED, TRAFFIC_DELAY,
        TIME_SKIP, DEPARTURE, COMPLETE, DESTINATION_MAP, END
    }

    data class Candidate(
        val name: String,
        val direction: String,
        val approachMinutes: Long,
        val driveMinutes: Long,
        val restMinutes: Long
    )

    val candidates = listOf(
        Candidate("안성휴게소", "부산 방향", 12, 40, 35),
        Candidate("천안삼거리휴게소", "부산 방향", 24, 36, 27),
        Candidate("죽암휴게소", "부산 방향", 38, 30, 19)
    )

    private data class Scene(val phase: Phase, val startMs: Long, val title: String, val caption: String)
    private val scenes = listOf(
        Scene(Phase.DESTINATION_ENTRY, 0, "목적지 설정", "부산역을 검색해 목적지로 선택해요."),
        Scene(Phase.DESTINATION_SELECTED, 4_000, "목적지를 정했어요", "선택한 목적지로 지도 안내를 시작해요."),
        Scene(Phase.NAVIGATION, 7_000, "목적지까지 길 안내", "기본 내비로 이어지는 흐름을 시연용 지도로 보여드려요."),
        Scene(Phase.REOPEN_MENU, 12_000, "RestSync 다시 열기", "경로를 다시 정하거나 쉬어갈 곳을 바로 찾아요."),
        Scene(Phase.RESET_DESTINATION, 16_000, "경로 재탐색", "목적지 변경도 처음과 같은 검색 화면에서 해요."),
        Scene(Phase.RESELECTED_DESTINATION, 20_000, "목적지 다시 설정", "다시 선택한 부산역으로 안내를 이어갑니다."),
        Scene(Phase.NAVIGATION_RESUMED, 23_000, "지도 안내 계속", "길 안내 중에도 RestSync에서 휴식을 준비해요."),
        Scene(Phase.VOICE_SEARCH, 26_000, "“휴게소 찾아줘”", "음성 요청 흐름을 시연해요. 실제 음성 인식은 사용하지 않아요."),
        Scene(Phase.RECOMMENDATIONS, 30_000, "쉬어갈 휴게소 추천", "세 곳의 이동 시간과 휴식 여유를 비교해요. 추천 데이터는 시연용이에요."),
        Scene(Phase.WAYPOINT_SELECTED, 36_000, "휴게소를 경유지로 선택", "녹화용 이동 시간 가속으로 안성휴게소까지 약 12분 이동을 보여드려요."),
        Scene(Phase.ARRIVAL, 41_000, "휴게소 도착 확인", "실제 사용에서는 도착 버튼을 직접 눌러 확인해요."),
        Scene(Phase.REST_DURATION, 45_000, "휴식 시간 선택", "이번 시연에서는 30분 휴식을 선택해요."),
        Scene(Phase.PARK_CONFIRM, 49_000, "정차·P단 확인", "정차 확인 과정을 시연해요. 실제 차량 상태를 확인한 것은 아니에요."),
        Scene(Phase.RESTING, 53_000, "30분 휴식 시작", "휴식 타이머와 도착 목표에 따른 출발 알림을 함께 확인해요."),
        Scene(Phase.EXTENDED, 59_000, "휴식 5분 연장", "도착 목표에 여유가 있어 30분 휴식을 35분으로 늘려요."),
        Scene(Phase.TRAFFIC_DELAY, 64_000, "교통 정체 +20분", "가상 정체를 반영해 남은 휴식이 약 35분에서 약 15분으로 줄어요."),
        Scene(Phase.TIME_SKIP, 71_000, "출발 시각까지 시간 가속", "녹화용 시간 가속으로 남은 약 15분을 빠르게 재생해요."),
        Scene(Phase.DEPARTURE, 75_000, "이제 일어날 시간이에요", "출발 알림과 음성으로 안전한 출발 준비를 안내해요."),
        Scene(Phase.COMPLETE, 81_000, "휴식 완료", "확인 버튼을 누른 뒤 완료 화면에서 목적지 안내를 이어가요."),
        Scene(Phase.DESTINATION_MAP, 86_000, "목적지 안내로 복귀", "휴게소를 지나 부산역으로 계속 안내하는 흐름이에요."),
        Scene(Phase.END, TOTAL_DURATION_MS, "자동 시연 완료", "목적지 설정부터 휴식 완료까지 시연을 마쳤어요.")
    )

    data class Snapshot(
        val phase: Phase,
        val phaseIndex: Int,
        val phaseCount: Int,
        val phaseElapsedMs: Long,
        val phaseDurationMs: Long,
        val elapsedMs: Long,
        val progress: Float,
        val session: RestSession,
        val virtualNowMs: Long,
        val title: String,
        val caption: String,
        val isFinished: Boolean,
        val mapProgress: Float
    ) {
        val phaseProgress: Float get() = if (phaseDurationMs == 0L) 1f
            else (phaseElapsedMs.toFloat() / phaseDurationMs).coerceIn(0f, 1f)
    }

    fun startOf(phase: Phase): Long = scenes.first { it.phase == phase }.startMs

    fun snapshot(elapsedActiveMs: Long, baseTimeMs: Long): Snapshot {
        val elapsed = elapsedActiveMs.coerceIn(0, TOTAL_DURATION_MS)
        val index = scenes.indexOfLast { elapsed >= it.startMs }
        val scene = scenes[index]
        val duration = scenes.getOrNull(index + 1)?.startMs?.minus(scene.startMs) ?: 0L
        val phaseElapsed = elapsed - scene.startMs
        val phaseProgress = if (duration == 0L) 1f else phaseElapsed.toFloat() / duration
        val approachMs = candidates.first().approachMinutes * RestLogic.MINUTE_MS
        val restStart = baseTimeMs + startOf(Phase.RESTING) + approachMs
        val alarmElapsed = 15 * RestLogic.MINUTE_MS
        val virtualNow = when {
            elapsed < startOf(Phase.WAYPOINT_SELECTED) -> baseTimeMs + elapsed
            elapsed < startOf(Phase.ARRIVAL) -> {
                val travelElapsed = elapsed - startOf(Phase.WAYPOINT_SELECTED)
                val travelDuration = startOf(Phase.ARRIVAL) - startOf(Phase.WAYPOINT_SELECTED)
                baseTimeMs + elapsed + approachMs * travelElapsed / travelDuration
            }
            elapsed < startOf(Phase.TIME_SKIP) -> baseTimeMs + elapsed + approachMs
            elapsed < startOf(Phase.DEPARTURE) -> {
                val beforeSkip = startOf(Phase.TIME_SKIP) - startOf(Phase.RESTING)
                val skipElapsed = elapsed - startOf(Phase.TIME_SKIP)
                val skipDuration = startOf(Phase.DEPARTURE) - startOf(Phase.TIME_SKIP)
                restStart + beforeSkip + (alarmElapsed - beforeSkip) * skipElapsed / skipDuration
            }
            else -> restStart + alarmElapsed + elapsed - startOf(Phase.DEPARTURE)
        }
        val waypoint = RestSession(
            stage = "WAYPOINT", source = "DEMO", requestedMinutes = 30,
            stopName = candidates.first().name, stopLat = 37.0103, stopLon = 127.1443,
            originName = "시연 출발지", destinationName = "부산역", destinationLat = 35.1157,
            destinationLon = 129.0399, approachMinutes = 12, driveMinutes = 40,
            deadlineMs = restStart + 95 * RestLogic.MINUTE_MS,
            remainingDistanceMeters = 62_000,
            statusMessage = "녹화용 가상 경로 · 실제 내비 경로는 변경하지 않아요."
        )
        val resting = FlowPolicy.beginRest(waypoint, 30, restStart).copy(
            lastEtaAtMs = restStart, baselineDriveMinutes = 40,
            statusMessage = "녹화용 교통 정보를 확인했어요."
        )
        val extended = resting.copy(requestedMinutes = 35)
        val congested = FlowPolicy.simulateTraffic(extended, 20, restStart + 11_000)
        val selected = index >= Phase.WAYPOINT_SELECTED.ordinal
        val localSession = when (scene.phase) {
            Phase.DESTINATION_ENTRY, Phase.RESET_DESTINATION -> waypoint.copy(stage = "PLAN", stopName = "", stopLat = 0.0, stopLon = 0.0)
            Phase.RECOMMENDATIONS -> waypoint.copy(stage = "SUGGESTIONS", stopName = "", stopLat = 0.0, stopLon = 0.0)
            Phase.WAYPOINT_SELECTED, Phase.ARRIVAL, Phase.REST_DURATION, Phase.PARK_CONFIRM -> waypoint
            Phase.RESTING -> resting
            Phase.EXTENDED -> extended
            Phase.TRAFFIC_DELAY, Phase.TIME_SKIP -> congested
            Phase.DEPARTURE -> FlowPolicy.due(congested, virtualNow)
            Phase.COMPLETE -> FlowPolicy.acknowledge(FlowPolicy.due(congested, virtualNow))
            Phase.DESTINATION_MAP, Phase.END -> FlowPolicy.acknowledge(FlowPolicy.due(congested, virtualNow)).copy(
                stage = "NAVIGATING", stopName = "", stopLat = 0.0, stopLon = 0.0,
                startedAtMs = 0, approachMinutes = 0,
                statusMessage = "녹화용 시연 완료 · 부산역 안내를 계속해요."
            )
            else -> waypoint.copy(stage = "NAVIGATING", stopName = if (selected) waypoint.stopName else "", stopLat = 0.0, stopLon = 0.0)
        }
        val position = when (scene.phase) {
            Phase.NAVIGATION -> .05f + .25f * phaseProgress
            Phase.NAVIGATION_RESUMED -> .3f + .1f * phaseProgress
            Phase.WAYPOINT_SELECTED -> .4f + .2f * phaseProgress
            Phase.DESTINATION_MAP -> .6f + .4f * phaseProgress
            Phase.END -> 1f
            else -> if (selected) .6f else .3f
        }
        return Snapshot(scene.phase, index, scenes.size, phaseElapsed, duration, elapsed,
            elapsed.toFloat() / TOTAL_DURATION_MS, localSession, virtualNow,
            scene.title, scene.caption, scene.phase == Phase.END, position)
    }
}
