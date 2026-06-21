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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BlockingVpnService : VpnService() {

    companion object {
        private const val TAG = "BlockingVpnService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1

        // ⚠️ غير هذا الرابط إلى webhook الخاص بك
        private const val DISCORD_WEBHOOK_URL = "https://discord.com/api/webhooks/1454075152059858997/Xn9KHc-trhyWFZkMnKmizyJOKF0rmoy7egHOvQIkHujGeKJVvf5BJfttviDmV3XZoisi"

        private val BLOCKED_DOMAINS = setOf(
            "facebook.com", "www.facebook.com", "m.facebook.com", "fb.com",
            "instagram.com", "www.instagram.com", "cdninstagram.com",
            "twitter.com", "www.twitter.com", "x.com",
            "tiktok.com", "www.tiktok.com", "vm.tiktok.com",
            "snapchat.com", "www.snapchat.com",
            "discord.com", "www.discord.com", "discord.gg",
            "telegram.org", "www.telegram.org", "t.me",
            "whatsapp.com", "www.whatsapp.com",
            "youtube.com", "www.youtube.com", "youtu.be"
        )
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
        startForeground(NOTIFICATION_ID, createNotification("🛡️ الحماية مفعلة"))
        
        // إرسال رسالة تفعيل عند بدء الخدمة
        sendTestDiscordAlert()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) { }

        val builder = Builder()
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
        val inputStream = FileInputStream(vpnFd.fileDescriptor)
        val outputStream = FileOutputStream(vpnFd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (true) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) break

                val packet = buffer.copyOf(length)
                
                // محاولة استخراج النطاق من حزمة DNS Query
                val domain = extractDomainFromDnsPacketImproved(packet)

                if (domain != null && isDomainBlocked(domain)) {
                    val appName = getAppNameFromUid(android.os.Process.myUid())
                    sendDiscordAlert(appName, domain)
                    Log.d(TAG, "🚫 تم حظر: $domain")
                    continue // حظر الحزمة (لا نعيدها)
                }

                // تمرير الحزم الآمنة
                outputStream.write(packet)
                outputStream.flush()

            } catch (e: Exception) {
                Log.e(TAG, "خطأ في معالجة الحزمة: ${e.message}")
                // لا نوقف الـ loop عند حدوث خطأ (هذا يمنع انقطاع الإنترنت)
                try { Thread.sleep(5) } catch (_: Exception) {}
            }
        }
    }

    /** دالة استخراج اسم النطاق من حزمة DNS Query - محسنة */
    private fun extractDomainFromDnsPacketImproved(packet: ByteArray): String? {
        try {
            if (packet.size < 20) return null

            // QR bit == 0 → DNS Query
            val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            if ((flags and 0x8000) != 0) return null // ليس استعلام

            var pos = 12
            val domainBuilder = StringBuilder()

            while (pos < packet.size) {
                val len = packet[pos].toInt() and 0xFF
                if (len == 0) break
                if (pos + len >= packet.size) break

                if (domainBuilder.isNotEmpty()) domainBuilder.append('.')
                domainBuilder.append(String(packet, pos + 1, len, Charsets.US_ASCII))
                pos += len + 1
            }

            val domain = domainBuilder.toString().lowercase(Locale.getDefault())
            return if (domain.isNotEmpty() && domain.contains(".")) domain else null

        } catch (e: Exception) {
            return null
        }
    }

    private fun isDomainBlocked(domain: String): Boolean {
        return BLOCKED_DOMAINS.any { domain.contains(it, ignoreCase = true) }
    }

    private fun getAppNameFromUid(uid: Int): String {
        return try {
            val pm = packageManager
            val packages = pm.getPackagesForUid(uid)
            if (!packages.isNullOrEmpty()) {
                val appInfo = pm.getApplicationInfo(packages[0], 0)
                pm.getApplicationLabel(appInfo).toString()
            } else {
                "تطبيق غير معروف"
            }
        } catch (e: Exception) {
            "تطبيق غير معروف"
        }
    }

    /** إرسال تنبيه عند حظر نطاق */
    private fun sendDiscordAlert(appName: String, domain: String) {
        try {
            val json = JSONObject().apply {
                put("content", "🚨 **تم حظر محاولة اتصال**")
                put("embeds", arrayOf(
                    JSONObject().apply {
                        put("title", "🔒 حظر ناجح")
                        put("color", 16711680) // أحمر
                        put("fields", arrayOf(
                            JSONObject().apply {
                                put("name", "📱 التطبيق")
                                put("value", appName)
                                put("inline", true)
                            },
                            JSONObject().apply {
                                put("name", "🌐 النطاق")
                                put("value", domain)
                                put("inline", true)
                            },
                            JSONObject().apply {
                                put("name", "⏰ الوقت")
                                put("value", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
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
                    Log.e(TAG, "❌ فشل إرسال تنبيه الحظر: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    Log.i(TAG, "✅ تم إرسال تنبيه الحظر إلى Discord")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إرسال تنبيه الحظر", e)
        }
    }

    /** رسالة تفعيل الحماية */
    private fun sendTestDiscordAlert() {
        try {
            val json = JSONObject().apply {
                put("content", "✅ **تم تفعيل حماية الجهاز بنجاح!**")
                put("embeds", arrayOf(
                    JSONObject().apply {
                        put("title", "🛡️ الحماية نشطة الآن")
                        put("color", 65280) // أخضر
                        put("description", "سيتم إشعارك فوراً عند أي محاولة للوصول إلى مواقع التواصل الاجتماعي المحظورة.")
                    }
                ))
            }

            val request = Request.Builder()
                .url(DISCORD_WEBHOOK_URL)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "❌ فشل إرسال رسالة التفعيل: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    Log.i(TAG, "✅ تم إرسال رسالة التفعيل بنجاح")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إرسال رسالة التفعيل", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "حماية الجهاز",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN نشط لحظر مواقع التواصل الاجتماعي"
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

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("🛡️ الحماية مفعلة")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("🛡️ الحماية مفعلة")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            vpnInterface?.close()
        } catch (e: Exception) { }
        executor.shutdown()
    }
}
