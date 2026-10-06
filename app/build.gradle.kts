import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firebase config is supplied by the developer (see docs/FIREBASE_SETUP.md); never committed.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun propsOf(name: String) = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

val localProps = propsOf("local.properties")
val keystoreProps = propsOf("keystore.properties")

android {
    namespace = "com.laddu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.laddu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional static TURN for development only. Production uses ephemeral credentials
        // issued by the `getTurnCredentials` Cloud Function (docs/TURN_SETUP.md).
        fun cfg(key: String) = "\"" + (localProps.getProperty(key) ?: "") + "\""
        // Empty by default (so release APKs never carry a static TURN secret); debug builds fill them in below.
        buildConfigField("String", "DEV_TURN_URL", "\"\"")
        buildConfigField("String", "DEV_TURN_USERNAME", "\"\"")
        buildConfigField("String", "DEV_TURN_CREDENTIAL", "\"\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            fun cfg(key: String) = "\"" + (localProps.getProperty(key) ?: "") + "\""
            buildConfigField("String", "DEV_TURN_URL", cfg("laddu.turn.url"))
            buildConfigField("String", "DEV_TURN_USERNAME", cfg("laddu.turn.username"))
            buildConfigField("String", "DEV_TURN_CREDENTIAL", cfg("laddu.turn.credential"))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }

    // Keep .tflite models uncompressed so they can be memory-mapped.
    androidResources { noCompress += "tflite" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.functions)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.guava)

    implementation(libs.tflite)
    implementation(libs.tflite.task.vision)

    implementation(libs.webrtc)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Windows loopback workaround helper: dump the unit-test classpath so JUnit can be run without forking (see docs/TESTING.md).
afterEvaluate {
    tasks.register("dumpUnitTestClasspath") {
        val t = tasks.named<Test>("testDebugUnitTest")
        inputs.files(t.get().classpath, t.get().testClassesDirs)
        doLast {
            val cp = (t.get().testClassesDirs.files + t.get().classpath.files).joinToString(File.pathSeparator)
            layout.buildDirectory.file("unit-test-classpath.txt").get().asFile.writeText(cp)
        }
    }
}
