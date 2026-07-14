package com.immichframe.immichframe

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormatSymbols
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_view, rootKey)
        val chkUseWebView = findPreference<SwitchPreferenceCompat>("useWebView")
        val chkBlurredBackground = findPreference<SwitchPreferenceCompat>("blurredBackground")
        val chkShowCurrentDate = findPreference<SwitchPreferenceCompat>("showCurrentDate")
        val chkActiveTimes = findPreference<SwitchPreferenceCompat>("activeTimes")
        val editActiveSchedule = findPreference<Preference>("active_schedule_edit")
        val adminActiveSchedule = findPreference<Preference>("active_schedule_admin")


        //obfuscate the authSecret
        val authPref = findPreference<EditTextPreference>("authSecret")
        authPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // Update visibility based on switches
        val useWebView = chkUseWebView?.isChecked ?: false
        chkBlurredBackground?.isVisible = !useWebView
        chkShowCurrentDate?.isVisible = !useWebView
        val activeTimes = chkActiveTimes?.isChecked ?: false
        editActiveSchedule?.isVisible = activeTimes
        adminActiveSchedule?.isVisible = activeTimes
        updateAdminSummary(adminActiveSchedule)
        updateScheduleSummary(editActiveSchedule)

        // React to changes
        chkUseWebView?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as Boolean
            chkBlurredBackground?.isVisible = !value
            chkShowCurrentDate?.isVisible = !value
            //add android settings button
            true
        }
        chkActiveTimes?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as Boolean
            editActiveSchedule?.isVisible = value
            adminActiveSchedule?.isVisible = value
            true
        }
        editActiveSchedule?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ActiveScheduleActivity::class.java))
            true
        }
        adminActiveSchedule?.setOnPreferenceClickListener {
            val dpm = requireContext()
                .getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = FrameDeviceAdminReceiver.componentName(requireContext())
            if (dpm.isAdminActive(component)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Screen-Off Permission")
                    .setMessage("ImmichFrame can already turn the screen off. Disable this permission?")
                    .setPositiveButton("Disable") { _, _ ->
                        dpm.removeActiveAdmin(component)
                        // removeActiveAdmin applies asynchronously; refresh once it takes effect.
                        Handler(Looper.getMainLooper()).postDelayed({
                            updateAdminSummary(adminActiveSchedule)
                        }, 500)
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_description),
                    )
                }
                startActivity(intent)
            }
            true
        }
        val chkSettingsLock = findPreference<SwitchPreferenceCompat>("settingsLock")
        chkSettingsLock?.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (enabling) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Confirm Action")
                    .setMessage(
                        "This will disable access to the settings screen, the only way back is via RPC commands (or uninstall/reinstall).\n" +
                                "Are you absolutely sure?"
                    )
                    .setPositiveButton("Yes", null) // Proceed
                    .setNegativeButton("No") { dialog, _ ->
                        chkSettingsLock.isChecked = false // revert
                        dialog.dismiss()
                    }
                    .show()
            }
            true
        }


        val btnClose = findPreference<Preference>("closeSettings")
        btnClose?.setOnPreferenceClickListener {
            val url = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("webview_url", "")?.trim()
            val urlPattern = Regex("^https?://.+")
            return@setOnPreferenceClickListener if (url.isNullOrEmpty() || !url.matches(urlPattern)) {
                Toast.makeText(requireContext(), "Please enter a valid server URL.", Toast.LENGTH_LONG).show()
                false
            } else {
                activity?.setResult(Activity.RESULT_OK)
                activity?.finish()
                true
            }
        }

        val btnAndroidSettings = findPreference<Preference>("androidSettings")
        btnAndroidSettings?.setOnPreferenceClickListener {
            val context = requireContext()

            // Only show Toast + auto-return on Android 9 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                Toast.makeText(context, "Returning to app in 2 minutes…", Toast.LENGTH_LONG).show()

                // Schedule return after 2 minutes
                Handler(Looper.getMainLooper()).postDelayed({
                    val returnIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    returnIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(returnIntent)
                }, 2 * 60 * 1000)
            }

            // Launch Android settings
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)

            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateAdminSummary(findPreference("active_schedule_admin"))
        updateScheduleSummary(findPreference("active_schedule_edit"))
    }

    private fun updateScheduleSummary(preference: Preference?) {
        val pref = preference ?: return
        val json = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("activeSchedule", null)
        pref.summary = summarizeSchedule(json)
    }

    private fun summarizeSchedule(json: String?): String {
        val schedule = Helpers.parseActiveSchedule(json)
        if (schedule.rules.isEmpty()) return getString(R.string.active_schedule_always)
        return schedule.rules.joinToString("\n") { rule ->
            val days = summarizeDays(rule.days)
            val times = rule.ranges.joinToString(", ") { "${it.start}–${it.end}" }
            if (days.isEmpty()) times else "$days: $times"
        }
    }

    // Collapse a set of Calendar weekday constants into a compact label, e.g. "Mon–Fri, Sun".
    private fun summarizeDays(days: Set<Int>): String {
        val order = intArrayOf(2, 3, 4, 5, 6, 7, 1) // Mon..Sun
        val indices = order.indices.filter { days.contains(order[it]) }
        if (indices.isEmpty()) return ""
        val names = DateFormatSymbols(Locale.getDefault()).shortWeekdays
        fun name(dayInt: Int) = names.getOrNull(dayInt)?.takeIf { it.isNotBlank() } ?: dayInt.toString()
        val parts = mutableListOf<String>()
        var start = 0
        while (start < indices.size) {
            var end = start
            while (end + 1 < indices.size && indices[end + 1] == indices[end] + 1) end++
            if (end - start >= 2) {
                parts.add("${name(order[indices[start]])}–${name(order[indices[end]])}")
            } else {
                for (k in start..end) parts.add(name(order[indices[k]]))
            }
            start = end + 1
        }
        return parts.joinToString(", ")
    }

    private fun updateAdminSummary(preference: Preference?) {
        val pref = preference ?: return
        val dpm = requireContext()
            .getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enabled = dpm.isAdminActive(FrameDeviceAdminReceiver.componentName(requireContext()))
        pref.summary = if (enabled) {
            "Enabled — the frame can turn off the screen and sleep the device. Tap to disable."
        } else {
            "Allow the frame to turn off the screen and sleep the device during inactive hours"
        }
    }
}