package com.restsync.demo

/** Speech-demo intents. This parser is a local scripted demonstration, not Gleo AI. */
internal sealed class DemoVoiceIntent {
    object Search : DemoVoiceIntent()
    data class Select(val index: Int) : DemoVoiceIntent()
    object Start : DemoVoiceIntent()
    object Stop : DemoVoiceIntent()
    object Extend : DemoVoiceIntent()
    object Congestion : DemoVoiceIntent()
    object ClearTraffic : DemoVoiceIntent()
    object Status : DemoVoiceIntent()
    object Unknown : DemoVoiceIntent()
}

internal object DemoVoiceParser {
    fun requestedMinutes(utterance: String): Long? {
        val match = Regex("(\\d{1,3})\\s*분").find(utterance) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it in 1..180 }
    }

    fun arrivalHourMinute(utterance: String): Pair<Int, Int>? {
        val match = Regex("(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분)?").find(utterance) ?: return null
        val h = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    fun parse(utterance: String): DemoVoiceIntent {
        val s = utterance.lowercase().replace(" ", "")
        if (s.isBlank()) return DemoVoiceIntent.Unknown
        if (listOf("휴식종료", "그만쉴", "다쉬었", "출발하", "이제갈게").any { it in s }) return DemoVoiceIntent.Stop
        if (listOf("5분더", "5분연장", "조금더쉬", "연장해").any { it in s }) return DemoVoiceIntent.Extend
        if (listOf("교통정체", "정체심", "차가막혀", "차막혀").any { it in s }) return DemoVoiceIntent.Congestion
        if (listOf("교통회복", "정체풀", "길이뚫", "차가안막").any { it in s }) return DemoVoiceIntent.ClearTraffic
        if (listOf("휴식시작", "쉬기시작", "이제쉴게", "잠잘게").any { it in s }) return DemoVoiceIntent.Start
        if (Regex("\\d{1,3}분(?:쉴게|쉬겠|쉬고갈|휴식할)").containsMatchIn(s)) return DemoVoiceIntent.Start
        if (listOf("첫번째", "1번", "일번", "첫째").any { it in s }) return DemoVoiceIntent.Select(0)
        if (listOf("두번째", "2번", "이번", "둘째").any { it in s }) return DemoVoiceIntent.Select(1)
        if (listOf("세번째", "3번", "삼번", "셋째").any { it in s }) return DemoVoiceIntent.Select(2)
        if (listOf("얼마나쉴", "남은휴식", "휴식시간", "얼마나남", "몇분남", "출발시간", "몇시에출발", "언제출발").any { it in s }) return DemoVoiceIntent.Status
        if (listOf("찾아줘", "찾아봐", "검색해", "쉬고싶", "쉬고싶어", "쉴수있", "쉴곳", "좀쉴", "휴게소", "졸음쉼터", "잠깐쉬", "배고파", "졸려", "졸린", "자고싶", "피곤", "밥먹", "휴식하고싶", "휴식해도").any { it in s }) return DemoVoiceIntent.Search
        return DemoVoiceIntent.Unknown
    }
}
