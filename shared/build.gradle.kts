plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    androidLibrary {
        namespace = "com.bitchat.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {
            isReturnDefaultValues = true
        }
    }

    // Apple targets only compile on macOS hosts; Gradle skips them elsewhere.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.cryptography.core)
            implementation(libs.secp256k1.kmp)
        }
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.secp256k1.kmp.jni.android)
            // The JDK provider derives X25519/Ed25519 public keys from private keys only via BouncyCastle,
            // and Android below API 33 has no JCA X25519/Ed25519 at all.
            implementation(libs.bouncycastle.bcprov)
        }
        iosMain.dependencies {
            implementation(libs.cryptography.provider.optimal)
        }
        getByName("androidHostTest").dependencies {
            // JNI library for the host JVM; the Android artifact only carries device ABIs.
            implementation(libs.secp256k1.kmp.jni.jvm)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
