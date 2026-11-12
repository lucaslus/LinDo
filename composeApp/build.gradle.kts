import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            implementation(libs.jsoup)
            implementation(libs.commonmark)
            implementation(libs.commonmark.tables)
            implementation(libs.commonmark.strikethrough)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.webkit)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.coil.gif)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    sourceSets.getByName("test").resources.srcDir("src/commonTest/resources")
    namespace = "do_.linux.lindo"
    compileSdk = 36
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "do_.linux.lindo"
        minSdk = 24
        targetSdk = 36
        versionCode = 31
        versionName = "0.2.29"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // 复用 debug 签名以便直接覆盖升级现有安装，同时获得 Release 模式下的 ART AOT 编译与 Compose 高帧率跳过优化（消除 Debug 模式 3~5 倍的列表重组开销）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)
}

compose.desktop {
    application {
        mainClass = "do_.linux.lindo.MainKt"
        jvmArgs(
            "-XX:+UseZGC",
            "-Dskiko.vsync.enabled=true",
            "-Dsun.java2d.metal=true",
            "-Dapple.awt.application.name=Lindo"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Lindo"
            packageVersion = "1.0.29"
            description = "Lindo — High-Performance Native Linux.do Client for Android, macOS, and Windows"

            macOS {
                bundleID = "do.linux.lindo"
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Lindo Auth Redirect</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>discourse</string>
                                    <string>lindo</string>
                                </array>
                            </dict>
                        </array>
                        <key>CADisableMinimumFrameDurationOnPhone</key>
                        <true/>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.matching { it.name == "packageDmg" }.configureEach {
    dependsOn("createDistributable")
    doLast {
        val uvBin = listOf(
            File(System.getProperty("user.home"), ".local/bin/uv").absolutePath,
            "/opt/homebrew/bin/uv",
            "/usr/local/bin/uv"
        ).firstOrNull { File(it).canExecute() } ?: "uv"
        val scriptFile = rootProject.file("scripts/build_macos_dmg.py")
        val pkgVer = compose.desktop.application.nativeDistributions.packageVersion ?: "1.0.29"
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/Lindo.app").get().asFile
        val dmgOut = layout.buildDirectory.file("compose/binaries/main/dmg/Lindo-$pkgVer.dmg").get().asFile
        val iconFile = project.file("src/desktopMain/resources/icon.icns")
        val markFile = rootProject.file("design/brand-v3/sources/mark-transparent.png")
        if (scriptFile.isFile && appDir.isDirectory) {
            val proc = ProcessBuilder(
                uvBin, "run", "--with", "dmgbuild,pillow",
                "python3", scriptFile.absolutePath,
                "--app", appDir.absolutePath,
                "--icon", iconFile.absolutePath,
                "--mark", markFile.absolutePath,
                "--out", dmgOut.absolutePath
            ).inheritIO().start()
            val code = proc.waitFor()
            if (code != 0) {
                throw GradleException("Best-practice macOS DMG packaging failed with exit code $code")
            }
        }
    }
}
