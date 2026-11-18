package com.immichframe.immichframe

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat

class SeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dialogPreferenceStyle
) : DialogPreference(context, attrs, defStyleAttr) {

    var min: Int = 0
    var max: Int = 100
    var currentValue: Int = 0

    init {
        // Read custom attributes from XML
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.SeekBarPreference)
            min = typedArray.getInt(R.styleable.SeekBarPreference_min, 0)
            max = typedArray.getInt(R.styleable.SeekBarPreference_max, 100)
            typedArray.recycle()
        }

        dialogLayoutResource = R.layout.preference_seekbar
    }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any {
        return a.getInt(index, 0)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        currentValue = getPersistedInt((defaultValue as? Int) ?: 0)
        updateSummary()
    }

    fun saveValue(value: Int) {
        currentValue = value
        persistInt(value)
        updateSummary()
    }

    private fun updateSummary() {
        summary = if (key == "image_gamma") {
            String.format("%.2f", currentValue / 100.0)
        } else {
            currentValue.toString()
        }
    }

    class SeekBarPreferenceDialogFragment : PreferenceDialogFragmentCompat() {
        private var seekBar: SeekBar? = null
        private var valueText: TextView? = null
        private var minText: TextView? = null
        private var maxText: TextView? = null
        private var resetButton: android.widget.Button? = null

        override fun onBindDialogView(view: View) {
            super.onBindDialogView(view)

            val preference = preference as? SeekBarPreference ?: run {
                // Safety check: if preference is not SeekBarPreference, log error and return
                Log.e("SeekBarPreference", "Dialog bound to non-SeekBarPreference")
                return
            }
            seekBar = view.findViewById(R.id.seekbar)
            valueText = view.findViewById(R.id.seekbar_value)
            minText = view.findViewById(R.id.seekbar_min)
            maxText = view.findViewById(R.id.seekbar_max)
            resetButton = view.findViewById(R.id.reset_button)

            // Set min/max labels
            val isGamma = preference.key == "image_gamma"
            minText?.text = if (isGamma) {
                String.format("%.1f", preference.min / 100.0)
            } else {
                preference.min.toString()
            }
            maxText?.text = if (isGamma) {
                String.format("%.1f", preference.max / 100.0)
            } else {
                preference.max.toString()
            }

            seekBar?.apply {
                max = preference.max - preference.min
                progress = preference.currentValue - preference.min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        val value = progress + preference.min
                        updateValueText(value, isGamma)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            // Set up reset button
            resetButton?.setOnClickListener {
                // Default value: 0 for most, 100 for gamma
                val defaultValue = if (isGamma) 100 else 0
                seekBar?.progress = defaultValue - preference.min
                updateValueText(defaultValue, isGamma)
            }

            updateValueText(preference.currentValue, isGamma)
        }

        private fun updateValueText(value: Int, isGamma: Boolean) {
            valueText?.text = if (isGamma) {
                String.format("%.2f", value / 100.0)
            } else {
                value.toString()
            }
        }

        override fun onDialogClosed(positiveResult: Boolean) {
            if (positiveResult) {
                val preference = preference as? SeekBarPreference ?: run {
                    Log.e("SeekBarPreference", "Dialog closed for non-SeekBarPreference")
                    return
                }
                val value = (seekBar?.progress ?: 0) + preference.min
                if (preference.callChangeListener(value)) {
                    preference.saveValue(value)
                }
            }
        }

        companion object {
            fun newInstance(key: String): SeekBarPreferenceDialogFragment {
                val fragment = SeekBarPreferenceDialogFragment()
                val bundle = android.os.Bundle(1)
                bundle.putString(ARG_KEY, key)
                fragment.arguments = bundle
                return fragment
            }
        }
    }
}
