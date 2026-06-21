package com.parentalmonitor.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * شاشة تطبيق واضحة وظاهرة: تفعيل صلاحية المدير، تفعيل/إيقاف الحماية.
 * كل الأزرار شغالة بدون أي قفل أو إخفاء - المستخدم يتحكم بالتطبيق
 * بحرية، وأي إيقاف يرسل تنبيه فوري لمدير الجهاز عبر ديسكورد.
 */
class MainActivity : Activity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var statusText: TextView
    private lateinit var vpnButton: Button

    companion object {
        private const val REQUEST_CODE_ENABLE_ADMIN = 1
        private const val REQUEST_CODE_VPN_PERMISSION = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MonitorDeviceAdminReceiver::class.java)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "🛡️ حماية الجهاز"
            textSize = 22f
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 32)
        }

        val adminButton = Button(this).apply {
            text = "تفعيل صلاحية المدير"
            setOnClickListener { requestAdminPermission() }
        }

        vpnButton = Button(this).apply {
            setOnClickListener { toggleVpn() }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(adminButton)
        layout.addView(vpnButton)
        setContentView(layout)

        updateStatus()
    }

    private fun requestAdminPermission() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "هذا التطبيق يحمي الجهاز من تثبيت تطبيقات معينة. الصلاحية مطلوبة لتفعيل الحماية. " +
                "بعد التفعيل، لن يكون بالإمكان حذف هذا التطبيق إلا من خلال تعطيل صلاحية المدير أولاً."
            )
        }
        startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
    }

    /**
     * يمنع حذف التطبيق عبر صلاحية النظام الرسمية - بشكل شفاف وظاهر.
     * التطبيق يبقى مرئياً بقائمة التطبيقات، ومحاولة حذفه تظهر رسالة واضحة
     * تشرح أنه محمي بصلاحية إدارة الجهاز (مو مخفي أو مموه).
     */
    private fun blockUninstall() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
            } catch (e: SecurityException) {
                // قد يحتاج صلاحية Device Owner كاملة على بعض إصدارات أندرويد
            }
        }
    }

    /**
     * يشغّل أو يوقف الـVPN. الزر شغال دائماً وواضح - لا قفل ولا تعطيل.
     * كل تشغيل أو إيقاف يُسجَّل ويُرسَل كتنبيه عبر BlockingVpnService نفسه.
     */
    private fun toggleVpn() {
        if (BlockingVpnService.isRunning) {
            startService(Intent(this, BlockingVpnService::class.java).apply { action = "STOP" })
            updateStatus()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, REQUEST_CODE_VPN_PERMISSION)
            } else {
                startService(Intent(this, BlockingVpnService::class.java))
                updateStatus()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_ENABLE_ADMIN -> {
                blockUninstall()
                updateStatus()
            }
            REQUEST_CODE_VPN_PERMISSION -> {
                if (resultCode == RESULT_OK) {
                    startService(Intent(this, BlockingVpnService::class.java))
                }
                updateStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isAdminActive = devicePolicyManager.isAdminActive(adminComponent)
        val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
        val isVpnRunning = BlockingVpnService.isRunning

        statusText.text = buildString {
            append(if (isAdminActive) "✅ صلاحية المسؤول: مفعّلة\n" else "❌ صلاحية المسؤول: غير مفعّلة\n")
            append(if (isDeviceOwner) "✅ Device Owner: مفعّل (تعليق التطبيقات يعمل)\n"
                   else "⚠️ Device Owner: غير مفعّل - يحتاج إعداد عبر ADB (مرة واحدة)\n")
            append(if (isAdminActive) "🔒 الحذف: محمي (يحتاج تعطيل صلاحية المدير أولاً)\n" else "")
            append(if (isVpnRunning) "✅ حماية الإنترنت: مفعّلة" else "⭕ حماية الإنترنت: متوقفة")
        }

        vpnButton.text = if (isVpnRunning) "إيقاف حماية الإنترنت" else "تفعيل حماية الإنترنت"
    }
}
