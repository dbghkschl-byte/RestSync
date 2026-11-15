package com.restsync.demo

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast

/** Controls keep real SDK selection/confirmation on the existing screen. */
internal fun Activity.recordingSelectionBar(instruction:String,canRestart:()->Boolean={true}):View {
    var bar:View?=null
    bar=RecordingTour.bar(this,
        onNext={Toast.makeText(this,instruction,Toast.LENGTH_LONG).show()},
        onRestart={
            if(canRestart())startActivity(Intent(this,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("recordingTourAction","restart"))
            else Toast.makeText(this,"진행 중인 내비 요청이 끝난 뒤 다시 시작해 주세요.",Toast.LENGTH_LONG).show()
        },
        onStop={
            if(RecordingTour.read(this).automated)ClickAutomation.command(this,"stop")
            RecordingTour.stop(this);bar?.visibility=View.GONE;RestAlerts.showCompanionShortcut(this)
        })
    return bar
}
