# Downloads the two on-device AI models Laddu uses into app/src/main/assets/models/.
#   dog_detector.tflite : SSD MobileNet v1 (COCO, includes class "dog", 4-output Task metadata), TFLite Task metadata
#   bark_classifier.tflite : YAMNet (AudioSet, includes Bark / Howl / Whimper (dog) / Speech)
# Any TFLite model with Task-library metadata can replace these - see docs/AI_MODEL_SETUP.md.
$ErrorActionPreference = "Stop"
$dest = Join-Path $PSScriptRoot "..\app\src\main\assets\models"
New-Item -ItemType Directory -Force $dest | Out-Null

$models = @{
  "dog_detector.tflite"    = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_ssd_mobilenet_v1_1_metadata_2.tflite"
  "bark_classifier.tflite" = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
}
foreach ($name in $models.Keys) {
  $out = Join-Path $dest $name
  Write-Host "Downloading $name ..."
  Invoke-WebRequest $models[$name] -OutFile $out
  Write-Host ("  {0:N1} MB" -f ((Get-Item $out).Length / 1MB))
}
Write-Host "Done. Models are git-ignored; rebuild the app to bundle them."
