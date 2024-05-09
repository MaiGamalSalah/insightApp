package com.example.insight
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*
class QiblaActivity : AppCompatActivity() , TextToSpeech.OnInitListener{
    private lateinit var tts: TextToSpeech
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qibla)
        tts=TextToSpeech(this,this)
    }
    override fun onStart() {
        super.onStart()
        if(ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            startLocationService()
        }
        else{
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),100)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode==100 && grantResults[0]==PackageManager.PERMISSION_GRANTED){
            startLocationService()
        }
    }
    override fun onStop() {
        super.onStop()
        stopLocationService()
    }
    private fun IsLocationRuning():Boolean{
        var manager=getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var list=manager.getRunningServices(1000)
        for(i in list){
            if(i.service.equals(Location_Service::class.java.name)){
                return true
            }
        }
        return false
    }
    private fun startLocationService(){
        if(!IsLocationRuning()){
            val intent= Intent(applicationContext,Location_Service::class.java)
            intent.setAction("location_start")
            startService(intent)
        }
    }
    private fun stopLocationService(){
        if(IsLocationRuning()){
            val intent= Intent(applicationContext,Location_Service::class.java)
            intent.setAction("location_stop")
            startService(intent)
        }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language not supported!")
            }
            else{
                tts.speak("The qibla is now determined",TextToSpeech.QUEUE_FLUSH,null)
            }
        }
    }
}