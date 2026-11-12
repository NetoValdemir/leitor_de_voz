plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("net.dankito.readability4j:readability4j:1.0.8")
    // Mesma API do onnxruntime-android: o app usa a versão Android; os testes, a de PC
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.29.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")
    testImplementation("org.json:json:20240303")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
}

tasks.test {
    // Testes de fonemas: biblioteca JNI compilada para o PC + dados do espeak-ng
    systemProperty("espeak.jni", System.getProperty("espeak.jni") ?: "")
    systemProperty("espeak.dados", System.getProperty("espeak.dados") ?: "")
    systemProperty("kokoro.modelo", System.getProperty("kokoro.modelo") ?: "")
    systemProperty("kokoro.referencia", System.getProperty("kokoro.referencia") ?: "")
    systemProperty("assets", rootProject.file("app/src/main/assets").absolutePath)
    systemProperty("fixtures", rootProject.file("../testes").absolutePath)
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
