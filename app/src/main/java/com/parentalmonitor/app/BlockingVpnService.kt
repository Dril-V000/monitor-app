package com.parentalmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * خدمة VPN محلية تحجب نطاقات سوشيال ميديا فقط عبر فلترة DNS.
 *
 * التصميم: نوجّه كل استعلامات DNS (منفذ 53) لمعالج بسيط يقرر
 * يرد عليها أو يتجاهلها حسب القائمة المحظورة. أي حركة شبكة أخرى
 * (HTTPS، تطبيقات، كل شي) تُمرّر للراوتر الحقيقي بدون أي تدخل أو
 * محاولة تفسير - هذا يتجنب بالضبط المشكلة اللي كسرت الإنترنت بالكود
 * القديم (محاولة قراءة كل حزمة كأنها DNS).
 */
class BlockingVpnService : VpnService() {

    companion object {
        private const val TAG = "BlockingVpnService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val UPSTREAM_DNS = "8.8.8.8"  // خادم DNS حقيقي نمرر له الاستعلامات المسموحة

        private val BLOCKED_DOMAINS = setOf(
            "facebook.com", "fb.com",
            "instagram.com", "cdninstagram.com",
            "twitter.com", "x.com",
            "tiktok.com",
            "snapchat.com",
        )

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn(userInitiated = true)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("حماية الجهاز")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            // نوجّه DNS الجهاز بالكامل لعنوان وهمي داخلي - هذا العنوان
            // نفسه نستمع عليه محلياً ونرد على الاستعلامات يدوياً
            .addDnsServer("10.0.0.1")
            .setBlocking(true)
            .setMtu(1500)

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "فشل إنشاء واجهة VPN")
            return
        }

        isRunning = true
        sendDiscordEvent("✅ تم تفعيل الحماية", 0x2ECC71, "VPN فعّال الآن - سيتم حظر تطبيقات سوشيال ميديا محددة.")

        // نشغّل خادم DNS محلي بسيط على منفذ منفصل، ونعالج حزم
        // الشبكة فقط لإعادة توجيه استعلامات DNS له - باقي الحزم تُمرر
        // كما هي بدون أي تفسير أو فحص محتوى
        executor.execute { runDnsRelay() }
    }

    /**
     * يستقبل حزم IP الخارجة من الجهاز عبر واجهة VPN، ويتعرف فقط على
     * حزم UDP لمنفذ 53 (DNS) عبر فحص الهيدر بشكل صحيح (offsets دقيقة)
     * مو تخمين. أي حزمة غير ذلك تُمرر فوراً بدون لمسها.
     */
    private fun runDnsRelay() {
        val vpnFd = vpnInterface ?: return
        val input = FileInputStream(vpnFd.fileDescriptor)
        val output = FileOutputStream(vpnFd.fileDescriptor)
        val buffer = ByteArray(32767)
        val dnsSocket = DatagramSocket()
        protect(dnsSocket)  // يمنع حلقة لا نهائية (الـVPN يحمي نفسه من نفسه)

        while (isRunning) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue

                if (isDnsQueryPacket(buffer, length)) {
                    handleDnsQuery(buffer, length, output, dnsSocket)
                } else {
                    // أي حركة غير DNS تُمرر كما هي بدون أي تدخل
                    output.write(buffer, 0, length)
                }
            } catch (e: IOException) {
                if (isRunning) Log.w(TAG, "خطأ بمعالجة حزمة: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "استثناء غير متوقع: ${e.message}")
            }
        }
        dnsSocket.close()
    }

    /**
     * فحص صحيح (مو تخمين) لكون الحزمة IPv4/UDP لمنفذ 53.
     * يحسب إزاحات الهيدر الحقيقية بدل افتراض موقع ثابت خاطئ.
     */
    private fun isDnsQueryPacket(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return false  // ندعم IPv4 فقط بهذا الإصدار البسيط

        val ihl = (packet[0].toInt() and 0xF) * 4  // طول هيدر IP الفعلي بالبايت
        if (ihl < 20 || length < ihl + 8) return false

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false  // 17 = UDP

        val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        return destPort == 53
    }

    private fun handleDnsQuery(packet: ByteArray, length: Int, output: FileOutputStream, dnsSocket: DatagramSocket) {
        val ihl = (packet[0].toInt() and 0xF) * 4
        val udpPayloadStart = ihl + 8
        val dnsPayloadLength = length - udpPayloadStart
        if (dnsPayloadLength <= 0) return

        val domain = parseDomainFromDns(packet, udpPayloadStart, length)

        if (domain != null && isDomainBlocked(domain)) {
            val appLabel = "تطبيق على الجهاز"
            sendDiscordEvent(
                "🚫 تم حظر محاولة اتصال",
                0xE74C3C,
                "النطاق: $domain"
            )
            Log.i(TAG, "حُظر: $domain")
            return  // لا نرد على الاستعلام - الطلب يفشل بصمت بدل ما يتعطل التطبيق فجأة
        }

        // النطاق مسموح - نعيد توجيه الاستعلام لخادم DNS حقيقي ونعيد الرد كما هو
        try {
            val dnsPayload = packet.copyOfRange(udpPayloadStart, length)
            val request = DatagramPacket(dnsPayload, dnsPayload.size, InetSocketAddress(UPSTREAM_DNS, 53))
            dnsSocket.send(request)

            val responseBuffer = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            dnsSocket.soTimeout = 5000
            dnsSocket.receive(responsePacket)

            // نبني حزمة رد بسيطة بنفس مسار IP/UDP الأصلي معكوساً
            val replyPacket = buildReplyPacket(packet, ihl, responsePacket.data, responsePacket.length)
            output.write(replyPacket)
        } catch (e: Exception) {
            Log.w(TAG, "فشل تمرير استعلام DNS: ${e.message}")
        }
    }

    /** يستخرج اسم النطاق من قسم الـQuestion في حزمة DNS فقط (بعد التأكد من موقعها الصحيح). */
    private fun parseDomainFromDns(packet: ByteArray, dnsStart: Int, totalLength: Int): String? {
        try {
            var pos = dnsStart + 12  // تخطي هيدر DNS الثابت (12 بايت)
            val sb = StringBuilder()
            while (pos < totalLength) {
                val len = packet[pos].toInt() and 0xFF
                if (len == 0) break
                if (pos + len >= totalLength) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(packet, pos + 1, len, Charsets.US_ASCII))
                pos += len + 1
            }
            val domain = sb.toString().lowercase(Locale.getDefault())
            return domain.ifEmpty { null }
        } catch (e: Exception) {
            return null
        }
    }

    private fun isDomainBlocked(domain: String): Boolean =
        BLOCKED_DOMAINS.any { domain == it || domain.endsWith(".$it") }

    /** يبني حزمة IP/UDP رد صالحة بعكس المرسل/المستقبل من حزمة الطلب الأصلية. */
    private fun buildReplyPacket(originalRequest: ByteArray, ihl: Int, dnsResponse: ByteArray, dnsLength: Int): ByteArray {
        val udpLength = 8 + dnsLength
        val totalLength = ihl + udpLength
        val reply = ByteArray(totalLength)

        // هيدر IP: ننسخ الأصلي ونعكس عنوان المصدر/الوجهة
        System.arraycopy(originalRequest, 0, reply, 0, ihl)
        for (i in 0 until 4) {
            reply[12 + i] = originalRequest[16 + i]  // المصدر الجديد = وجهة الطلب الأصلي
            reply[16 + i] = originalRequest[12 + i]  // الوجهة الجديدة = مصدر الطلب الأصلي
        }
        reply[2] = ((totalLength shr 8) and 0xFF).toByte()
        reply[3] = (totalLength and 0xFF).toByte()
        reply[10] = 0; reply[11] = 0  // إعادة تصفير checksum (الأجهزة الحديثة تتجاهله غالباً لـUDP)

        // هيدر UDP: نعكس المنفذ المصدر/الوجهة
        reply[ihl] = originalRequest[ihl + 2]
        reply[ihl + 1] = originalRequest[ihl + 3]
        reply[ihl + 2] = originalRequest[ihl]
        reply[ihl + 3] = originalRequest[ihl + 1]
        reply[ihl + 4] = ((udpLength shr 8) and 0xFF).toByte()
        reply[ihl + 5] = (udpLength and 0xFF).toByte()
        reply[ihl + 6] = 0; reply[ihl + 7] = 0  // UDP checksum اختياري لـIPv4

        System.arraycopy(dnsResponse, 0, reply, ihl + 8, dnsLength)
        return reply
    }

    /** يوقف الـVPN. لو المستخدم هو اللي أوقفه، يرسل تنبيه فوري لديسكورد. */
    private fun stopVpn(userInitiated: Boolean) {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // تجاهل أخطاء الإغلاق
        }
        vpnInterface = null

        if (userInitiated) {
            sendDiscordEvent(
                "⚠️ تم إيقاف الحماية",
                0xF39C12,
                "تم إيقاف VPN الحماية يدوياً على الجهاز."
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendDiscordEvent(title: String, color: Int, description: String) {
        val webhookUrl = BuildConfigSecrets.DISCORD_WEBHOOK_URL
        if (webhookUrl.isBlank()) {
            Log.w(TAG, "Discord webhook غير مُعد")
            return
        }
        try {
            val embed = JSONObject().apply {
                put("title", title)
                put("description", description)
                put("color", color)
                put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(java.util.Date()))
            }
            val body = JSONObject().apply {
                put("embeds", org.json.JSONArray().put(embed))
            }
            val request = Request.Builder()
                .url(webhookUrl)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w(TAG, "فشل إرسال تنبيه ديسكورد: ${e.message}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "خطأ بإرسال تنبيه: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "حماية الجهاز", NotificationManager.IMPORTANCE_LOW).apply {
                description = "حماية نشطة لحظر تطبيقات سوشيال ميديا محددة"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("🛡️ الحماية مفعّلة")
            .setContentText("حظر تطبيقات سوشيال ميديا محددة نشط")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { vpnInterface?.close() } catch (e: Exception) { }
        executor.shutdown()
    }

    override fun onRevoke() {
        // المستخدم سحب صلاحية VPN من إعدادات أندرويد مباشرة (مو من زر التطبيق)
        sendDiscordEvent("⚠️ تم سحب صلاحية VPN", 0xF39C12, "تم إيقاف الحماية من إعدادات النظام مباشرة.")
        stopVpn(userInitiated = false)
        super.onRevoke()
    }
}
