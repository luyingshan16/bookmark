import com.android.build.api.variant.BuildConfigField
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.personal.bookmark_app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personal.bookmark_app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val apiBaseUrl = providers.gradleProperty("BOOKMARK_API_BASE_URL").get()
        val googleWebClientId = providers.gradleProperty("GOOGLE_WEB_CLIENT_ID")
            .orElse(providers.environmentVariable("GOOGLE_WEB_CLIENT_ID"))
            .orElse("").get()
        // Development default for Debug and Benchmark. Release overrides this field below.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val releaseUrl = providers.gradleProperty("BOOKMARK_RELEASE_API_BASE_URL")
            .orElse(providers.environmentVariable("BOOKMARK_RELEASE_API_BASE_URL"))
            .orElse("")
            .map { value ->
                require(value.isNotBlank()) {
                    "Release requires BOOKMARK_RELEASE_API_BASE_URL (stable HTTPS URL ending in /)."
                }
                val uri = runCatching { URI(value) }.getOrNull()
                val host = uri?.host?.lowercase().orEmpty()
                val labels = host.split('.')
                val dnsHost = labels.size >= 2 && host.length <= 253 &&
                    labels.last().firstOrNull()?.isLetter() == true &&
                    labels.all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }
                require(uri != null && uri.scheme.equals("https", ignoreCase = true) && dnsHost &&
                    host != "trycloudflare.com" && !host.endsWith(".trycloudflare.com") &&
                    !host.endsWith(".localhost") && !host.endsWith(".local") &&
                    (uri.port == -1 || uri.port in 1..65535) && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.endsWith('/')) {
                    "BOOKMARK_RELEASE_API_BASE_URL must use HTTPS with a stable DNS hostname and end in / (no credentials, query or fragment)."
                }
                BuildConfigField("String", "\"${uri.toASCIIString()}\"", "Release API base URL")
            }
        // Resolve only when Gradle needs the Release BuildConfig, keeping IDE sync and Debug usable.
        checkNotNull(variant.buildConfigFields) { "Release requires BuildConfig generation." }
            .put("API_BASE_URL", releaseUrl)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.paging.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    // 讓 Hilt 在編譯時能讀取此專案的 Kotlin 版本所產生的 metadata。
    ksp(libs.kotlin.metadata.jvm)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.landscapist.coil3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jsoup:jsoup:1.24.1")
    testImplementation(libs.junit)
}
