// O plugin Android (AGP) precisa estar no mesmo classloader do plugin Kotlin;
// por isso entra aqui, e só quando há Android SDK (sem SDK, compila-se só o :core).
buildscript {
    val temSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").exists()
    if (temSdk) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

plugins {
    kotlin("jvm") version "2.0.21" apply false
}
