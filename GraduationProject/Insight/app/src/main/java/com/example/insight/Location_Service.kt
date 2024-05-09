package com.example.insight
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import java.util.*

class Location_Service : Service(),SensorEventListener,TextToSpeech.OnInitListener{
    private lateinit var tts: TextToSpeech
    var vibrator: Vibrator?=null
    var lastTime=System.currentTimeMillis()
    var sensor:Sensor?=null
    var sensorManager:SensorManager?=null
    var queue:RequestQueue? = null
    var qiblaDirection:Double?=null
    override fun onCreate() {

        super.onCreate()
        tts=TextToSpeech(this,this)
    }
    public fun stratLocation(){

        vibrator=getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        sensorManager=getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor=sensorManager!!.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        queue = Volley.newRequestQueue(this)
        val notificationChannel : NotificationChannel
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, Location_Service::class.java)
        // FLAG_UPDATE_CURRENT specifies that if a previous
        // PendingIntent already exists, then the current one
        // will update it with the latest intent
        // 0 is the request code, using it later with the
        // same method again will get back the same pending
        // intent for future reference
        // intent passed here is to our afterNotification class
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // RemoteViews are used to use the content of
        // some different layout apart from the current activity layout
        // checking if android version is greater than oreo(API 26) or not
        var builder :Notification.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationChannel = NotificationChannel("location", "Location", NotificationManager.IMPORTANCE_HIGH)
            notificationChannel.enableLights(true)
            notificationChannel.lightColor = Color.GREEN
            notificationChannel.enableVibration(false)
            notificationManager.createNotificationChannel(notificationChannel)

            builder = Notification.Builder(this, "location")
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setLargeIcon(BitmapFactory.decodeResource(this.resources, R.drawable.ic_launcher_background))
                .setContentIntent(pendingIntent).setAutoCancel(false).setDefaults(NotificationCompat.DEFAULT_ALL)
        } else {
            builder = Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setLargeIcon(BitmapFactory.decodeResource(this.resources, R.drawable.ic_launcher_background))
                .setContentIntent(pendingIntent)
        }
        builder.setContentTitle("CurrentLocation")
        val locationReq : LocationRequest= LocationRequest()
        locationReq.setInterval(1000)
        locationReq.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("LocationService", "permission not granted")
            return
        }

        Log.d("LocationService", "permission granted")

        val lastlocation=LocationServices.getFusedLocationProviderClient(this).lastLocation
        lastlocation.addOnSuccessListener {
            val longitude=it.longitude
            val latitude=it.latitude
            val url ="https://api.aladhan.com/v1/qibla/$latitude/$longitude"
            Log.d("LOCATION","$longitude ,$latitude")

            // Request a string response from the provided URL.
            if(qiblaDirection==null){
                val stringRequest = StringRequest(
                    Request.Method.GET, url,
                    Response.Listener<String> { response ->
                        // Display the first 500 characters of the response string.
                        Log.d("location service","Good: " + response)
                        val res:JsonObject= Json.decodeFromString(response)
                        val data:JsonObject= res["data"] as JsonObject
                        qiblaDirection=(data["direction"] as JsonPrimitive).double
                    },
                    Response.ErrorListener {
                        Log.d("location service","Error request " + it.toString())
                    })

                // Add the request to the RequestQueue.
                queue!!.add(stringRequest)
            }
        }


        startForeground(1000,builder.build())
        sensorManager!!.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL)

    }
    public fun stop(){
        stopForeground(true)
        sensorManager?.unregisterListener(this)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("locationService","recivce Massage " + intent.toString())
        if(intent !=null && intent.action!=null){
            if(intent.action.equals("location_start")){
                Log.d("LocationService", "location_start")
                stratLocation()
            }
            else if(intent.action.equals("location_stop")){
                Log.d("LocationService", "location_stop")
                stop()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val time=System.currentTimeMillis()
        if(event==null || qiblaDirection==null || time - lastTime < 1000){
            return
        }
        lastTime=time
       val direction = event!!.values[0]
        Log.d("Location_Service","direction:$direction")
        var def=qiblaDirection!!-direction
        if(def > 180){
            def-=360
        }
        else if(def <= -180){
            def+=360
        }
        Log.d("Location Service","$def")
        if(def<10 && def>-10){
            tts.speak("Correct",TextToSpeech.QUEUE_FLUSH,null)
            Log.d("Location_Service","Correct")
            sensorManager?.unregisterListener(this)
            vibrator!!.vibrate(3000)
        }
        else if(def >0 ){
            tts.speak("Go Right",TextToSpeech.QUEUE_FLUSH,null)
            Log.d("Location Service","Go Right")
            //vibrator!!.vibrate(longArrayOf(500,500,500),-1)
        }
        else{
            tts.speak("Go Left",TextToSpeech.QUEUE_FLUSH,null)
            Log.d("Location Service","Go Left")
            //vibrator!!.vibrate(500)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language not supported!")
            }
        }
    }
}