package com.restsync.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/** Original vector scenery and schematic route illustrations; not live map data. */
internal class RestVisuals(context: Context, private val scene: String = "home") : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lilac = Color.rgb(196, 187, 255)
    private val purple = RestTheme.purple
    private val soft = Color.rgb(238, 235, 255)
    private val dark = Color.rgb(94, 85, 160)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1f || h < 1f) return
        canvas.save()
        canvas.scale(w / 1000f, h / 600f)
        if (scene == "route") drawMap(canvas) else drawHills(canvas)
        canvas.restore()
    }

    private fun drawHills(c: Canvas) {
        p.shader = LinearGradient(0f,0f,0f,600f, intArrayOf(Color.rgb(252,250,255),Color.rgb(227,216,255),Color.rgb(167,157,233)),null,Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,1000f,600f,p)
        p.shader = null
        p.color = Color.rgb(255,244,244)
        c.drawCircle(790f,220f,76f,p)
        mountain(c, floatArrayOf(0f,410f,155f,265f,300f,396f,470f,215f,680f,413f,845f,292f,1000f,388f), Color.rgb(225,216,252))
        mountain(c, floatArrayOf(0f,475f,130f,380f,270f,458f,470f,350f,690f,476f,820f,345f,1000f,458f),Color.rgb(189,177,238))
        mountain(c, floatArrayOf(0f,530f,150f,450f,270f,535f,500f,415f,690f,530f,880f,440f,1000f,510f),Color.rgb(147,133,207))
        p.color=Color.rgb(108,96,165); c.drawRect(0f,540f,1000f,600f,p)
        val road=Path().apply { moveTo(500f,600f); cubicTo(450f,560f,510f,520f,600f,480f); cubicTo(740f,400f,580f,440f,650f,375f) }
        p.color=Color.rgb(75,70,119);p.strokeWidth=130f;p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        c.drawPath(road,p)
        p.color=Color.rgb(255,251,252);p.strokeWidth=5f;c.drawPath(road,p)
        p.style=Paint.Style.FILL
        p.color=Color.rgb(255,255,255);c.drawRoundRect(560f,466f,660f,521f,17f,17f,p)
        p.color=Color.rgb(62,53,116);c.drawRoundRect(578f,453f,637f,480f,12f,12f,p)
        p.color=Color.rgb(48,39,82);c.drawCircle(580f,518f,12f,p);c.drawCircle(640f,518f,12f,p)
    }

    private fun mountain(c: Canvas, vertices: FloatArray, color: Int) {
        val path=Path().apply {moveTo(vertices[0],vertices[1]);for(i in 2 until vertices.size step 2) lineTo(vertices[i],vertices[i+1]);lineTo(1000f,600f);lineTo(0f,600f);close()}
        p.color=color;c.drawPath(path,p)
    }

    private fun drawMap(c:Canvas) {
        p.color=Color.rgb(247,245,255);c.drawColor(p.color)
        p.color=Color.rgb(226,239,224)
        c.drawRoundRect(65f,65f,305f,240f,45f,45f,p)
        c.drawRoundRect(710f,355f,935f,500f,40f,40f,p)
        c.drawRoundRect(80f,395f,310f,570f,40f,40f,p)
        p.style=Paint.Style.STROKE
        for(i in 0..7) {
            p.color=Color.rgb(225,223,236);p.strokeWidth=16f;p.strokeCap=Paint.Cap.ROUND
            val y=i*95f-80f
            c.drawLine(0f,y,1000f,y+260f,p)
        }
        for(i in 0..6) {
            p.color=Color.rgb(232,229,243);p.strokeWidth=11f
            val x=i*185f-60f
            c.drawLine(x,0f,x+110f,600f,p)
        }
        val path=Path().apply{moveTo(150f,455f);cubicTo(250f,375f,170f,270f,350f,290f);cubicTo(530f,305f,495f,100f,795f,120f)}
        p.color=Color.rgb(250,248,255);p.strokeWidth=39f;c.drawPath(path,p)
        p.color=purple;p.strokeWidth=19f;c.drawPath(path,p)
        p.style=Paint.Style.FILL
        marker(c,150f,455f,"출발")
        marker(c,800f,120f,"휴게소")
    }

    private fun marker(c:Canvas,x:Float,y:Float,title:String){
        p.color=Color.WHITE;c.drawCircle(x,y,30f,p)
        p.color=purple;c.drawCircle(x,y,20f,p)
        p.color=Color.WHITE;p.textSize=30f;p.typeface=android.graphics.Typeface.DEFAULT_BOLD
        val tWidth=p.measureText(title);c.drawRoundRect(x-100f,y-94f,x+100f,y-44f,23f,23f,p)
        p.color=dark;c.drawText(title,x-tWidth/2,y-60f,p)
    }
}
