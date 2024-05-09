package com.example.insight
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.util.*

class MainActivity : AppCompatActivity() ,TextToSpeech.OnInitListener{
    companion object {
       private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private lateinit var tts: TextToSpeech
    private var clickCount = 0;
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tts=TextToSpeech(this,this)
        findViewById<Button>(R.id.btn).setOnClickListener {
            if(clickCount==0){
                clickCount=1
                scope.launch {
                    delay(1000)
                    if(clickCount==1){
                        val intent: Intent = Intent(applicationContext, QiblaActivity::class.java)
                        startActivity(intent)
                    }
                    else if(clickCount==2){
                        var intent: Intent = Intent(applicationContext, DetectObjectActivity::class.java)
                        startActivity(intent)
                    }
                    clickCount = 0
                }
            }
            else{
                clickCount++
            }
        }

    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language not supported!")
            }
            else{
                tts.speak("Welcome to the insight application. You should to press the screen once to determine the direction of the Qibla, and if you press twice, you will determine the things around you.",TextToSpeech.QUEUE_FLUSH,null)
            }
        }
    }
}