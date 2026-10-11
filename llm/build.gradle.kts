plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// On-device LLM (llama.cpp) for organising notes and finding reminders. A separate module because
// llama.cpp and whisper.cpp each bundle their own copy of ggml and can't share one CMake build.
android {
    namespace = "com.flownotes.llm"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // Always optimise: an unoptimised llama.cpp is unusably slow, even in debug APKs.
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            }
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
    kotlinOptions {
        jvmTarget = "17"
    }
}
