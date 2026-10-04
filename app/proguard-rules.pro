# WebRTC native bindings
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# TensorFlow Lite / Task library
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.auto.value.**

# Firestore model classes use reflection
-keepclassmembers class com.laddu.app.core.model.** { *; }
