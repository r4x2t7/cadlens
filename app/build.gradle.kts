plugins {
    id("com.android.application")
}

android {
    namespace = "dev.cadlens"
    compileSdk = 36

    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.cadlens"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        // 发布只带两种 ARM ABI；x86_64 只在 debug 里加，给模拟器用
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名，保证从 Releases 下下来能直接装；上架再换正式证书
            signingConfig = signingConfigs.getByName("debug")
            externalNativeBuild {
                cmake {
                    // 发布包要 -O2 + 打包时 strip，否则 so 会带一堆符号
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
        debug {
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
