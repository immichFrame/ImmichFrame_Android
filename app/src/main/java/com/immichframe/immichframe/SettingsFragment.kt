package com.immichframe.immichframe

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.fragment.app.DialogFragment

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_view, rootKey)
        val chkUseWebView = findPreference<SwitchPreferenceCompat>("useWebView")
        val chkBlurredBackground = findPreference<SwitchPreferenceCompat>("blurredBackground")
        val chkShowCurrentDate = findPreference<SwitchPreferenceCompat>("showCurrentDate")
        val chkScreenDim = findPreference<SwitchPreferenceCompat>("screenDim")
        val txtDimTime = findPreference<EditTextPreference>("dim_time_range")
        val chkImageAdjustments = findPreference<SwitchPreferenceCompat>("imageAdjustments")
        val imageBrightness = findPreference<SeekBarPreference>("image_brightness")
        val imageContrast = findPreference<SeekBarPreference>("image_contrast")
        val imageRedChannel = findPreference<SeekBarPreference>("image_red_channel")
        val imageGreenChannel = findPreference<SeekBarPreference>("image_green_channel")
        val imageBlueChannel = findPreference<SeekBarPreference>("image_blue_channel")
        val imageGamma = findPreference<SeekBarPreference>("image_gamma")


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
        val imageAdjustments = chkImageAdjustments?.isChecked ?: false
        imageBrightness?.isVisible = imageAdjustments
        imageContrast?.isVisible = imageAdjustments
        imageRedChannel?.isVisible = imageAdjustments
        imageGreenChannel?.isVisible = imageAdjustments
        imageBlueChannel?.isVisible = imageAdjustments
        imageGamma?.isVisible = imageAdjustments

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
        chkImageAdjustments?.setOnPreferenceChangeListener { preference, newValue ->
            val value = newValue as Boolean
            imageBrightness?.isVisible = value
            imageContrast?.isVisible = value
            imageRedChannel?.isVisible = value
            imageGreenChannel?.isVisible = value
            imageBlueChannel?.isVisible = value
            imageGamma?.isVisible = value

            // Save the preference value immediately so it takes effect
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean("imageAdjustments", value)
                .apply()

            true
        }
        val chkSettingsLock = findPreference<SwitchPreferenceCompat>("settingsLock")
        chkSettingsLock?.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (enabling) {
                AlertDialog.Builder(requireContext())
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

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is SeekBarPreference) {
            val dialogFragment = SeekBarPreference.SeekBarPreferenceDialogFragment.newInstance(preference.key)
            // setTargetFragment is deprecated but still required by PreferenceDialogFragmentCompat
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, "SeekBarPreferenceDialog")
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }
}