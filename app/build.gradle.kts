import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.secrets)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.inputStream().use { load(it) }
    }
}

val storeSafeProp = (project.findProperty("STORE_SAFE_MODE")?.toString()
    ?: localProps.getProperty("STORE_SAFE_MODE")
    ?: "false").toBoolean()
val fullControlProp = !storeSafeProp

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.jarvis.vcaixq"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "FULL_CONTROL_MODE", fullControlProp.toString())
        buildConfigField("boolean", "STORE_SAFE_MODE", storeSafeProp.toString())
        buildConfigField("boolean", "AI_VOICE", "false")

        resValue("bool", "enable_accessibility_automation", fullControlProp.toString())
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("../debug.keystore")
            val base64File = file("../debug.keystore.base64")

            if (!keystoreFile.exists() && base64File.exists()) {
                val base64String = base64File.readText().replace("\\s".toRegex(), "")
                keystoreFile.writeBytes(Base64.getDecoder().decode(base64String))
            }

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        getByName("debug") {
            val keystoreFile = file("../debug.keystore")
            val base64File = file("../debug.keystore.base64")

            if (!keystoreFile.exists() && base64File.exists()) {
                val base64String = base64File.readText().replace("\\s".toRegex(), "")
                keystoreFile.writeBytes(Base64.getDecoder().decode(base64String))
            }

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
        debug {
            val debugSigning = signingConfigs.getByName("debug")
            if (debugSigning.storeFile?.exists() == true) {
                signingConfig = debugSigning
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
    ignoreList.add("keyToIgnore")
    ignoreList.add("sdk.*")
    ignoreList.add("STORE_SAFE_MODE")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Material Icons Extended & Navigation
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room Local Database + SQLCipher encryption at rest
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)

    // DataStore Preferences (settings, language, voice speed, safety level)
    implementation(libs.androidx.datastore.preferences)

    // Networking & JSON (OkHttp + Moshi)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.runner)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
