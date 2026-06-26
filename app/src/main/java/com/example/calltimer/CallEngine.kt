package com.example.calltimer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

/** Sự kiện gửi về UI. */
interface CallEventListener {
    fun onLog(message: String)
    fun onCountdownTick(remaining: Int)
    /** Báo kết quả 1 cuộc gọi để lưu lịch sử. */
    fun onResult(durationSec: Double, result: String)
    fun onCallFinished()
}

/**
 * Giao diện chung cho mọi kiểu gọi.
 * CellularCallEngine = gọi thường, SipCallEngine = VoIP.
 */
interface CallEngine {
    fun startCall(number: String, durationSeconds: Int)
    fun cancel()
}

/**
 * Gọi qua mạng di động, đếm N giây kể từ khi máy chuyển sang OFFHOOK
 * (≈ lúc bắt đầu quay số) rồi tự ngắt bằng TelecomManager.endCall().
 */
class CellularCallEngine(
    private val context: Context,
    private val listener: CallEventListener
) : CallEngine {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val telecomManager =
        context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val handler = Handler(Looper.getMainLooper())

    private var durationSeconds = 0
    private var offHookStarted = false
    private var finished = false
    private var hangUpCalled = false
    private var startElapsed = 0L
    private var ticksLeft = 0

    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private val hangUpRunnable = Runnable { hangUp() }

    private val hangupFallback = Runnable {
        val dur = (SystemClock.elapsedRealtime() - startElapsed) / 1000.0
        terminate(dur, "Đã ngắt (không xác nhận IDLE)")
    }

    private val noOffHookTimeout = Runnable {
        if (!offHookStarted && !finished) {
            listener.onLog("Không phát hiện OFFHOOK trong 8s (cuộc gọi có thể bị từ chối ngay).")
            terminate(0.0, "Không OFFHOOK — gọi thất bại")
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (finished) return
            ticksLeft--
            if (ticksLeft >= 0) listener.onCountdownTick(ticksLeft)
            if (ticksLeft > 0) handler.postDelayed(this, 1000)
        }
    }

    override fun startCall(number: String, durationSeconds: Int) {
        this.durationSeconds = durationSeconds
        offHookStarted = false
        finished = false
        hangUpCalled = false

        registerStateListener()

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            listener.onLog("Đặt cuộc gọi tới $number — sẽ ngắt sau ${durationSeconds}s kể từ khi máy bắt đầu gọi.")
        } catch (e: SecurityException) {
            listener.onLog("Thiếu quyền gọi điện: ${e.message}")
            terminate(0.0, "Thiếu quyền gọi điện")
            return
        }

        handler.postDelayed(noOffHookTimeout, 8000)
    }

    private fun onState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!offHookStarted && !finished) {
                    offHookStarted = true
                    handler.removeCallbacks(noOffHookTimeout)
                    startElapsed = SystemClock.elapsedRealtime()
                    listener.onLog("OFFHOOK — bắt đầu đếm ${durationSeconds}s.")
                    ticksLeft = durationSeconds
                    listener.onCountdownTick(ticksLeft)
                    handler.postDelayed(tickRunnable, 1000)
                    handler.postDelayed(hangUpRunnable, durationSeconds * 1000L)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (offHookStarted && !finished) {
                    val dur = (SystemClock.elapsedRealtime() - startElapsed) / 1000.0
                    val result = if (hangUpCalled)
                        "Đã ngắt sau ${durationSeconds}s"
                    else
                        String.format(Locale.US, "Kết thúc sớm (%.1fs) — có thể số chết/bận", dur)
                    listener.onLog(String.format(Locale.US, "Cuộc gọi kết thúc. Thời lượng thực tế: %.2fs", dur))
                    terminate(dur, result)
                }
            }
        }
    }

    private fun hangUp() {
        if (finished) return
        hangUpCalled = true
        try {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                val ended = telecomManager.endCall()
                listener.onLog(
                    if (ended) "Đã gọi endCall() đúng ${durationSeconds}s."
                    else "Đã gọi endCall() nhưng hệ thống không xác nhận."
                )
            } else {
                listener.onLog("Thiếu quyền ANSWER_PHONE_CALLS — không thể tự ngắt.")
            }
        } catch (e: Exception) {
            listener.onLog("Lỗi khi ngắt: ${e.message}")
        }
        handler.postDelayed(hangupFallback, 3000)
    }

    override fun cancel() {
        if (finished) return
        val dur = if (offHookStarted) (SystemClock.elapsedRealtime() - startElapsed) / 1000.0 else 0.0
        try {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
            ) telecomManager.endCall()
        } catch (_: Exception) {
        }
        terminate(dur, "Đã hủy")
    }

    private fun terminate(dur: Double, result: String) {
        if (finished) return
        listener.onResult(dur, result)
        completeCall()
    }

    private fun completeCall() {
        if (finished) return
        finished = true
        handler.removeCallbacks(hangUpRunnable)
        handler.removeCallbacks(hangupFallback)
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(noOffHookTimeout)
        unregisterStateListener()
        listener.onCallFinished()
    }

    private fun registerStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onState(state)
            }
            telephonyCallback = cb
            telephonyManager.registerTelephonyCallback(context.mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            val psl = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
            }
            phoneStateListener = psl
            @Suppress("DEPRECATION")
            telephonyManager.listen(psl, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
            phoneStateListener = null
        }
    }
}
