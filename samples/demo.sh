#!/usr/bin/env bash
# End-to-end demo against a running instance (default http://localhost:8080).
# Requires curl and jq. Creates accounts, makes transfers (including a replay, a conflict and an
# insufficient-funds rejection), then reconciles a statement generated with the current timestamp.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}/api/v1"
SUFFIX="$(date +%s)"          # unique account numbers so the demo can run repeatedly
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

account() { # account <number> <opening balance>
  curl -sf -X POST "$BASE/accounts" -H 'Content-Type: application/json' \
    -d "{\"accountNumber\":\"$1\",\"currency\":\"USD\",\"openingBalance\":\"$2\"}" | jq -r .accountId
}
transfer() { # transfer <key> <src> <dst> <amount> <reference>
  curl -s -i -X POST "$BASE/transfers" -H 'Content-Type: application/json' -H "Idempotency-Key: $1" \
    -d "{\"sourceAccountId\":\"$2\",\"destinationAccountId\":\"$3\",\"amount\":\"$4\",\"currency\":\"USD\",\"reference\":\"$5\"}"
  echo; echo
}

A1=$(account "ACC-001-$SUFFIX" 1000.00); A2=$(account "ACC-002-$SUFFIX" 0.00)
A3=$(account "ACC-003-$SUFFIX" 1000.00); A4=$(account "ACC-004-$SUFFIX" 0.00)

echo "== successful transfer (201)";                 transfer "demo-$SUFFIX-1" "$A1" "$A2" 150.00 "EXT-$SUFFIX-1"
echo "== same key, same body: replay (201, Idempotent-Replayed: true)"; transfer "demo-$SUFFIX-1" "$A1" "$A2" 150.00 "EXT-$SUFFIX-1"
echo "== same key, different body: 409";             transfer "demo-$SUFFIX-1" "$A1" "$A2" 151.00 "EXT-$SUFFIX-1"
echo "== insufficient funds: 422";                   transfer "demo-$SUFFIX-2" "$A2" "$A1" 9999.00 "EXT-$SUFFIX-X"
echo "== second transfer";                           transfer "demo-$SUFFIX-3" "$A3" "$A4" 75.00 "EXT-$SUFFIX-2"

echo "== balances"; curl -s "$BASE/accounts/$A1" | jq '{accountNumber, balance}'; curl -s "$BASE/accounts/$A2" | jq '{accountNumber, balance}'
echo "== ledger for source"; curl -s "$BASE/accounts/$A1/ledger" | jq '.content'
echo "== integrity audit";   curl -s "$BASE/ledger/integrity" | jq '{consistent, totalDebits, totalCredits}'

STATEMENT="$(mktemp)"
cat > "$STATEMENT" <<CSV
external_reference,source_account,destination_account,amount,currency,transaction_time
EXT-$SUFFIX-1,ACC-001-$SUFFIX,ACC-002-$SUFFIX,150.00,USD,$NOW
EXT-$SUFFIX-2,ACC-003-$SUFFIX,ACC-004-$SUFFIX,80.00,USD,$NOW
EXT-$SUFFIX-2,ACC-003-$SUFFIX,ACC-004-$SUFFIX,80.00,USD,$NOW
EXT-$SUFFIX-404,ACC-001-$SUFFIX,ACC-002-$SUFFIX,12.00,USD,$NOW
EXT-$SUFFIX-BAD,ACC-001-$SUFFIX,ACC-002-$SUFFIX,abc,USD,$NOW
CSV

echo "== reconciliation"
RUN=$(curl -s -X POST "$BASE/reconciliations" -F "file=@$STATEMENT;type=text/csv")
echo "$RUN" | jq .
RUN_ID=$(echo "$RUN" | jq -r .runId)
echo "== findings"; curl -s "$BASE/reconciliations/$RUN_ID/items" | jq '.content[] | {rowNumber, resultType, discrepancyCodes, externalReference}'
echo "== uploading the identical file again reuses the run (200, Reconciliation-Reused: true)"
curl -s -i -X POST "$BASE/reconciliations" -F "file=@$STATEMENT;type=text/csv" | grep -iE '^(HTTP|Reconciliation-Reused)'
rm -f "$STATEMENT"
