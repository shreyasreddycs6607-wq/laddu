# Optional cloud AI (second opinion)

Local detection always runs first and raises its own alert **without waiting** for the cloud. Cloud analysis only adds
context to a hazard event afterwards. It is **off by default** (Settings → Pet safety → "Cloud second opinion").

**Verification status:** the app side (request building, strict response validation, rate limits, timeouts, merge into the
event) compiles and its response parser is unit-tested. The Cloud Function `analyzeHazardEvidence` is written and
syntax-checked but has **not been deployed or run against a real analyzer**, because no provider credentials exist yet.

## How data flows

```
camera phone ──(one ≤640 px JPEG + short text, only for a notifying hazard event)──▶ Firebase function analyzeHazardEvidence
                                                                                       │ checks you own the camera, size/type, 30/hour limit
                                                                                       ▼
                                                                          YOUR analyzer endpoint (ANALYZER_URL, key in ANALYZER_KEY)
```

* No provider key is ever in the app. The function holds it as a Firebase secret.
* Sent: one reduced snapshot and the local detector's one-sentence description. **Not sent:** video, audio, other events.
* Limits: opt-in switch, online only, one request per incident, 12 per hour on the phone, 30 per hour per user on the server,
  25 s timeout, one bounded retry. A failure only adds `cloud_status = unavailable` to the event.
* Results are stored under `cloud_*` keys and shown as "an interpretation, not a fact".

## Analyzer contract (you provide the endpoint)

The function POSTs JSON `{ "schema": "laddu-hazard-v1", "eventId": "...", "image": "<base64 JPEG>", "localSummary": "..." }`
with `Authorization: Bearer <ANALYZER_KEY>`, and expects JSON back:

```json
{
  "eventId": "<same id>",
  "objects": ["plastic bottle", "dog"],
  "dogActivity": "sniffing the floor",
  "suspectedInteraction": "dog investigating a bottle",
  "evidence": "The dog's nose is close to a clear bottle.",
  "hazardCategory": "PLASTIC",
  "confidence": 0.6,
  "recommendedAction": "Keep watching.",
  "modelId": "your-model-name"
}
```

`hazardCategory` is one of PLASTIC, PACKAGING, RUBBISH, TEXTILE, SMALL_OBJECT, SHARP, BATTERY, TOXIC_FOOD, SPOILED_FOOD, FAECES,
FOOD, TOY, CONTAINER, UNKNOWN, NONE. The app rejects any response that is for another event, has a missing or oversized field,
or a confidence outside 0..1.

Any vision-language service can sit behind that endpoint (a small server of your own that calls the provider you choose).
Choose the provider, check its terms and cost, and never put its key in the app.

## Setup

```bash
firebase functions:secrets:set ANALYZER_KEY      # also needed (placeholder is fine) before any functions deploy
firebase functions:secrets:set TURN_SECRET
# set ANALYZER_URL as a function parameter when deploying: firebase deploy --only functions
```

The Blaze (pay-as-you-go) plan is required for Cloud Functions. Then enable the switch on the camera phone.
Without a configured endpoint the function answers "not configured" and the app simply keeps its local result.
