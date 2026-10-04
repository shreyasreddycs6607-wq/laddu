# AI model setup

Laddu runs **two on-device TensorFlow Lite (LiteRT) models** through the TFLite *Task* library. They are **replaceable** and **not bundled in git** (binary assets). Without them the app works but reports *"Model missing"* — there are no fake detections.

| Purpose | File name | Default model | Task API |
|---|---|---|---|
| Dog detection | `dog_detector.tflite` | EfficientDet-Lite0 (COCO, includes class `dog`) | `ObjectDetector` |
| Bark / howl / whine / speech | `bark_classifier.tflite` | YAMNet (AudioSet 521 classes) | `AudioClassifier` |

## Install the defaults
```powershell
./scripts/download_models.ps1      # downloads both (~13 MB + ~4 MB) into app/src/main/assets/models/
./gradlew :app:assembleDebug       # rebuild so they are bundled (stored uncompressed)
```
Sources: Google MediaPipe model storage (`storage.googleapis.com/mediapipe-models/...`). Check each model's licence (Apache-2.0 at time of writing) before redistributing an APK.

## Replace a model without rebuilding
Copy a file with the same name to the app's private folder `files/models/` (e.g. with `adb`):
```bash
adb push my_detector.tflite /data/local/tmp/ && adb shell run-as com.laddu.app mkdir -p files/models && \
adb shell run-as com.laddu.app cp /data/local/tmp/my_detector.tflite files/models/dog_detector.tflite
```
A file in `files/models/` overrides the bundled one (`ModelLocator`). Restart monitoring.

## Requirements for custom models
* **Detector**: TFLite with *metadata* (Task Library format), label list containing **`dog`**. Input is resized by the library. Anything COCO-trained works (SSD MobileNet, EfficientDet-Lite0–2).
* **Audio**: TFLite audio classifier with metadata; labels are mapped by `soundClassOf()` (`Bark`, `Bow-wow`, `Yip`, `Dog` → bark; `Howl` → howl; `Whimper (dog)` → whine; `Speech`, … → human voice). To use a different label set, extend that function.

## Pipeline & performance
`frame → (motion check) → ≤320 px upright bitmap → inference → confidence ≥ threshold → IoU tracking → movement confirmation`.
* Performance modes (Settings → Camera → AI performance): **Low** 1 thread, ≥1.5 s between inferences · **Balanced** 2 threads, 0.7 s · **High** 4 threads, 0.3 s. Heat multiplies the interval ×2 (warm) / ×4 (hot).
* Sensitivity sliders map to the confidence thresholds (dog 0.7→0.3, bark 0.6→0.2).
* A bark counts only if the dog-sound score is ≥ threshold **and** ≥ the human-voice score (TV/speech does not trigger).

## Tuning notes
Detector jitter on a sleeping dog stays below the movement threshold (`BOX_MOTION_THRESHOLD = 0.03` of frame); if you get false movement or miss walking, adjust `FramePipeline.MOTION_THRESHOLD` / `BOX_MOTION_THRESHOLD`.
