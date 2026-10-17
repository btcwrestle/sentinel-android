package com.samourai.sentinel.data.entropy

/**
 * Kill switch for the per-tx Boltzmann entropy feature.
 *
 * ON again - the #107 re-enable flip. The #105 premise ("5x5-class
 * remix rounds row as tooComplex") was census-falsified: none of
 * the 47 refused txs is a 5x5 (28x 8x8, 10x 6x6, 9x 7x7 - every
 * row a wide round), and the wallet's real 5x5 remixes are the 35
 * rows at nbCmbn=1496 / 10.5469 bits computed on-device Oct 4. The
 * re-enable bar - a real on-device remix rendering 1496 / ~10.55
 * bits - is met by banked evidence.
 *
 * What stays refused is the wide-round class, and the arm split is
 * stated honestly: 28 capRefused (8 inputs > SURGE_CAP=7, refused
 * pre-engine, by design); 19 timebox-or-engineFailed (6x6/7x7 pass
 * the cap and die on the 500ms caller box - the landing's
 * five-counter line was wiped, so the split between those two arms
 * is unverifiable; both are contained, both row tooComplex).
 * Declined rows carry the tier-1 external analysis link (#108):
 * refusal plus a pointer, never a derived number. The engine's
 * duration bailout is guarded (#109) - a trip rows tooComplex
 * instead of crashing.
 *
 * While ON: the ingest computes on full txs during sync (bounded
 * by the surge cap and the time box), rows cache permanently, and
 * the sheet renders engine truth - honest numbers, or the
 * declined-row link. Secure mode (street mode) still hides the
 * row. The gate sits on the two production entries
 * (TransactionsRepository's ingest wiring,
 * TransactionsDetailsBottomSheet's row block).
 */
const val ENTROPY_FEATURE_ENABLED = true

/**
 * Tier-1 external analysis link (#107): destination for entropy
 * rows the device declines. The txid rides in the hash fragment
 * (#?tx=...), which is never sent to a server; am-i.exposed is a
 * static site with no backend. The privacy cost is the page's own
 * mempool.space fetch - that server sees the txid and the timing
 * (IP protected by Tor while the app's webview proxy is active).
 * The advisory dialog states this before the link opens.
 *
 * #116: the link is a cross-check affordance on EVERY entropy
 * row (declined or honest) - am-i.exposed vendors boltzmann-rs,
 * which states the same oracle counts this engine produces
 * (1496 / 426,833 / 9,934,563 / 277,006,192); agreement
 * verifies, disagreement is a finding. For honest rows this is
 * an optional third-party query about a tx already analyzed
 * locally - hence the advisory on every open, not only refusals.
 */
const val EXTERNAL_ANALYSIS_URL_PREFIX = "https://am-i.exposed/#?tx="
