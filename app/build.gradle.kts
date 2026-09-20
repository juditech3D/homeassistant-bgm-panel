import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Identifiants de signature, tenus hors du fichier de build et hors du dÃ©pÃ´t.
// Absents, le build de release se fait sans signature et Gradle le signale.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.judit.hapanel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.judit.hapanel"
        minSdk = 24
        // Le panneau tourne sous Android 8.1 (API 27). Rester sur targetSdk 27
        // garde le HTTP en clair autorisÃ© par dÃ©faut, ce qui est indispensable
        // pour joindre Home Assistant en local sans TLS.
        targetSdk = 27
        versionCode = 27
        versionName = "1.17"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Pas d'obfuscation : l'application est petite, tient sur ce matÃ©riel
            // modeste, et R8 compliquerait la lecture des traces sans bÃ©nÃ©fice ici.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }

    lint {
        // Lint refuse un build de release dont le targetSdk est infÃ©rieur Ã  33, au nom
        // des exigences du Google Play Store. Cette application n'y sera jamais publiÃ©e :
        // elle est installÃ©e directement sur un panneau sous Android 8.1, et son
        // targetSdk 27 est un choix assumÃ© (voir plus haut). La rÃ¨gle ne s'applique pas.
        disable += "ExpiredTargetSdkVersion"
    }
}

// Les sources sont en UTF-8 : sans cela, javac utiliserait l'encodage par dÃ©faut de la
// plateforme (cp1252 sous Windows). Voir aussi kotlin.daemon.jvmargs dans
// gradle.properties, qui rÃ¨gle le mÃªme problÃ¨me cÃ´tÃ© Kotlin.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
