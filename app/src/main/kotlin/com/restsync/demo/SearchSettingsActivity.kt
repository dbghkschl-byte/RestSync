package com.restsync.demo

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** The existing device-local route credential is edited only in this settings screen. */
internal class SearchSettingsActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val prefs=getSharedPreferences("restsync_local_settings",MODE_PRIVATE)
        val box=RestTheme.container(this,28,RestTheme.snow,0).apply {isFocusableInTouchMode=true}
        fun label(t:String,size:Float=22f)=RestTheme.label(this,t,size,RestTheme.ink)
        box.addView(RestTheme.button(this,"‹ 돌아가기",false) {finish()},RestTheme.margins(this,24))
        box.addView(RestTheme.label(this,"휴게소 검색 설정",32f,RestTheme.ink,true),RestTheme.margins(this,18))
        box.addView(label("한 번 저장하면 휴식을 누를 때 바로 검색해요."),RestTheme.margins(this,24))
        box.addView(label("카카오 모빌리티 REST 키",24f),RestTheme.margins(this,12))
        val input=EditText(this).apply {
            isSingleLine=true;textSize=24f
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod=android.text.method.PasswordTransformationMethod.getInstance()
            imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            setText(prefs.getString("kakao_rest_api_key","").orEmpty())
            hint="REST 키 입력"
            setPadding(RestTheme.dp(this@SearchSettingsActivity,18),18,18,18)
            background=RestTheme.rounded(this@SearchSettingsActivity,RestTheme.white,16,RestTheme.line)
        }
        box.addView(input,LinearLayout.LayoutParams(-1,RestTheme.dp(this,76)).apply {bottomMargin=RestTheme.dp(this@SearchSettingsActivity,16)})
        val status=label(if(input.text.isNotBlank()) "저장된 키가 있어요. 변경할 때만 수정해 주세요." else "실시간 우회 시간 비교에 사용할 키를 저장해 주세요.")
        box.addView(status,RestTheme.margins(this,20))
        box.addView(RestTheme.button(this,"저장하고 돌아가기") {
            val value=input.text.toString().trim()
            if(value.isBlank()) {status.text="키를 입력해 주세요. 기존 저장값은 유지됩니다.";return@button}
            prefs.edit().putString("kakao_rest_api_key",value).apply()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
            setResult(RESULT_OK);finish()
        },RestTheme.margins(this,24))
        box.addView(RestTheme.label(this,"키는 현재 앱에 보관돼요. 목적지 장소 검색에는 이 키가 필요하지 않아요.",20f,RestTheme.muted),RestTheme.margins(this,12))
        setContentView(ScrollView(this).apply {isFillViewport=true;addView(box)})
        box.requestFocus()
    }
}
