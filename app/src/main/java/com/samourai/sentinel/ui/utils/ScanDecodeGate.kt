package com.samourai.sentinel.ui.utils

/**
 * #232: the shared invalid-payload policy for every QR scan surface -
 * the New-button scanner (ScanPubKeyFragment), the Dojo pairing scanner
 * (DojoConfigureBottomSheet.ScanFragment), and through the first, every
 * consumer of a scan sheet (ImportLabelsScanBottomSheet).
 *
 * The QR decode callback re-fires for as long as the same QR sits in
 * frame, and the library's setScanResult can invoke it twice per decoded
 * frame - so an invalid QR, once decoded, must not re-enter validation
 * on every fire: the surfaces that stopped the scanner on decode turned
 * that refire into a frozen preview under a live sheet, or into a tight
 * stop/decode/reset toast loop (#232).
 *
 * Pure on purpose: no clock reads, no Android imports - the callers
 * supply the timestamps they track, so the window logic is testable
 * without any instrumentation (same shape as #233's
 * pairingTorDecision).
 */
const val SCAN_REDECODE_WINDOW_MS = 2_000L

/**
 * #232: may this decode pass the gate? False only for a REPEAT of the
 * last ADMITTED payload inside [windowMs] - the same payload still in
 * frame, or the library's same-frame double-fire. A DIFFERENT payload,
 * or the same one once the window has elapsed, is a fresh decode: it
 * passes and re-arms the window (the caller records it as the new last
 * payload + timestamp). Valid decodes pass through the same gate, which
 * also kills the pre-existing double-consume race where a second fire of
 * the same payload reached the sheet while the first was still
 * advancing it.
 */
fun shouldAdmitScan(
    lastPayload: String?,
    lastAdmittedAtMs: Long,
    payload: String,
    nowMs: Long,
    windowMs: Long = SCAN_REDECODE_WINDOW_MS,
): Boolean =
    payload != lastPayload || nowMs - lastAdmittedAtMs >= windowMs
