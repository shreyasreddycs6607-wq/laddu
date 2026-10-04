# Release build (APK / AAB)

## 1. Prerequisites
`google-services.json` in `app/`, models downloaded ([AI_MODEL_SETUP.md](AI_MODEL_SETUP.md)), Firebase deployed ([FIREBASE_SETUP.md](FIREBASE_SETUP.md)). Add your release key's **SHA-1 / SHA-256** to the Firebase Android app.

## 2. Signing key (once)
```bash
keytool -genkeypair -v -keystore laddu-release.jks -alias laddu -keyalg RSA -keysize 4096 -validity 10000
```
Create `keystore.properties` in the repo root (**git-ignored**, never commit):
```
storeFile=laddu-release.jks
storePassword=****
keyAlias=laddu
keyPassword=****
```
`app/build.gradle.kts` reads it automatically; without it the release build is unsigned.

## 3. Build
```bash
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk (sideload)
./gradlew :app:bundleRelease        # app/build/outputs/bundle/release/app-release.aab (Play Store)
```
R8 minification + resource shrinking are on (`proguard-rules.pro` keeps WebRTC, TFLite and model classes). Test the **release** build on a device — WebRTC / TFLite are the usual casualties of shrinking.

## 4. Before publishing
* Bump `versionCode`/`versionName` in `app/build.gradle.kts`.
* Play Console: declare foreground-service types **camera** and **microphone**, the *ignore battery optimisation* prompt (used only as a user-confirmed request), data-safety form (account email, event metadata, optional clips), and a privacy policy.
* Run the [security checklist](SECURITY_CHECKLIST.md).

## Windows note (Gradle daemon)
If Gradle reports `Unable to establish loopback connection` on Windows 11 with certain JDKs, run Gradle in-process:
```powershell
$env:GRADLE_OPTS="-Xmx3g -Dfile.encoding=UTF-8"
gradle --no-daemon "-Dorg.gradle.jvmargs=-Xmx3g -Xms64m -Dfile.encoding=UTF-8" :app:assembleDebug
```
(`gradle.properties` already sets `kotlin.compiler.execution.strategy=in-process`.) Android Studio's bundled JBR does not have this problem.
