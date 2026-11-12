plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "br.leitordevoz.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "br.leitordevoz.app"
        minSdk = 26
        targetSdk = 35
        // Número da execução do GitHub Actions: cada APK novo atualiza o anterior
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += listOf("arm64-v8a") }   // Galaxy A35 e celulares atuais
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                targets += listOf("espeak_jni")   // só a biblioteca do app (não o executável do espeak)
            }
        }
    }

    // Assinatura: a chave NUNCA fica no repositório. O GitHub Actions a fornece por
    // variáveis de ambiente (segredo do repositório ou cache). Sem elas (compilação
    // local), usa a chave de depuração padrão do Android.
    val chave = System.getenv("LEITOR_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (chave != null) create("estavel") {
            storeFile = chave
            storePassword = System.getenv("LEITOR_KEYSTORE_SENHA")
            storeType = "pkcs12"
            keyAlias = System.getenv("LEITOR_KEY_ALIAS") ?: "leitor"
            keyPassword = System.getenv("LEITOR_KEYSTORE_SENHA")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (chave != null) signingConfigs.getByName("estavel") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("f32") }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
}
