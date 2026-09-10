import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.kotlinPluginSerialization)
    `maven-publish`
}

group = "com.github.SecNtfy"
version = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("VERSION"))
    .orElse("0.0.0-SNAPSHOT")
    .get()

android {
    namespace = "com.secntfy.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "SecNtfy-Android"
            version = project.version.toString()

            pom {
                name.set("SecNtfy Android")
                description.set("Android client for the SecNtfy encrypted notification service.")
                url.set("https://github.com/SecNtfy/SecNtfy-Android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/SecNtfy/SecNtfy-Android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/SecNtfy/SecNtfy-Android.git")
                    url.set("https://github.com/SecNtfy/SecNtfy-Android")
                }
            }

            // AGP creates the release component after project evaluation.
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
