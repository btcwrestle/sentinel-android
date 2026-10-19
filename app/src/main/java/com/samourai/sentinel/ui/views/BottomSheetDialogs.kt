package com.samourai.sentinel.ui.views

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.home.HomeActivity
import java.util.UUID


/**
 * sentinel-android
 *
 * @author Sarath
 */

/**
 * Base BottomSheet class for the whole application
 * Custom themes with rounded corners are applied
 *
 * #210: no primary-constructor parameters anymore. Android rebuilds
 * fragments through their public no-arg constructor whenever the host
 * activity is recreated (dark-mode / font / locale change, multi-window
 * resize, or process death while the sheet is showing - all live on
 * master even though every activity is portrait-locked). Everything a
 * sheet needs now travels in `arguments`; answers travel back through
 * the Fragment Result API. The legacy `secure` constructor is kept as a
 * delegating secondary so subclasses that still pass it (QRBottomSheet,
 * TransactionsDetails, AddNewPubKey, WalletPairing, Sweep, DojoCredentials)
 * compile unchanged - it writes the flag into `arguments`, which is what
 * a restored instance reads, so FLAG_SECURE now SURVIVES recreation
 * instead of silently dropping with the constructor field.
 */
open class GenericBottomSheet() : BottomSheetDialogFragment() {

    /**
     * #210: the flag is read from `arguments`, NOT held as a base-class
     * property - subclasses (QRBottomSheetDialog, TransactionsDetails,
     * AddNewPubKey...) declare their own `secure` constructor properties,
     * and a member here collides with every one of them.
     */
    private fun isSecure(): Boolean = arguments?.getBoolean(ARG_SECURE, false) == true

    /** #210 bridge for the pre-#210 `GenericBottomSheet(secure)` shape. */
    constructor(secure: Boolean) : this() {
        arguments = (arguments ?: Bundle()).apply { putBoolean(ARG_SECURE, secure) }
    }

    override fun getTheme(): Int = R.style.AppTheme_BottomSheet_Theme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #210: cancelability is part of the showing contract, not of saved
        // fragment state - re-apply from arguments after every recreation so
        // a non-cancelable sheet (first-run network choice) stays that way.
        arguments?.let { isCancelable = it.getBoolean(ARG_CANCELABLE, true) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isSecure())
            dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(
            requireContext(),
            theme
        )
        if (isSecure())
            dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        return dialog
    }

    companion object {
        const val ARG_SECURE = "generic_bottom_sheet_arg_secure"
        const val ARG_CANCELABLE = "generic_bottom_sheet_arg_cancelable"
    }
}

/**
 * Shows BottomSheet with Input field
 * It will immediately focus input field for better UX
 *
 * #210: every presentation option travels in `arguments`, and the sheet
 * wires itself in onViewCreated (the old base-class onViewReady hook never
 * ran for this class - its override shadowed it without a super call).
 * The typed answer is posted to [ARG_REQUEST_KEY] on the parent
 * FragmentManager via the Fragment Result API, so a recreated sheet still
 * delivers it to whoever re-registers.
 *
 * The legacy (label, onViewReady) constructor is kept as a bridge for the
 * one remaining direct call site (TransactionsDetailsBottomSheet's label
 * editor); it stores the lambda for this instance's lifetime only.
 */
class InputBottomSheet() : GenericBottomSheet() {

    private val label: String
        get() = arguments?.getString(ARG_LABEL) ?: ""
    private val buttonLabel: String
        get() = arguments?.getString(ARG_BUTTON_LABEL) ?: "Confirm"
    private val prefill: String
        get() = arguments?.getString(ARG_VALUE) ?: ""
    private val hint: String
        get() = arguments?.getString(ARG_LABEL_EDIT_TEXT) ?: ""
    private val maskInput: Boolean
        get() = arguments?.getBoolean(ARG_MASK_INPUT, false) == true
    private val maxLen: Int
        get() = arguments?.getInt(ARG_MAX_LEN, 34) ?: 34
    private val editable: Boolean
        get() = arguments?.getBoolean(ARG_EDITABLE, true) == true
    private val requestKey: String
        get() = arguments?.getString(ARG_REQUEST_KEY) ?: ""

