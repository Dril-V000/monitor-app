package com.parentalmonitor.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.content.pm.PackageManager

class MainActivity : Activity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: SharedPreferences
    private val PASSWORD = "codeAA-14"

    companion object {
        private const val REQUEST_CODE_ENABLE_ADMIN = 1
        private const val VPN_REQUEST_CODE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MonitorDeviceAdminReceiver::class.java)
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val isConfigured = prefs.getBoolean("is_configured", false)

        if (isConfigured) {
            showPasswordDialog()
            return
        }

        setupMainUI()
    }

    private fun showPasswordDialog() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "أدخل كلمة المرور"
        }

        AlertDialog.Builder(this)
            .setTitle("🔒 حماية الجهاز")
            .setMessage("أدخل كلمة المرور للوصول إلى الإعدادات")
            .setView(editText)
            .setPositiveButton("دخول") { _, _ ->
                if (editText.text.toString() == PASSWORD) {
                    showAppIcon()
                    setupMainUI()
                } else {
                    Toast.makeText(this, "❌ كلمة مرور خاطئة", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("إلغاء") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupMainUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "🛡️ حماية الجهاز"
            textSize = 24f
            setPadding(0, 0, 0, 48)
        }

        val statusText = TextView(this).apply {
            text = getStatusText()
            textSize = 16f
            setPadding(0, 0, 0, 48)
        }

        val enableAdminButton = Button(this).apply {
            text = "🔑 تفعيل صلاحية المدير"
            setOnClickListener { requestAdminPermission() }
        }

        val enableVpnButton = Button(this).apply {
            text = "🌐 تفعيل حماية الإنترنت (VPN)"
            setOnClickListener { startVpn() }
        }

        val configureButton = Button(this).apply {
            text = "🔒 تفعيل الحماية الكاملة وإخفاء التطبيق"
            setOnClickListener {
                enableFullProtection()
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(enableAdminButton)
        layout.addView(enableVpnButton)
        layout.addView(configureButton)
        setContentView(layout)
    }

    private fun getStatusText(): String {
        val isAdminActive = devicePolicyManager.isAdminActive(adminComponent)
        val isVpnActive = prefs.getBoolean("vpn_active", false)
        return buildString {
            append(if (isAdminActive) "✅ صلاحية المدير: مفعّلة\n" else "❌ صلاحية المدير: غير مفعّلة\n")
            append(if (isVpnActive) "✅ VPN: مفعّل\n" else "❌ VPN: غير مفعّل\n")
            append(if (devicePolicyManager.isDeviceOwnerApp(packageName)) "✅ Device Owner: مفعّل\n" else "⚠️ Device Owner: غير مفعّل (اختياري)")
        }
    }

    private fun requestAdminPermission() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "هذه الصلاحية تسمح للتطبيق بحماية الجهاز من التطبيقات الضارة."
            )
        }
        startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startService(Intent(this, BlockingVpnService::class.java))
            prefs.edit().putBoolean("vpn_active", true).apply()
            Toast.makeText(this, "✅ تم تفعيل حماية الإنترنت", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableFullProtection() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            requestAdminPermission()
            Toast.makeText(this, "⚠️ يرجى تفعيل صلاحية المدير أولاً", Toast.LENGTH_LONG).show()
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
            Toast.makeText(this, "⚠️ يرجى الموافقة على اتصال VPN", Toast.LENGTH_LONG).show()
            return
        } else {
            startService(Intent(this, BlockingVpnService::class.java))
            prefs.edit().putBoolean("vpn_active", true).apply()
        }

        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
        }

        prefs.edit().putBoolean("is_configured", true).apply()
        hideAppIcon()

        Toast.makeText(this, "🔒 تم تفعيل الحماية الكاملة", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun hideAppIcon() {
        val pm = packageManager
        pm.setApplicationEnabledSetting(
            packageName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0
        )
    }

    private fun showAppIcon() {
        val pm = packageManager
        pm.setApplicationEnabledSetting(
            packageName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            0
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_ENABLE_ADMIN -> {
                Toast.makeText(this, "✅ تم تحديث صلاحية المدير", Toast.LENGTH_SHORT).show()
                recreate()
            }
            VPN_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    startService(Intent(this, BlockingVpnService::class.java))
                    prefs.edit().putBoolean("vpn_active", true).apply()
                    Toast.makeText(this, "✅ تم تفعيل حماية الإنترنت", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ لم يتم تفعيل VPN", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
