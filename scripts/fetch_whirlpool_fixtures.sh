#!/usr/bin/env bash
# Fetches raw mainnet tx JSON from mempool.space, merges with the
# manifest's classification metadata into the sample fixture JSONL.
# Tx JSON lands verbatim — never hand-edited; the manifest is the only
# knob. Privacy: clearnet by default; override e.g.
#   CURL="torsocks curl" MEMPOOL_BASE="http://<mempool-onion>" $0
set -euo pipefail
MANIFEST="${1:-scripts/whirlpool_manifest.jsonl}"
OUT="app/src/test/resources/whirlpool/sample.jsonl"
BASE="${MEMPOOL_BASE:-https://mempool.space}"
CURL="${CURL:-curl}"
command -v jq >/dev/null 2>&1 || { echo "jq required" >&2; exit 1; }
[ -s "$MANIFEST" ] || { echo "manifest missing: $MANIFEST" >&2; exit 1; }
: > "$OUT"
n=0
while IFS= read -r line; do
  [ -z "$line" ] && continue
  txid="$(jq -r '.txid' <<<"$line")"
  echo "fetching $txid ..." >&2
  $CURL -sf --retry 3 "$BASE/api/tx/$txid" -o "/tmp/whirl_$txid.json"
  jq -c --argjson meta "$line" \
     '. + {expect:$meta.expect, accounts:$meta.accounts}' \
     "/tmp/whirl_$txid.json" >> "$OUT"
  rm -f "/tmp/whirl_$txid.json"
  n=$((n+1))
done < "$MANIFEST"
echo "wrote $n fixture lines to $OUT" >&2
