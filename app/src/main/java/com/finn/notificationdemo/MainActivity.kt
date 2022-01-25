package com.finn.notificationdemo

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.finn.notificationdemo.StepService.Companion.MSG_FROM_CLIENT
import com.finn.notificationdemo.StepService.Companion.MSG_FROM_NOTIFY
import com.finn.notificationdemo.StepService.Companion.MSG_FROM_SERVER
import java.util.*

class MainActivity : AppCompatActivity(), Handler.Callback {


    companion object {
        private const val TAG = "MainActivity"

        private const val COARSE_LOCATION_REQUEST = 1001
        private const val REQUEST_CODE = 100

        /**
         * Permissions required to make the app work!
         */
        private val CHECK_PERMISSIONS = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.FOREGROUND_SERVICE
        )
    }

    val stepShow: TextView by lazy { findViewById(R.id.stepShow) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 获取必要权限
        if (!allPermissionsGranted()) {
            requestPermissions()
        } else {
            /**
             * 这里判断当前设备是否支持计步
             */
            if (isSupportStepCountSensor(this)) {
                setupService()
            } else {
                Toast.makeText(this, "您的设备不支持计步", Toast.LENGTH_SHORT).show()
            }
        }

        stepNumber.observe(this) {
            stepShow.text = "Step is:  $it"
            notifyStep()
        }

        stepShow.setOnClickListener {
            notifyStep()
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            CHECK_PERMISSIONS,
            COARSE_LOCATION_REQUEST
        )
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted(): Boolean {
        return CHECK_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                baseContext, it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            COARSE_LOCATION_REQUEST -> {
                if (grantResults.isNotEmpty()) {
                    setupService()
                }
            }
        }
    }


    val stepNumber = MutableLiveData(0)

    /**
     * 开启计步服务
     */
    private fun setupService() {
        Intent(this, StepService::class.java).also { intent ->
            this.bindService(intent, conn, BIND_AUTO_CREATE)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) startForegroundService(intent) else startService(
                intent
            )
        }

    }

    private var isBound = false

    /**
     * 定时任务
     */
    private var timerTask: TimerTask? = null
    private var timer: Timer? = null
//    private val getReplyMessenger by lazy { Messenger(Handler(Looper.getMainLooper(), this)) }

    /** Messenger for communicating with the service.  */
    private var serviceMessenger: Messenger? = null

    /**
     * 用于查询应用服务（application Service）的状态的一种interface，
     * 更详细的信息可以参考Service 和 context.bindService()中的描述，
     * 和许多来自系统的回调方式一样，ServiceConnection的方法都是进程的主线程中调用的。
     */
    private val conn = object : ServiceConnection {
        /**
         * 在建立起于Service的连接时会调用该方法，目前Android是通过IBind机制实现与服务的连接。
         * @param name 实际所连接到的Service组件名称
         * @param service 服务的通信信道的IBind，可以通过Service访问对应服务
         */
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // This is called when the connection with the service has been
            // established, giving us the object we can use to
            // interact with the service.  We are communicating with the
            // service using a Messenger, so here we get a client-side
            // representation of that from the raw IBinder object.
            serviceMessenger = Messenger(service)
            isBound = true

            syncDataToMessage()
        }

        /**
         * 当与Service之间的连接丢失的时候会调用该方法，
         * 这种情况经常发生在Service所在的进程崩溃或者被Kill的时候调用，
         * 此方法不会移除与Service的连接，当服务重新启动的时候仍然会调用 onServiceConnected()。
         * @param name 丢失连接的组件名称
         */
        override fun onServiceDisconnected(name: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            serviceMessenger = null
            isBound = false
        }
    }

    private fun syncDataToMessage() {
        timerTask = object : TimerTask() {
            override fun run() {
                try {
                    val msg = Message.obtain(null, MSG_FROM_CLIENT)
                    serviceMessenger?.send(msg)
                } catch (e: Exception) {
                }
            }
        }
        timer = Timer()
        timer?.schedule(timerTask, 0, 5000L)
    }

    fun notifyStep() {
        if (!isBound) return
        // Create and send a message to the service, using a supported 'what' value
        try {
            val msg: Message = Message.obtain(null, MSG_FROM_NOTIFY, 0, 0)
            val bundle = Bundle()
            bundle.putString(StepService.MSG_TODAY_STEP, stepShow.text.toString())
            msg.data = bundle
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }

    }

    fun isSupportStepCountSensor(context: Context): Boolean {
        // 获取传感器管理器的实例
        val sensorManager = context
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val countSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        return countSensor != null || detectorSensor != null
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            //这里用来获取到Service发来的数据
            MSG_FROM_SERVER -> {
                // 同步运动步数
                val steps = msg.data.getInt("counter")
                Log.d("TEST", "handleMessage: 同步运动步数 $steps")
                stepNumber.postValue(steps + 1)
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        //记得解绑Service，不然多次绑定Service会异常
        if (isBound) unbindService(conn)
        isBound = false
    }
}