    /** #210 bridge: legacy shape for pre-#210 direct constructions. */
    private var legacyViewReady: ((GenericBottomSheet) -> Unit)? = null

    constructor(label: String, onViewReady: (GenericBottomSheet) -> Unit) : this() {
        arguments = (arguments ?: Bundle()).apply { putString(ARG_LABEL, label) }
        legacyViewReady = onViewReady
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_bottom_sheet, container)
        view.findViewById<TextView>(R.id.dialogTitle).text = label
        val inputContent = inflater.inflate(R.layout.content_bottom_sheet_input, null)
        val content = view.findViewById<FrameLayout>(R.id.contentContainer)
        content.addView(inputContent)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val textInputLayout = view.findViewById<TextInputLayout>(R.id.bottomSheetInputFieldLayout)
        val textInput = view.findViewById<TextInputEditText>(R.id.bottomSheetInputField)
        val positiveButton = view.findViewById<MaterialButton>(R.id.bottomSheetConfirmPositiveBtn)

        positiveButton?.text = buttonLabel
        textInputLayout?.hint = hint
        if (!editable) {
            textInput?.isFocusable = false
            textInput?.isFocusableInTouchMode = false
            positiveButton?.visibility = View.GONE
            textInputLayout?.setPadding(
                textInputLayout.paddingLeft,
                textInputLayout.paddingTop,
                textInputLayout.paddingRight,
                100
            )
        }
        textInput?.setText(prefill)
        if (maskInput) {
            textInput?.inputType = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
            textInput?.transformationMethod = PasswordTransformationMethod.getInstance()
        }
        textInput?.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
        positiveButton?.setOnClickListener { _ ->
            val text = textInput?.text.toString()
            parentFragmentManager.setFragmentResult(
                requestKey,
                Bundle().apply { putString(RESULT_INPUT, text) }
            )
            dismiss()
        }
        // #210 bridge: invoked after self-wiring so a legacy lambda can
        // still override any of the defaults above, exactly as before.
        legacyViewReady?.invoke(this)

        textInput?.requestFocus()
        val inputMethodManager = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    override fun onDismiss(dialog: DialogInterface) {
        val inputMethodManager = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0)
        super.onDismiss(dialog)
    }

    companion object {
        const val ARG_LABEL = "input_bottom_sheet_arg_label"
        const val ARG_BUTTON_LABEL = "input_bottom_sheet_arg_button_label"
        const val ARG_VALUE = "input_bottom_sheet_arg_value"
        const val ARG_LABEL_EDIT_TEXT = "input_bottom_sheet_arg_label_edit_text"
        const val ARG_MASK_INPUT = "input_bottom_sheet_arg_mask_input"
        const val ARG_MAX_LEN = "input_bottom_sheet_arg_max_len"
        const val ARG_EDITABLE = "input_bottom_sheet_arg_editable"
        const val ARG_REQUEST_KEY = "input_bottom_sheet_arg_request_key"
        const val RESULT_INPUT = "input_bottom_sheet_result_input"

        fun newInstance(
            label: String,
            buttonLabel: String = "Confirm",
            value: String = "",
            labelEditText: String = "",
            maskInput: Boolean = false,
            maxLen: Int = 34,
            editable: Boolean = true,
            isCancelable: Boolean = true,
            requestKey: String
        ): InputBottomSheet = InputBottomSheet().apply {
            // #210: cancelability travels as an argument; the base class
            // re-applies it in onCreate on every (re)creation.
            arguments = Bundle().apply {
                putString(ARG_LABEL, label)
                putString(ARG_BUTTON_LABEL, buttonLabel)
                putString(ARG_VALUE, value)
                putString(ARG_LABEL_EDIT_TEXT, labelEditText)
                putBoolean(ARG_MASK_INPUT, maskInput)
                putInt(ARG_MAX_LEN, maxLen)
                putBoolean(ARG_EDITABLE, editable)
                putString(ARG_REQUEST_KEY, requestKey)
                putBoolean(GenericBottomSheet.ARG_CANCELABLE, isCancelable)
            }
        }
    }
}

