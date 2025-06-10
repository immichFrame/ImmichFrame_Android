package com.immichframe.immichframe

import android.app.Activity
import android.content.Intent
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
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_view, rootKey)
        val chkUseWebView = findPreference<SwitchPreferenceCompat>("useWebView")
        val chkBlurredBackground = findPreference<SwitchPreferenceCompat>("blurredBackground")
        val chkShowCurrentDate = findPreference<SwitchPreferenceCompat>("showCurrentDate")
        val chkScreenDim = findPreference<SwitchPreferenceCompat>("screenDim")
        val txtDimTime = findPreference<EditTextPreference>("dim_time_range")


        //obfuscate the authSecret
        val authPref = findPreference<EditTextPreference>("authSecret")
        authPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // Update visibility based on switches
        val useWebView = chkUseWebView?.isChecked ?: false
        chkBlurredBackground?.isVisible = !useWebView
        chkShowCurrentDate?.isVisible = !useWebView
        val screenDim = chkScreenDim?.isChecked ?: false
        txtDimTime?.isVisible = screenDim

        // React to changes
        chkUseWebView?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as Boolean
            chkBlurredBackground?.isVisible = !value
            chkShowCurrentDate?.isVisible = !value
            //add android settings button
            true
        }
        chkScreenDim?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as Boolean
            txtDimTime?.isVisible = value
            true
        }

        val btnClose = findPreference<Preference>("closeSettings")
        btnClose?.setOnPreferenceClickListener {
            val url = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("webview_url", "")?.trim()
            val urlPattern = Regex("^https?://.+")
            return@setOnPreferenceClickListener if (url.isNullOrEmpty()|| !url.matches(urlPattern)) {
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
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
            true
        }

        val timePref = findPreference<EditTextPreference>("dim_time_range")
        timePref?.setOnPreferenceChangeListener { _, newValue ->
            val timeRange = newValue.toString().trim()

            val regex = "^([01]?[0-9]|2[0-3]):([0-5][0-9])-([01]?[0-9]|2[0-3]):([0-5][0-9])$".toRegex()
            if (timeRange.matches(regex)) {
                val (start, end) = timeRange.split("-")
                val (startHour, startMinute) = start.split(":").map { it.toInt() }
                val (endHour, endMinute) = end.split(":").map { it.toInt() }

                // Save parsed time values separately
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
                sharedPreferences.edit()
                    .putInt("dimStartHour", startHour)
                    .putInt("dimStartMinute", startMinute)
                    .putInt("dimEndHour", endHour)
                    .putInt("dimEndMinute", endMinute)
                    .apply()

                true // Accept new value
            } else {
                Toast.makeText(requireContext(), "Invalid time format. Use HH:mm-HH:mm.", Toast.LENGTH_LONG).show()
                false // Reject value change
            }
        }
    }
}