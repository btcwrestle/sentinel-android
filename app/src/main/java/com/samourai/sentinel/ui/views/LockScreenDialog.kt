package com.samourai.sentinel.ui.views

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.CycleInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.databinding.FragmentLockScreenBinding
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.koin.java.KoinJavaComponent.inject


/**
 * sentinel-android
 *
 * @author Sarath
 */

/**
 * #210: the prompt text travels in `arguments` instead of a constructor
 * field, so a recreated dialog keeps its prompt.
 * #220: the pin itself never travels in `arguments` (that bundle is saved).
 * The check button posts it once, through [ARG_REQUEST_KEY], to whoever
 * re-registered a listener. A restored pad has an empty digit buffer;
 * the user types again. [setOnPinEntered] remains only for a caller that
 * still uses the message-only constructor — none of the #220 flows do.
 * FLAG_SECURE is already set on the host activity's window, so it
 * survives recreation unchanged.
 */
class LockScreenDialog() : DialogFragment() {

    private val lockScreenMessage: String
        get() = arguments?.getString(ARG_MESSAGE) ?: ""

    private val requestKey: String
        get() = arguments?.getString(ARG_REQUEST_KEY) ?: ""

    /** #220: set / remove / confirm / unlock / import. Not a secret. */
    val purpose: String
        get() = arguments?.getString(ARG_PURPOSE) ?: ""

    /** #210 bridge for the pre-#210 `LockScreenDialog(lockScreenMessage)` shape. */
    constructor(lockScreenMessage: String) : this() {
        arguments = (arguments ?: Bundle()).apply { putString(ARG_MESSAGE, lockScreenMessage) }
    }

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var userInput = StringBuilder()
    private var strPassphrase = ""
    private var onConfirm: ((String) -> Unit)? = null
    private lateinit var touchInterceptor: View

    private var _binding: FragmentLockScreenBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentLockScreenBinding.inflate(inflater, container, false)
        val view = binding.root
        touchInterceptor = view.findViewById(R.id.touch_interceptor)
        touchInterceptor.setOnTouchListener { _, _ ->
            // Consume all touch events on the touch interceptor
            true
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding.pinEntryView.setConfirmClickListener {
            val pin = userInput.toString()
            val key = requestKey
            if (key.isNotEmpty()) {
                parentFragmentManager.setFragmentResult(
                    key,
                    Bundle().apply { putString(RESULT_PIN, pin) }
                )
            } else {
                onConfirm?.invoke(pin)
            }
        }
        binding.lockScreenText.text = lockScreenMessage
        prefsUtil.scramblePin?.let { binding.pinEntryView.setScramble(it) }
        prefsUtil.haptics?.let { binding.pinEntryView.isHapticFeedbackEnabled = it }
        binding.pinEntryView.setEntryListener { key, _ ->
            if (userInput.length <= AccessFactory.MAX_PIN_LENGTH - 1) {
                hapticFeedBack()
                userInput = userInput.append(key)
                if (userInput.length >= AccessFactory.MIN_PIN_LENGTH) {
                    binding.pinEntryView.showCheckButton()
                } else {
                    binding.pinEntryView.hideCheckButton()
                }
                setPinMaskView()
            }
        }
        binding.pinEntryView.setClearListener { clearType ->
            if (clearType === PinEntryView.KeyClearTypes.CLEAR) {
                if (userInput.isNotEmpty()) userInput = java.lang.StringBuilder(userInput.substring(0, userInput.length - 1))
                if (userInput.length >= AccessFactory.MIN_PIN_LENGTH) {
                    binding.pinEntryView.showCheckButton()
                } else {
                    binding.pinEntryView.hideCheckButton()
                }
            } else {
                strPassphrase = ""
                userInput = java.lang.StringBuilder()
                binding.pinEntryMaskLayout.removeAllViews()
                binding.pinEntryView.hideCheckButton()
            }
            setPinMaskView()
        }
    }

    fun setOnPinEntered(callback: ((String) -> Unit)) {
        this.onConfirm = callback
    }

    private fun hapticFeedBack() {
        if(binding.pinEntryView.isHapticFeedbackEnabled) {
            val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(44, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(44)
            }
        }
    }

    private fun setPinMaskView() {
        if (userInput.length > binding.pinEntryMaskLayout.childCount && userInput.isNotEmpty()) {
            val image = ImageView(requireContext())
            image.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.circle_dot_white))
            image.drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.ADD)
            val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(8, 0, 8, 0)
            TransitionManager.beginDelayedTransition(binding.pinEntryMaskLayout, ChangeBounds().setDuration(50))
            binding.pinEntryMaskLayout.addView(image, params)
        } else {
            if (binding.pinEntryMaskLayout.childCount != 0) {
                TransitionManager.beginDelayedTransition(binding.pinEntryMaskLayout, ChangeBounds().setDuration(200))
                binding.pinEntryMaskLayout.removeViewAt(binding.pinEntryMaskLayout.childCount - 1)
            }
        }

    }

    fun showError() {
        binding.pinEntryMaskLayout.removeAllViews()
        binding.pinEntryView.removeUserInput()
        userInput.delete(0, userInput.length)
        binding.pinEntryView.hideCheckButton()
        val errorShake = TranslateAnimation(0F, 12F, 0F, 0F)
        errorShake.duration = 420
        errorShake.interpolator = CycleInterpolator(4F)
        binding.pinEntryMaskLayout.startAnimation(errorShake)
    }

    companion object {
        const val ARG_MESSAGE = "lock_screen_dialog_arg_message"
        const val ARG_REQUEST_KEY = "lock_screen_dialog_arg_request_key"
        const val ARG_PURPOSE = "lock_screen_dialog_arg_purpose"
        const val RESULT_PIN = "lock_screen_dialog_result_pin"

        const val PURPOSE_UNLOCK = "unlock"
        const val PURPOSE_SET = "set"
        const val PURPOSE_REMOVE = "remove"
        const val PURPOSE_CONFIRM = "confirm"
        const val PURPOSE_IMPORT = "import"

        fun newInstance(message: String, requestKey: String, purpose: String): LockScreenDialog =
            LockScreenDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE, message)
                    putString(ARG_REQUEST_KEY, requestKey)
                    putString(ARG_PURPOSE, purpose)
                }
            }
    }
}