/**
 * Shows Confirmation style Bottomsheet
 *
 * #210: label / message / button texts travel in `arguments`; the yes/no
 * answer is posted to [ARG_REQUEST_KEY] on the parent FragmentManager via
 * the Fragment Result API, so a recreated sheet still delivers its answer
 * to whoever re-registers (see HomeActivity's first-run network choice
 * for the durable pattern).
 */
class ConfirmBottomSheet : GenericBottomSheet() {

    private val label: String
        get() = arguments?.getString(ARG_LABEL) ?: ""
    private val message: String
        get() = arguments?.getString(ARG_MESSAGE) ?: ""
    private val positiveText: String
        get() = arguments?.getString(ARG_POSITIVE_TEXT) ?: "Yes"
    private val negativeText: String
        get() = arguments?.getString(ARG_NEGATIVE_TEXT) ?: "No"
    private val requestKey: String
        get() = arguments?.getString(ARG_REQUEST_KEY) ?: ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_bottom_sheet, container)
        view.findViewById<TextView>(R.id.dialogTitle).text = label
        val inputContent = inflater.inflate(R.layout.content_bottom_sheet_confirm, null)
        val content = view.findViewById<FrameLayout>(R.id.contentContainer)
        content.addView(inputContent)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val positiveButton = view.findViewById<MaterialButton>(R.id.bottomSheetConfirmPositiveBtn);
        val negativeButton = view.findViewById<MaterialButton>(R.id.bottomSheetConfirmNegativeBtn);
        view?.findViewById<TextView>(R.id.bottomSheetConfirmMessage)?.text = message;

        positiveButton?.text = positiveText
        negativeButton?.text = negativeText

        negativeButton?.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                requestKey,
                Bundle().apply { putBoolean(RESULT_CONFIRMED, false) }
            )
            dismiss()
        }
        positiveButton?.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                requestKey,
                Bundle().apply { putBoolean(RESULT_CONFIRMED, true) }
            )
            dismiss()
        }
    }

    companion object {
        const val ARG_LABEL = "confirm_bottom_sheet_arg_label"
        const val ARG_MESSAGE = "confirm_bottom_sheet_arg_message"
        const val ARG_POSITIVE_TEXT = "confirm_bottom_sheet_arg_positive_text"
        const val ARG_NEGATIVE_TEXT = "confirm_bottom_sheet_arg_negative_text"
        const val ARG_REQUEST_KEY = "confirm_bottom_sheet_arg_request_key"
        const val RESULT_CONFIRMED = "confirm_bottom_sheet_result_confirmed"

        fun newInstance(
            label: String,
            message: String = "",
            positiveText: String = "Yes",
            negativeText: String = "No",
            isCancelable: Boolean = true,
            requestKey: String
        ): ConfirmBottomSheet = ConfirmBottomSheet().apply {
            // #210: cancelability travels as an argument; the base class
            // re-applies it in onCreate on every (re)creation.
            arguments = Bundle().apply {
                putString(ARG_LABEL, label)
                putString(ARG_MESSAGE, message)
                putString(ARG_POSITIVE_TEXT, positiveText)
                putString(ARG_NEGATIVE_TEXT, negativeText)
                putString(ARG_REQUEST_KEY, requestKey)
                putBoolean(GenericBottomSheet.ARG_CANCELABLE, isCancelable)
            }
        }
    }
}

/**
 * #210: label / txId travel in `arguments`. The legacy constructor is kept
 * as a bridge so BroadcastTx and the sweep preview (crypto-critical, this
 * PR deliberately does not touch that file) compile unchanged; it converts
 * to arguments, and the legacy lambda is stored for this instance's
 * lifetime only - both remaining call sites pass no-ops, so a restored
 * sheet simply doesn't invoke it. Follow-up migrates both to newInstance.
 */
