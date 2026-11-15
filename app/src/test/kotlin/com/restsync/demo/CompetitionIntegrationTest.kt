package com.restsync.demo

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class CompetitionIntegrationTest {
    @Test fun documentedIntentParameterEncodingsPreserveDuration() {
        assertEquals(30, GleoBridge.parameters("{\"restMinutes\":30}").getInt("restMinutes"))
        assertEquals(30, GleoBridge.parameters(JSONObject.quote("{\"restMinutes\":30}")).getInt("restMinutes"))
        assertEquals(30, GleoBridge.parameters("{\"actionParameters\":{\"restMinutes\":30}}").getInt("restMinutes"))
        assertTrue(runCatching { GleoBridge.parameters("not-json") }.isFailure)
    }
    @Test fun movingOrNonParkVehicleNeverPasses() {
        assertEquals(ParkingPolicy.State.UNSAFE, ParkingPolicy.evaluate(1f, 4, 4, true))
        assertEquals(ParkingPolicy.State.UNSAFE, ParkingPolicy.evaluate(-1f, 4, 4, true))
        assertEquals(ParkingPolicy.State.UNSAFE, ParkingPolicy.evaluate(0f, 8, 4, true))
        assertEquals(ParkingPolicy.State.UNSAFE, ParkingPolicy.evaluate(null, 8, 4, true))
    }
    @Test fun missingOrInvalidTelemetryIsUnknown() {
        assertEquals(ParkingPolicy.State.UNKNOWN, ParkingPolicy.evaluate(null, 4, 4, true))
        assertEquals(ParkingPolicy.State.UNKNOWN, ParkingPolicy.evaluate(Float.NaN, 4, 4, true))
        assertEquals(ParkingPolicy.State.UNKNOWN, ParkingPolicy.evaluate(0f, 4, 4, false))
        assertEquals(ParkingPolicy.State.PARKED, ParkingPolicy.evaluate(0f, 4, 4, true))
    }
    @Test fun arrivalHintRejectsInvalidCoordinatesAndDistantStops() {
        assertFalse(ParkingPolicy.nearStop(35.1, 129.0, 0.0, 0.0))
        assertFalse(ParkingPolicy.nearStop(Double.NaN, 129.0, 35.1, 129.0))
        assertFalse(ParkingPolicy.nearStop(35.1, 129.0, 36.1, 128.0))
        assertTrue(ParkingPolicy.nearStop(35.1, 129.0, 35.1001, 129.0001))
    }
    @Test fun callbackContainsActualTimeDataAsString() {
        val response = JSONObject(GleoBridge.response("CHECK_REST_TIME", "success", "15 minutes remain.",
            JSONArray().put(JSONObject().put("remainingMinutes", 15))))
        assertTrue(response.get("data") is String)
        assertEquals(15, JSONArray(response.getString("data")).getJSONObject(0).getInt("remainingMinutes"))
    }
    @Test fun congestionShortensExtendedBreakWithoutChangingRequestedDuration() {
        val now = 1_800_000_000_000L
        val s = RestSession(stage="RESTING", source="PLEOS", requestedMinutes=35, startedAtMs=now,
            deadlineMs=now + (60+10+10+35)*60_000L, driveMinutes=60, respectDeadline=true)
        assertEquals(now+35*60_000L, RestLogic.calculate(s,now).nextAlertMs)
        val congested = FlowPolicy.simulateTraffic(s,20,now)
        assertEquals(35, congested.requestedMinutes.toInt())
        assertEquals(now+15*60_000L, RestLogic.calculate(congested,now).nextAlertMs)
    }
    @Test fun spokenDurationAndActualSelectionAreRecognized() {
        assertEquals(DemoVoiceIntent.Start, DemoVoiceParser.parse("30분 쉴게"))
        assertEquals(30L, DemoVoiceParser.requestedMinutes("30분 쉴게"))
        assertEquals(DemoVoiceIntent.Select(1), DemoVoiceParser.parse("두 번째 휴게소로 안내해 줘"))
        assertEquals(DemoVoiceIntent.Extend, DemoVoiceParser.parse("5분 더 쉴게"))
    }
}
