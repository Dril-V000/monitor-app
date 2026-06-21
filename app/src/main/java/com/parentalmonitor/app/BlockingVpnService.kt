package com.parentalmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BlockingVpnService : VpnService() {

    companion object {
        private const val TAG = "BlockingVpnService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val DISCORD_WEBHOOK_URL = "https://discord.com/api/webhooks/1454075152059858997/Xn9KHc-trhyWFZkMnKmizyJOKF0rmoy7egHOvQIkHujGeKJVvf5BJfttviDmV3XZoisi"

        // قائمة النطاقات المحظورة (وسائل التواصل الاجتماعي)
        private val BLOCKED_DOMAINS = setOf(
            "facebook.com", "www.facebook.com", "m.facebook.com", "fb.com", "fbcdn.net",
            "instagram.com", "www.instagram.com", "cdninstagram.com",
            "twitter.com", "www.twitter.com", "x.com",
            "tiktok.com", "www.tiktok.com", "vm.tiktok.com",
            "snapchat.com", "www.snapchat.com",
            "discord.com", "www.discord.com", "discord.gg",
            "telegram.org", "www.telegram.org", "t.me",
            "whatsapp.com", "www.whatsapp.com",
            "youtube.com", "www.youtube.com", "youtu.be",
            "reddit.com", "www.reddit.com",
            "pinterest.com", "www.pinterest.com",
            "tumblr.com", "www.tumblr.com"
        )

        // قائمة التطبيقات المسموحة (مثل واتساب)
        private val ALLOWED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("جاري حماية الجهاز..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) { /* تجاهل */ }

        val builder = VpnService.Builder()
            .setSession("حماية الجهاز")
            .addAddress("10.0.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setBlocking(true)

        vpnInterface = builder.establish()
        vpnInterface?.let {
            executor.execute { 
                processPackets(it) 
            }
        }
    }

    private fun processPackets(vpnFd: ParcelFileDescriptor) {
        val buffer = ByteBuffer.allocate(32767)
        val channel = DatagramChannel.open()
        channel.connect(InetSocketAddress("127.0.0.1", 0))
        channel.configureBlocking(false)

        while (true) {
            try {
                val length = vpnFd.fd.read(buffer)
                if (length <= 0) break

                // تحليل الحزمة لاستخراج النطاق المطلوب
                val packet = buffer.array().copyOf(length)
                val domain = extractDomainFromDnsPacket(packet)

                if (domain != null && isDomainBlocked(domain)) {
                    // معرفة التطبيق المرسل
                    val uid = getPacketUid(packet)
                    val appName = getAppNameFromUid(uid)

                    // إرسال تقرير إلى Discord
                    sendDiscordAlert(appName, domain)

                    // حظر الحزمة (تجاهلها)
                    continue
                }

                // إعادة إرسال الحزمة
                vpnFd.fd.write(packet)
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في معالجة الحزمة: ${e.message}")
                break
            }
        }
    }

    private fun extractDomainFromDnsPacket(packet: ByteArray): String? {
        try {
            // تحليل بسيط لحزم DNS (البايتات 12-... تحتوي على اسم النطاق)
            if (packet.size < 12) return null
            // تحقق من أن هذه حزمة DNS (منفذ 53)
            if (packet[2].toInt() and 0x80 == 0) return null
            
            var pos = 12
            val domainBuilder = StringBuilder()
            while (pos < packet.size) {
                val len = packet[pos].toInt()
                if (len == 0) break
                if (pos + len >= packet.size) break
                if (domainBuilder.isNotEmpty()) domainBuilder.append('.')
                domainBuilder.append(String(packet, pos + 1, len))
                pos += len + 1
            }
            return domainBuilder.toString().ifEmpty { null }
        } catch (e: Exception) {
            return null
        }
    }

    private fun isDomainBlocked(domain: String): Boolean {
        return BLOCKED_DOMAINS.any { domain.contains(it, ignoreCase = true) }
    }

    private fun getPacketUid(packet: ByteArray): Int {
        // في التطبيق الحقيقي، استخرج UID من الحزمة (معقد)
        // سنستخدم نهجاً مبسطاً: نأخذ UID من السياق
        return android.os.Process.myUid()
    }

    private fun getAppNameFromUid(uid: Int): String {
        return try {
            val pm = packageManager
            val packages = pm.getPackagesForUid(uid)
            if (packages != null && packages.isNotEmpty()) {
                val appInfo = pm.getApplicationInfo(packages[0], 0)
                pm.getApplicationLabel(appInfo).toString()
            } else {
                "تطبيق غير معروف"
            }
        } catch (e: Exception) {
            "تطبيق غير معروف"
        }
    }

    private fun sendDiscordAlert(appName: String, domain: String) {
        val json = JSONObject().apply {
            put("content", "🚨 **محاولة اتصال محظورة**")
            put("embeds", arrayOf(
                JSONObject().apply {
                    put("title", "تفاصيل المحاولة")
                    put("color", 16711680)
                    put("fields", arrayOf(
                        JSONObject().apply {
                            put("name", "📱 التطبيق")
                            put("value", appName)
                            put("inline", true)
                        },
                        JSONObject().apply {
                            put("name", "🌐 الموقع")
                            put("value", domain)
                            put("inline", true)
                        },
                        JSONObject().apply {
                            put("name", "⏰ الوقت")
                            put("value", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                            put("inline", false)
                        }
                    ))
                }
            ))
        }

        val request = Request.Builder()
            .url(DISCORD_WEBHOOK_URL)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "فشل الإرسال إلى Discord: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                Log.i(TAG, "تم إرسال التقرير إلى Discord")
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "حماية الجهاز",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN نشط لحظر مواقع التواصل"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ الحماية مفعلة")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            vpnInterface?.close()
        } catch (e: Exception) { /* تجاهل */ }
    }
}