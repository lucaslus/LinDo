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

// Keep dependency metadata intact and select the same patched artifact for
// Kotlin's JAR artifact views, tests and the native packager. Android never opts in.
if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
    val macInputPatched = Attribute.of("lindo.macInputPatched", Boolean::class.javaObjectType)
    dependencies.attributesSchema.attribute(macInputPatched)
    dependencies.artifactTypes.getByName("jar").attributes.attribute(macInputPatched, false)
    dependencies.registerTransform(do_.linux.lindo.buildlogic.MacComposeInputPatch::class) {
        from.attribute(macInputPatched, false)
        to.attribute(macInputPatched, true)
    }
    // Opt in once Kotlin has created the JVM compilation configurations.
    afterEvaluate {
        configurations.matching {
            it.name.startsWith("desktop") && it.name.endsWith("Classpath")
        }.configureEach {
            attributes.attribute(macInputPatched, true)
        }
    }
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
        val desktopTest by getting

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
            implementation("net.java.dev.jna:jna:5.17.0")
        }

        desktopTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
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
        versionCode = 38
        versionName = "0.2.32"
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
        // Native JNA entry points and the versioned Compose input bridge must retain their names.
        // The packaged release uses the same verified bytecode as desktop tests.
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            buildTypes.release.proguard.isEnabled.set(false)
        }
        jvmArgs += buildList {
            add("-XX:+IgnoreUnrecognizedVMOptions")
            add("-XX:+UseZGC")
            add("-XX:+ZGenerational")
            add("-Dskiko.vsync.enabled=true")
            add("-Dskiko.rendering.macos.waitForPreviousFrameVsyncOnRedrawImmediately=false")
            add("-Dskiko.rendering.windows.waitForVsyncOnRedrawImmediately=false")
            add("-Dskiko.gpu.resourceCacheLimit=268435456")
            add("-Dfile.encoding=UTF-8")
            if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
                add("-Dsun.java2d.metal=true")
                add("-Dapple.awt.application.name=Lindo")
                add("-Dapple.awt.enableTemplateImages=true")
            }
        }

        nativeDistributions {
            modules("java.net.http", "jdk.crypto.ec")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Lindo"
            packageVersion = "1.0.32"
            description = "Lindo — High-Performance Native Linux.do Client for Android, macOS, and Windows"
            vendor = "Lindo Community"

            macOS {
                packageBuildVersion = "1.0.38"
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
                        <key>CADisableMinimumFrameDuration</key>
                        <true/>
                        <key>CADisableMinimumFrameDurationOnPhone</key>
                        <true/>
                        <key>NSHighResolutionCapable</key>
                        <true/>
                    """.trimIndent()
                }
            }

            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                menuGroup = "Lindo"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "9a4e7c31-62b8-4d95-b7f2-8c1e3409a520"
            }

            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
                shortcut = true
                menuGroup = "Network"
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
