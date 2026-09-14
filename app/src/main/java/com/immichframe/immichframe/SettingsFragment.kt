package com.immichframe.immichframe

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_view, rootKey)

        val context = requireContext()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = FrameDeviceAdminReceiver.componentName(context)

        val useWebViewPref = findPreference<SwitchPreferenceCompat>("useWebView")
        val blurredBackgroundPref = findPreference<SwitchPreferenceCompat>("blurredBackground")
        val showCurrentDatePref = findPreference<SwitchPreferenceCompat>("showCurrentDate")

        fun updateDisplayOptions(isWebView: Boolean) {
            blurredBackgroundPref?.isEnabled = !isWebView
            showCurrentDatePref?.isEnabled = !isWebView
        }

        useWebViewPref?.let { pref ->
            updateDisplayOptions(pref.isChecked)
            pref.setOnPreferenceChangeListener { _, newValue ->
                updateDisplayOptions(newValue as Boolean)
                true
            }
        }

        val authSecretPref = findPreference<EditTextPreference>("authSecret")
        authSecretPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        authSecretPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
            val text = preference.text
            if (text.isNullOrEmpty()) "Not set" else "••••••••"
        }

        findPreference<SwitchPreferenceCompat>("settingsLock")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                Toast.makeText(context, "Settings lock enabled.", Toast.LENGTH_SHORT).show()
            }
            true
        }

        findPreference<SwitchPreferenceCompat>("activeTimes")?.setOnPreferenceChangeListener { _, _ ->
            view?.post { updateScheduleSummary() }
            true
        }

        findPreference<Preference>("active_schedule_edit")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ActiveScheduleActivity::class.java))
            true
        }

        findPreference<Preference>("androidSettings")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            true
        }

        // Validierung der Server-URL vor dem Schließen der Einstellungen
        findPreference<Preference>("closeSettings")?.setOnPreferenceClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val url = prefs.getString("webview_url", "")?.trim().orEmpty()

            if (!isValidServerUrl(url)) {
                Toast.makeText(
                    context,
                    "Please enter a valid ImmichFrame Server URL (http:// or https://)",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnPreferenceClickListener true
            }

            requireActivity().setResult(Activity.RESULT_OK)
            requireActivity().finish()
            true
        }

        findPreference<Preference>("motion_admin_permission")?.setOnPreferenceClickListener {
            requestDeviceAdmin()
            true
        }

        findPreference<Preference>("active_schedule_admin")?.setOnPreferenceClickListener {
            requestDeviceAdmin()
            true
        }

        updateScheduleSummary()
    }

    override fun onResume() {
        super.onResume()
        updateAdminPreferences()
        updateScheduleSummary()
    }

    private fun isValidServerUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = Uri.parse(url)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun updateScheduleSummary() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val raw = prefs.getString("activeSchedule", null)
        val schedule = Helpers.parseActiveSchedule(raw)

        // CodeRabbit-Fix: Bei leeren Regeln leerer String -> Fallback greift
        val summary = if (schedule.rules.isEmpty()) {
            ""
        } else {
            formatScheduleSummary(schedule)
        }

        findPreference<Preference>("active_schedule_edit")?.let { pref ->
            pref.summary = if (summary.isNotBlank()) summary else "Per-weekday on/off times"
        }
    }

    private fun formatScheduleSummary(schedule: Helpers.ActiveSchedule): String {
        val now = java.util.Calendar.getInstance()
        return if (Helpers.isActiveNow(schedule, now)) {
            "Currently active"
        } else {
            val next = Helpers.nextActiveStart(schedule, now)
            if (next != null) {
                val sameDay = now.get(java.util.Calendar.YEAR) == next.get(java.util.Calendar.YEAR) &&
                        now.get(java.util.Calendar.DAY_OF_YEAR) == next.get(java.util.Calendar.DAY_OF_YEAR)
                val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                if (sameDay) {
                    "Inactive until ${timeFormat.format(next.time)}"
                } else {
                    val dayFormat = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
                    "Inactive until ${dayFormat.format(next.time)} ${timeFormat.format(next.time)}"
                }
            } else {
                "Currently inactive"
            }
        }
    }

    private fun requestDeviceAdmin() {
        if (dpm.isAdminActive(adminComponent)) return
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allows ImmichFrame to turn off the screen and sleep the device when inactive."
            )
        }
        startActivity(intent)
    }

    private fun updateAdminPreferences() {
        val isAdmin = dpm.isAdminActive(adminComponent)
        val motionAdminPref = findPreference<Preference>("motion_admin_permission")
        val activeAdminPref = findPreference<Preference>("active_schedule_admin")

        if (isAdmin) {
            motionAdminPref?.summary = "Permission granted ✓"
            motionAdminPref?.isEnabled = false
            activeAdminPref?.summary = "Permission granted ✓"
            activeAdminPref?.isEnabled = false
        } else {
            motionAdminPref?.summary = "Allow the frame to turn off the screen completely (Device Admin)"
            motionAdminPref?.isEnabled = true
            activeAdminPref?.summary = "Allow the frame to turn off the screen and sleep the device during inactive hours"
            activeAdminPref?.isEnabled = true
        }
    }
}