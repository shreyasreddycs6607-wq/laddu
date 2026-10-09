# Project status (resume point)

Last updated after the quiet-hours / activity-type pass. See [FEATURE_AUDIT.md](FEATURE_AUDIT.md) for per-feature evidence.

## Done
Core reliability pass (75 findings fixed), real-device pairing and live video, hazard detection engine, insights and
assistant, optional cloud layer (code only), Pi agent (code only), Teal & Coral UI, 16 KB native-library compatibility,
quiet hours, activity type.

## Next actions (highest value first)
1. **Real-device check of the newest build:** on the Oppo A53 start monitoring; confirm Dog AI "Ready" (the detector now uses LiteRT) and bark AI ready.
2. Test the hazard feature with a harmless stand-in object under supervision; measure latency and false alerts on real footage.
3. Deploy Cloud Functions (Blaze plan) so push alerts, offline alerts and the cloud second opinion can work end to end.
4. Pick a detector that can see plastic wrappers / socks / rubbish (open-vocabulary or custom-trained) and add its labels to the policy.
5. Missing features: manual recording, camera scheduling, dog profile, weekly report screen, dedicated Devices screen, evidence deletion UI, whining events, prolonged-inactivity and other behaviour observations.
6. Raspberry Pi: connect it to a network, run `pi-agent/`, then build the PC-side bridge that runs the hazard detector on its frames.

## Test results
`python -I` JUnit run: 130 tests OK. `pi-agent/test_agent.py`: 9 OK. Builds: debug + release OK.

## Open decisions for the owner
Cloud provider and key for the analyzer; whether to deploy functions (paid plan); which hazard model to adopt.
