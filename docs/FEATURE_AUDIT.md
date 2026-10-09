# Laddu feature audit

Status key: **V** verified on real devices · **T** implemented and unit-tested only · **R** implemented, needs real-world testing · **P** partial · **M** missing · **B** blocked by credentials, billing or hardware.

"Real devices" = a Lenovo TB-X6C6X tablet, a Galaxy-class phone ("Oppo a22" in its settings) and an Oppo A53 (CPH2139) against Firebase project `laddu-8a636`. Nothing has been tested on a real Raspberry Pi or with a real dog. Evidence is given per line.

## A. Authentication and onboarding
| Feature | Status | Evidence / gap |
|---|---|---|
| Login, registration, session persistence | V | signed up and in on tablet and phone; session survived reinstall |
| Logout | R | implemented (stops camera service, waits for final heartbeat, clears token); not exercised on a device |
| Secure pairing (one-time QR token) | V | paired phone and tablet; one-time rule enforced in deployed Firestore rules (re-pair after rule change not re-tested) |
| Onboarding / permission explanations | P | welcome + mode screens, permission card on dashboard; no guided first-run camera-placement tutorial |
| Device removal / re-pairing | R | Manage camera implemented, not exercised on device |

## B. Live monitoring
| Feature | Status | Evidence / gap |
|---|---|---|
| Camera + viewer modes, WebRTC live video | V | ICE connected, video + audio tracks received (tablet to phone, A53 to viewer) |
| Live audio actually audible | R | audio track arrives; playback not confirmed by ear |
| Snapshot capture | V | "Snapshot saved" toast on the viewer |
| Full screen | R | implemented, fullscreen state fixes tested logically only |
| Auto reconnect, offline indicators | R | bounded reconnect state machine unit-tested; real network drop not tested |
| Quality settings | V | 240p/480p/720p chips work |
| Event-triggered recording | R | clip encoder fixes made; no real clip verified on a device in this pass |
| Manual recording | M | not implemented |
| Camera scheduling | M | not implemented |
| Multiple cameras | P | list + selector implemented; only one camera was tested |
| Multiple viewers | R | limit of 2 per camera implemented; not tested with two |
| Streaming diagnostics | P | logs only, no in-app diagnostics screen |
| Away-from-home streaming | B | needs a TURN server (not set up) |

## C. Dog detection and tracking
| Feature | Status | Evidence / gap |
|---|---|---|
| Dog detection model loads | V | "Dog AI: Ready" on the tablet (SSD MobileNet); newest LiteRT-based loader not yet seen on a device |
| Detection accuracy on a real dog | R | never measured |
| Tracking, occlusion hold, dedup | T | IoU tracker unit-tested |
| Movement events, debounce | T | event engine tests |
| Activity type (resting / walking / running-like) | T | new; thresholds are untuned guesses. "Playing" intentionally not offered |
| Multiple dogs | P | tracker keeps several tracks; events are per camera, not per dog |

## D. Barking and sound
| Feature | Status | Evidence / gap |
|---|---|---|
| Bark model loads | V | YAMNet via interpreter loaded on the tablet |
| Bark / repeated-bark / howl events | T | event-engine tests; not tested with real barking |
| Sensitivity, count/window settings | V | settings screen works |
| Whining events | P | classes mapped, no whine event type |
| Duration, summaries | T | durations stored; episodes summarised |
| Audio–visual correlation | M | not implemented |

## E. Behaviour monitoring
| Feature | Status | Evidence / gap |
|---|---|---|
| Per-dog baseline (needs 3 well-covered days) | T | insights tests |
| Prolonged inactivity, pacing, scratching, head-shaking | M | not implemented (needs pose estimation or far more data) |
| "Unusual behaviour" notifications | M | only in-app observations vs baseline (assistant), no push |

## F. Dog safety
| Feature | Status | Evidence / gap |
|---|---|---|
| Hazard pipeline: objects, tracking, proximity, temporal interaction, risk, incidents | T | 18 scenario tests, found and fixed a real walking-dog false positive |
| Detecting plastic wrappers, socks, rubbish, toxic food, faeces | M/B | the bundled COCO model cannot see them; needs a different model |
| Muzzle/head estimation | M | no pose model; contact means "inside the dog's box" |
| Unknown-object detection | M | needs a class-agnostic detector |
| Immediate local alerts, no cloud dependency | T | by design and tested; push delivery blocked (functions not deployed) |
| Evidence capture (snapshot/clip) for hazard events | R | wired through the clip recorder, not seen on a device |
| Real-footage accuracy, false alerts/hour | M | never measured |

## G. Safety policy
Approved/Restricted/Hazardous per item, sensitivity, persistent: **V** (settings UI used on device). Sync between devices: **P** (stored on the camera phone only). Custom user-defined items: **M** (list is fixed to the defaults, editable approval only).

## H. Notifications
| Feature | Status | Evidence / gap |
|---|---|---|
| Per-type switches, cooldown, duplicate suppression, escalation | T | unit-tested |
| Quiet hours (high-risk hazards bypass) | T | new, tested |
| Alert acknowledgement ("reviewed", per viewer device) | R | implemented, UI not exercised |
| Deep links to event / live | R | implemented and fixed; not re-tested |
| **Push delivery** | B | Cloud Functions need the Blaze plan and are not deployed; nothing sent end to end |
| Unusual-activity alerts | M | see E |

## I. Timeline and history
Day selector, 24 h timeline, filters (incl. Safety), thumbnails: **R** (built and installed; not reviewed on screen by the owner). Severity filter: **P** (Safety filter only). Evidence deletion by a viewer: **M** (rules allow owner delete; no UI). Retention: **V** for clips (existing settings); one incident = one event: **T**.

## J. Reports
Daily summary (respects offline gaps): **T**. Weekly: **P** (assistant answers "last 7 days"; no weekly report screen). Trends vs baseline: **T**.

## K. AI assistant
Event-grounded, rule-based, honest about missing data, links to events: **T** (15 tests). Cloud rephrasing: **M**.

## L. Devices
Pairing, naming, removal: **R**. Camera health (battery, temperature, thermal, AI ready): **V** on the dashboard and Home. Dedicated Devices screen: **P** (Manage camera only).

## M. Settings
Notifications, hazard, recording, network, theme: **V**. Dog profile: **M**. Account settings: **P** (logout, mode switch only). Storage management: **P** (usage + delete-all).

## N. Privacy and security
| Feature | Status | Evidence / gap |
|---|---|---|
| Firestore rules, per-user isolation, one-time tokens | V | deployed; compiled by the emulator service |
| Storage rules | B | Storage not enabled (needed only for cloud clips) |
| No secrets in the APK | V | checked; dev TURN credentials excluded from release builds |
| Cloud AI: key server-side, validated, rate-limited | T/B | code and parser tested; function not deployed, no analyzer |
| 16 KB page-size compatibility | R | all native libraries now aligned (checked in the APK); not re-run on the 16 KB phone |

## Raspberry Pi B+
Capture agent with reconnect, health and token auth: **T** (9 Python tests, stream smoke-tested on a PC). On real Pi / webcam: **B** (the Pi is not on a network). Android app consuming it: **M**.

## Build and test facts
Debug and minified release builds succeed. 130 JVM unit tests plus 9 Python tests pass. Instrumented UI tests compile but were not run on a device.
