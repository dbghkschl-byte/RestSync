package com.restsync.demo

import android.content.Context
import android.graphics.*
import android.view.View

/** Scalable original twilight scenery; decorative, never presented as a live map. */
internal class RestScene(context:Context,private val road:Boolean=false):View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas) {
        c.save();c.scale(width/1200f,height/700f)
        p.shader=LinearGradient(0f,0f,0f,700f,intArrayOf(0xFF29244F.toInt(),0xFF6861A2.toInt(),0xFFBC9EB7.toInt()),null,Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,1200f,700f,p);p.shader=null
        p.color=0x33EAE4FF;c.drawCircle(920f,130f,64f,p)
        val colors=intArrayOf(0xFF71658F.toInt(),0xFF50476E.toInt(),0xFF353451.toInt())
        for(layer in 0..2) {
            val y=350f+layer*85
            val path=Path().apply {moveTo(0f,y);for(i in 0..8) lineTo(i*170f,y-((i*57+layer*31)%130));lineTo(1200f,700f);lineTo(0f,700f);close()}
            p.color=colors[layer];c.drawPath(path,p)
        }
        p.color=0xFF272B43.toInt()
        val lane=Path().apply {moveTo(if(road) 40f else 470f,700f);lineTo(640f,390f);lineTo(690f,390f);lineTo(1180f,700f);close()}
        c.drawPath(lane,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.color=0x99ECE4FF.toInt()
        c.drawLine(if(road)40f else 470f,700f,640f,390f,p);c.drawLine(1180f,700f,690f,390f,p)
        p.strokeWidth=4f;c.drawLine(820f,700f,666f,390f,p);p.style=Paint.Style.FILL
        if(!road) {
            p.color=0xFF242B40.toInt();c.drawRoundRect(30f,465f,340f,555f,10f,10f,p)
            p.color=0xFF443D62.toInt();c.drawRect(20f,451f,350f,467f,p)
            p.color=0xFFEBCAA6.toInt();for(i in 0..5)c.drawRect(53f+i*43,485f,76f+i*43,524f,p)
        }
        p.color=0x221C153D;c.drawRect(0f,0f,1200f,700f,p)
        c.restore()
    }
}