class SuccessfulBottomSheet() : GenericBottomSheet() {

    private var legacyViewReady: ((GenericBottomSheet) -> Unit)? = null

    constructor(label: String, txId: String, onViewReady: (GenericBottomSheet) -> Unit) : this() {
        arguments = (arguments ?: Bundle()).apply {
            putString(ARG_LABEL, label)
            putString(ARG_TX_ID, txId)
        }
        legacyViewReady = onViewReady
    }

    private val label: String
        get() = arguments?.getString(ARG_LABEL) ?: ""
    private val txId: String
        get() = arguments?.getString(ARG_TX_ID) ?: ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_success_bottom, container)
        view.findViewById<TextView>(R.id.dialogTitle).text = label
        view.findViewById<TextView>(R.id.transactionID).text = txId
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        legacyViewReady?.invoke(this)
    }

    override fun onDismiss(dialog: DialogInterface) {
        val intent = Intent(context, HomeActivity::class.java)
        intent.putExtra("forceRefresh", true)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        super.onDismiss(dialog)
    }

    companion object {
        const val ARG_LABEL = "successful_bottom_sheet_arg_label"
        const val ARG_TX_ID = "successful_bottom_sheet_arg_tx_id"

        fun newInstance(label: String, txId: String): SuccessfulBottomSheet =
            SuccessfulBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_LABEL, label)
                    putString(ARG_TX_ID, txId)
                }
            }
    }
}

/**
 * Shows BottomSheet with Input field
 *
 * #210: the helpers keep their exact signatures (every existing call site
 * compiles unchanged), but internally each invocation now uses a unique
 * request key and a one-shot FragmentResult listener instead of a lambda
 * captured by the sheet instance. While the activity instance lives, the
 * behavior is identical to before; after a recreation the restored sheet
 * rebuilds from arguments, its buttons post a result, and it dismisses
 * cleanly (no crash, no phantom action). Flows whose answer is
 * state-derived can re-register a stable key in onCreate to become fully
 * recreation-proof - see HomeActivity's first-run network choice.
 */
fun AppCompatActivity.alertWithInput(
    label: String,
    buttonLabel: String = "Confirm",
    value: String = "",
    labelEditText: String = "",
    maskInput: Boolean = false,
    isCancelable: Boolean = true,
    maxLen: Int = 34,
    onConfirm: (String) -> Unit,
    isEditable: Boolean = true
): GenericBottomSheet {
    val requestKey = "input_${UUID.randomUUID()}"
    supportFragmentManager.setFragmentResultListener(requestKey, this) { key, bundle ->
        supportFragmentManager.clearFragmentResultListener(key)
        onConfirm(bundle.getString(InputBottomSheet.RESULT_INPUT) ?: "")
    }
    val bottomSheet = InputBottomSheet.newInstance(
        label = label,
        buttonLabel = buttonLabel,
        value = value,
        labelEditText = labelEditText,
        maskInput = maskInput,
        maxLen = maxLen,
        editable = isEditable,
        isCancelable = isCancelable,
        requestKey = requestKey
    )
    bottomSheet.isCancelable = isCancelable
    bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    return bottomSheet
}


fun AppCompatActivity.confirm(
    label: String = "",
    positiveText: String = "Yes",
    message: String = "",
    negativeText: String = "No",
    isCancelable: Boolean = true,
    onConfirm: (Boolean) -> Unit
): GenericBottomSheet {
    val requestKey = "confirm_${UUID.randomUUID()}"
    supportFragmentManager.setFragmentResultListener(requestKey, this) { key, bundle ->
        supportFragmentManager.clearFragmentResultListener(key)
        onConfirm(bundle.getBoolean(ConfirmBottomSheet.RESULT_CONFIRMED))
    }
    val bottomSheet = ConfirmBottomSheet.newInstance(
        label = label,
        message = message,
        positiveText = positiveText,
        negativeText = negativeText,
        isCancelable = isCancelable,
        requestKey = requestKey
    )
    bottomSheet.isCancelable = isCancelable
    bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    return bottomSheet
}
