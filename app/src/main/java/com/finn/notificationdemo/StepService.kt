package com.finn.notificationdemo

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import androidx.annotation.Nullable
import java.util.*
import android.app.ActivityManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
import android.app.NotificationManager

class StepService : Service(), SensorEventListener {

    companion object {
        const val MSG_FROM_NOTIFY = 2
        const val MSG_FROM_CLIENT = 0
        const val MSG_FROM_SERVER = 1
        const val MSG_TODAY_STEP = "todayStep"
        const val MSG_COUNTER_STEP = "counterStep"


        const val STEP_NOTIFICATION_ID = 2021

        const val CHANNEL_ID = "202121"
        const val CHANNEL_NAME = "sxiaozhi-walk"
    }

    // 计步传感器类型
    enum class SensorType {
        SENSOR_COUNTER,
        SENSOR_DETECTOR,
        SENSOR_NONE
    }

    private var isStartStep: Boolean = false

    // 当前传感器步数
    private var counterStep: Int = 0

    // 传感器
    private var sensorManager: SensorManager? = null

    // 计步传感器类型
    private var stepSensor = SensorType.SENSOR_NONE

    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private val builder: NotificationCompat.Builder by lazy {
        NotificationCompat.Builder(
            this.applicationContext,
            CHANNEL_ID
        )
    }

    /**
     * 发送消息，用来和Service之间传递步数
     */
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.  We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        HandlerThread("ServiceStartArguments", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            getStepDetector()
        }
    }

    @Nullable
    override fun onBind(intent: Intent): IBinder? {
        messenger = Messenger(ServiceHandler(Looper.getMainLooper()))
        return messenger.binder
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("今日步数")
            .setContentText("健康走路，让走路更具价值！")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(getPendingIntent())
            .setVisibility(VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(true)

        // 创建 NotificationChannel，但仅在 API 26+ 上创建，因为 NotificationChannel 类是新的并且不在支持库中
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance)
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(channel)
        }
        // 参数一：唯一的通知标识；参数二：通知消息。
        startForeground(STEP_NOTIFICATION_ID, builder.build())  // 开启前台服务
        return START_STICKY
    }

    /**
     * 自定义handler
     */
    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) = when (msg.what) {
            MSG_FROM_CLIENT -> try {
                val messenger = msg.replyTo
                val replyMsg = Message.obtain(null, MSG_FROM_SERVER)
                val bundle = Bundle()
                bundle.putInt(MSG_COUNTER_STEP, if (isStartStep) counterStep else -1)
                replyMsg.data = bundle
                messenger.send(replyMsg)
            } catch (e: Exception) {
            }
            MSG_FROM_NOTIFY -> try {
                msg.data.getString(MSG_TODAY_STEP)?.let {
                    notifyStepBuilder(it)
                } ?: Unit
            } catch (e: Exception) {
            }
            else -> super.handleMessage(msg)
        }
    }

    /**
     * 获取传感器实例
     */
    private fun getStepDetector() {
        if (sensorManager != null) {
            sensorManager = null
        }
        // 获取传感器管理器的实例
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        //android4.4以后可以使用计步传感器
        addCountStepListener()
    }

    /**
     * 添加传感器监听
     */
    private fun addCountStepListener() {
        val counterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (counterSensor != null) {
            stepSensor = SensorType.SENSOR_COUNTER
            sensorManager?.registerListener(
                this@StepService,
                counterSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        } else if (detectorSensor != null) {
            stepSensor = SensorType.SENSOR_DETECTOR
            sensorManager?.registerListener(
                this@StepService,
                detectorSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    /**
     * 由传感器记录当前用户运动步数，注意：该传感器只在4.4及以后才有，并且该传感器记录的数据是从设备开机以后不断累加，
     * 只有当用户关机以后，该数据才会清空，所以需要做数据保护
     */
    override fun onSensorChanged(event: SensorEvent) {
        if (stepSensor == SensorType.SENSOR_COUNTER) {
            isStartStep = true
            counterStep = event.values[0].toInt()
        } else if (stepSensor == SensorType.SENSOR_DETECTOR) {
            isStartStep = true
            if (event.values[0].toDouble() == 1.0) {
                counterStep++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
    }

    private fun notifyStepBuilder(step: String) {
        builder.setContentTitle("今日步数：$step")
            .setContentIntent(getPendingIntent())
        // 调用更新  notificationId 是您必须定义的每个通知的唯一 int
        notificationManager.notify(STEP_NOTIFICATION_ID, builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        // 主界面中需要手动调用stop方法service才会结束
        stopForeground(true)
    }

    override fun onUnbind(intent: Intent): Boolean {
        return super.onUnbind(intent)
    }

    private fun getPendingIntent(): PendingIntent {
        val intent: Intent =
            if (isAppRunning(this, packageName) && isActivityRunning(MainActivity::class.java)
            ) {
                Intent()
            } else {
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            }
        return PendingIntent.getActivity(this, 0, intent, 0)
    }


    private fun isActivityRunning(activityClass: Class<*>): Boolean {
        val activityManager = baseContext.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val tasks = activityManager.getRunningTasks(Int.MAX_VALUE)
        activityManager.appTasks
        for (task in tasks) {
            if (activityClass.canonicalName.equals(
                    task.baseActivity?.className,
                    ignoreCase = true
                )
            ) return true
        }
        return false
    }

    private fun isAppRunning(context: Context, packageName: String): Boolean {
        val activityManager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processInfos = activityManager.runningAppProcesses
        if (processInfos != null) {
            for (processInfo in processInfos) {
                if (processInfo.processName == packageName) {
                    return true
                }
            }
        }
        return false
    }
}