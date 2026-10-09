# Hazard detection (pet safety)

Laddu watches for a dog **interacting with something it should not**, and tells the owner immediately and honestly.
It reports what the camera appears to show, with an evidence score. **It cannot confirm that anything was swallowed,
cannot identify chemical composition, and does not replace watching your dog or veterinary advice.**

## Pipeline (all on the camera phone, no network needed)

```
camera frame ─ TFLite detector ─┬─ dogs   ─ DogTracker ───────┐
(existing FramePipeline)        └─ objects ─ ObjectTracker ───┤
                                                              ▼
                              InteractionAnalyzer  (dog ↔ object over several frames)
                                                              ▼
                        HazardEngine  (owner's SafetyPolicy → risk level → incident)
                                                              ▼
              EngineInput.Hazard → EventEngine → EventProcessor → Room → Firestore → push → viewer
```

* One inference pass returns dogs **and** objects (`DogDetectionEngine.detectScene`), so hazard detection adds no
  second model run.
* `core/safety/` contains the new logic; everything else reuses the existing event, sync, recording and push code.
* Alerts do **not** wait for any cloud service.

## What it distinguishes

| Observation | Meaning | Typical result |
|---|---|---|
| near / approaching | the object is close / the dog moves toward it | usually silent; caution only for restricted items at high sensitivity |
| sniffing-like | dog at the object ≥ 0.8 s, dog not travelling, object still | `POSSIBLE_HAZARD_INTERACTION` (caution) |
| pickup / carrying | object was still, then moves together with the dog | `HIGH_RISK_OBJECT_INTERACTION` if evidence is strong |
| chewing-like | ≥ 4 s at the dog with the object shifting against a stationary dog | `POSSIBLE_CHEWING` |
| vanished after contact | object disappears where the dog is, after ≥ 2 s of contact | `POSSIBLE_INGESTION` (worded as *may be hidden or swallowed*) |

Deliberate guards: a **walking** dog is never "sniffing/chewing"; a **stationary** object cannot be "chewed"; an object
that vanishes while the dog is out of view, or after only a brief touch, makes **no** claim; a reappearing object closes
the incident; `CRITICAL` needs a hazardous item, strong chew/carry evidence beforehand, *and* the high evidence bar.

## Risk and alerts

`INFO` (not alerted) · `CAUTION` · `HIGH` · `CRITICAL`. Thresholds relax with the **Hazard sensitivity** slider.
One continuing situation is **one incident**: it is updated in place, and only a real rise in risk creates a new,
notifying event (linked by `incidentId`, `escalation=true`). Each event carries `risk`, `object`, `interaction`,
`evidence`, `explanation`, `action`, and ready-made notification `title`/`body` text.

## Owner policy (Settings → Pet safety)

Each item is *Approved*, *Restricted* or *Hazardous*. Approved items (toys, a food bowl, foods you approve) never raise
a hazard; unapproved human food is **not** assumed safe. Items map to detector labels in
`DefaultSafety` (`core/safety/SafetyPolicy.kt`); the policy is stored in DataStore as JSON and applied live.

## Limits you must know about

* **The bundled model is COCO SSD MobileNet (80 classes).** It can see bottles, bags (often), knives/scissors/forks,
  remotes/phones, ties, bowls, cups, toys and some foods. It **cannot** see plastic wrappers, socks, rubbish, tissue,
  chocolate, grapes, onion or faeces. Those policy items exist so an open-vocabulary or custom-trained model can be
  dropped in; with the bundled model they never trigger. Settings marks them "needs a model that can recognise this".
* No object is ever reported as *unknown*: a class-agnostic detector would be needed. Unlisted labels are ignored.
* No pose model: "contact" means the object lies inside the dog's bounding box, **not** that it is in the mouth.
  A floor object behind a standing dog can look like contact; the walking/stationary guards reduce, not remove, this.
* Small/transparent items, dim light, occlusion and a camera far from the dog all reduce detection.

## What has and has not been tested

* **Unit-tested (synthetic sequences, no real dog):** 18 scenarios in `HazardEngineTest` (walking past, sniffing, pickup,
  chewing, approved food, restricted item, rubbish, dog away, brief touch, dog out of view, possible-ingestion wording
  and non-critical, single notification per incident, escalation, closing, disabled policy, unknown labels, JSON policy).
* **Not yet tested:** real dog footage, detector accuracy on these objects, false-alert rate per hour, end-to-end push
  (Cloud Functions are not deployed). Do not trust any accuracy number until measured on a labelled dataset.
* Test safely with **harmless stand-ins** (an empty plastic bottle, a clean cloth) under supervision; never expose a dog
  to a dangerous substance to test.
