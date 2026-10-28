plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.xaxaxax.microglogin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xaxaxax.microglogin"
        // onPackageLoaded 需要 Android 10，導向必須在 AppComponentFactory 建立前啟動
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
}
