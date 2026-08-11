import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "com.cuscus.wifiscreenstreaming"
version = "0.1.0"

val appVersion = "1.0.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val isWindows = osName.contains("win")
val isMac = osName.contains("mac")
val isLinux = !isWindows && !isMac

val nativeOsDir = when {
    isWindows -> "windows"
    isMac -> "macos"
    else -> "linux"
}
val nativeArchDir = when {
    osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
    else -> "x86_64"
}

val javacvVersion = "1.5.10"

val targetPlatform = when {
    isWindows -> if (osArch.contains("64")) "windows-x86_64" else "windows-x86"
    isMac -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "macosx-arm64" else "macosx-x86_64"
    else -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "linux-arm64" else "linux-x86_64"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.bytedeco:javacv:$javacvVersion")
    implementation("org.bytedeco:ffmpeg:$javacvVersion:$targetPlatform")
    implementation("org.bytedeco:javacpp:$javacvVersion:$targetPlatform")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-nop:2.0.9")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        val baseArgs = mutableListOf("-Djava.net.preferIPv6Addresses=false")
        if (isLinux) baseArgs.add("-Dskiko.renderApi=SOFTWARE")
        jvmArgs += baseArgs

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)

            packageName = "WiFi Screen Streaming"
            packageVersion = appVersion

            modules(
                "java.desktop",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.unsupported"
            )

            windows {
                iconFile.set(project.file("src/main/resources/app_icon.ico"))
                shortcut = true
                menu = true
                upgradeUuid = "6f2a4d5e-9b41-4c8f-8e2d-1a7c3b9f5d20"
            }

            macOS {
                iconFile.set(project.file("src/main/resources/app_icon.icns"))
                bundleID = "com.cuscus.wifiscreenstreaming"
            }

            linux {
                iconFile.set(project.file("src/main/resources/app_icon.png"))
                packageName = "wifi-screen-streaming"
                appCategory = "AudioVideo"
            }
        }
    }
}

fun findCmake(): String {
    if (!isWindows) return "cmake"
    runCatching {
        val check = ProcessBuilder("cmake", "--version").redirectErrorStream(true).start()
        if (check.waitFor() == 0) return "cmake"
    }
    val candidates = mutableListOf<String>()
    val programFiles = listOf(
        System.getenv("ProgramFiles") ?: "C:\\Program Files",
        System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
    )
    for (pf in programFiles) {
        for (year in listOf("2022", "2019", "2017")) {
            for (edition in listOf("Community", "Professional", "Enterprise", "BuildTools")) {
                candidates += "$pf\\Microsoft Visual Studio\\$year\\$edition\\Common7\\IDE\\CommonExtensions\\Microsoft\\CMake\\CMake\\bin\\cmake.exe"
            }
        }
    }
    val localAppData = System.getenv("LOCALAPPDATA") ?: ""
    if (localAppData.isNotEmpty()) {
        candidates += "$localAppData\\Programs\\CLion\\bin\\cmake\\win\\x64\\bin\\cmake.exe"
    }
    candidates += "C:\\Program Files\\CMake\\bin\\cmake.exe"
    val userProfile = System.getenv("USERPROFILE") ?: ""
    if (userProfile.isNotEmpty()) candidates += "$userProfile\\scoop\\shims\\cmake.exe"
    candidates += "C:\\ProgramData\\chocolatey\\bin\\cmake.exe"

    for (path in candidates) if (file(path).exists()) return path

    throw GradleException(
        "cmake not found. Install it with 'winget install Kitware.CMake' " +
        "or turn the internal audio off with --audio-external."
    )
}

val nativeSrcDir = file("src/main/native")
val nativeBuildDir = file("${layout.buildDirectory.get()}/native-build")
val nativeOutputDir = file("${layout.buildDirectory.get()}/native-build/output")
val nativeResDir = file("src/main/resources/native/$nativeOsDir/$nativeArchDir")

val configureNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure CMake for the native audio engine"

    doFirst {
        nativeBuildDir.mkdirs()
        nativeResDir.mkdirs()
    }

    val javaHome = System.getProperty("java.home")?.replace("\\", "/")
        ?: throw GradleException("java.home not found")

    workingDir = nativeBuildDir

    val args = mutableListOf(
        findCmake(),
        nativeSrcDir.absolutePath,
        "-DJAVA_HOME=$javaHome",
        "-DCMAKE_BUILD_TYPE=Release"
    )
    args += if (isWindows) listOf("-G", "MinGW Makefiles") else listOf("-G", "Unix Makefiles")
    commandLine(args)
}

val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the native audio engine"
    dependsOn(configureNative)
    workingDir = nativeBuildDir
    commandLine(findCmake(), "--build", ".", "--config", "Release", "--parallel")
}

val copyNativeLib by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy the native library into the resources"
    dependsOn(buildNative)
    from(nativeOutputDir) {
        include("**/*.so", "**/*.dll", "**/*.dylib")
    }
    into(nativeResDir)
    eachFile { path = name }
    includeEmptyDirs = false
}

tasks.named("processResources") {
    dependsOn(copyNativeLib)
}

tasks.matching { it.name == "run" }.configureEach {
    if (this is JavaExec) standardInput = System.`in`
}

val checkCrypto by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Check the cryptographic layer of the input protocol"
    dependsOn("compileKotlin")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("WssCryptoCheckKt")
}

val checkHandshake by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Check the handshake, pairing and remembered devices"
    dependsOn("compileKotlin")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("WssHandshakeCheckKt")
}

tasks.named("check") {
    dependsOn(checkCrypto, checkHandshake)
}
