import java.util.Properties

plugins {
    id("com.android.application")
}

// Firma de release: se lee de keystore.properties (fuera de git). Si no existe, cae a la firma debug.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProps.getProperty(it).isNullOrBlank() } &&
    file(keystoreProps.getProperty("storeFile", "")).exists()

android {
    namespace = "cl.deco.shelf"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.deco.shelf"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.0"
        // Solo con -PlienzoCrashTrigger: gatillo de crash para probar el modo seguro. El release normal NO lo trae.
        val trigger = project.hasProperty("lienzoCrashTrigger")
        buildConfigField("boolean", "CRASH_TRIGGER", trigger.toString())
        buildConfigField("boolean", "STRICT", trigger.toString())
        // Solo con -PlienzoForceHealth: simula 8 días encendido para probar el aviso de Salud del deco. El release normal NO lo trae.
        buildConfigField("boolean", "FORCE_HEALTH", project.hasProperty("lienzoForceHealth").toString())
        // Solo con -PlienzoForceOffline: el home se comporta como sin red (sin apagar el WiFi del deco). El release normal NO lo trae.
        buildConfigField("boolean", "FORCE_OFFLINE", project.hasProperty("lienzoForceOffline").toString())
        // Solo con -PlienzoTvDump: vuelca Watch Next y canales preview al logcat (tag ShelfTvDump) para diagnosticar. El release normal NO lo trae.
        buildConfigField("boolean", "TV_DUMP", project.hasProperty("lienzoTvDump").toString())
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (hasReleaseKey) {
            create("lienzoRelease") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Firma propia si hay keystore.properties; si no, debug (flujo anterior)
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "lienzoRelease" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("es", "en")
    }

    // Recursos de tooling que no se leen en runtime (sin kotlin-reflect ni agente de depuración de coroutines): ~50 KB sin comprimir
    packaging {
        resources {
            excludes += listOf("kotlin/**", "META-INF/*.version", "DebugProbesKt.bin")
        }
    }
}

kotlin {
    // Sin jvmToolchain: compila con el JDK que corre Gradle (17 o 21, como el CI) y apunta a bytecode 17 igual que compileOptions
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json real para tests JVM
}
