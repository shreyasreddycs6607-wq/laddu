# Downloads the two on-device AI models Laddu uses into app/src/main/assets/models/.
#   dog_detector.tflite : EfficientDet-Lite0 (COCO, includes class "dog"), TFLite Task metadata
#   bark_classifier.tflite : YAMNet (AudioSet, includes Bark / Howl / Whimper (dog) / Speech)
# Any TFLite model with Task-library metadata can replace these - see docs/AI_MODEL_SETUP.md.
$ErrorActionPreference = "Stop"
$dest = Join-Path $PSScriptRoot "..\app\src\main\assets\models"
New-Item -ItemType Directory -Force $dest | Out-Null

$models = @{
  "dog_detector.tflite"    = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite"
  "bark_classifier.tflite" = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
}
foreach ($name in $models.Keys) {
  $out = Join-Path $dest $name
  Write-Host "Downloading $name ..."
  Invoke-WebRequest $models[$name] -OutFile $out
  Write-Host ("  {0:N1} MB" -f ((Get-Item $out).Length / 1MB))
}
Write-Host "Done. Models are git-ignored; rebuild the app to bundle them."
