import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
}

val ffmpegArchive = configurations.create("ffmpegArchive") {
    isTransitive = false
    attributes {
        attribute(
            Usage.USAGE_ATTRIBUTE,
            objects.named(Usage.JAVA_RUNTIME),
        )
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named("aar"),
        )
    }
}
val ffmpegDirectory = layout.buildDirectory.dir("ffmpeg")
val prepareFfmpeg = tasks.register<Sync>("prepareFfmpeg") {
    from({ ffmpegArchive.map { zipTree(it) } }) {
        include(
            "jni/**/libavcodec.so",
            "jni/**/libavformat.so",
            "jni/**/libavutil.so",
            "jni/**/libswscale.so",
        )
    }
    into(ffmpegDirectory)
}

android {
    namespace = "com.nplayer.core.thumbnail"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DFFMPEG_DIRECTORY=${ffmpegDirectory.get().asFile.absolutePath}"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) {
        dependsOn(prepareFfmpeg)
    }
}

dependencies {
    add(
        ffmpegArchive.name,
        libs.github.anilbeesetti.nextlib.mediainfo,
    )
    implementation(libs.github.anilbeesetti.nextlib.mediainfo)
}
