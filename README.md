# SecNtfy-Android
<a href="https://android-arsenal.com/api?level=28" target="blank">
    <img src="https://img.shields.io/badge/API-28%2B-brightgreen.svg?style=flat" alt="SecNtfy Android Library least API level" />
</a>
<a href="https://jitpack.io/#secntfy/secntfy-android" target="blank">
    <img src="https://jitpack.io/v/SecNtfy/SecNtfy-Android.svg" alt="SecNtfy Android Library on jitpack.io" />
</a>
<a href="https://github.com/SecNtfy/SecNtfy-Android/blob/main/LICENSE" target="blank">
    <img src="https://img.shields.io/github/license/SecNtfy/SecNtfy-Android" alt="SecNtfy Android Library License." />
</a>
<a href="https://github.com/SecNtfy/SecNtfy-Android/stargazers" target="blank">
    <img src="https://img.shields.io/github/stars/SecNtfy/SecNtfy-Android" alt="SecNtfy Android Library Stars"/>
</a>
<a href="https://github.com/SecNtfy/SecNtfy-Android/fork" target="blank">
    <img src="https://img.shields.io/github/forks/SecNtfy/SecNtfy-Android" alt="SecNtfy Android Library Forks"/>
</a>
<a href="https://github.com/SecNtfy/SecNtfy-Android/issues" target="blank">
    <img src="https://img.shields.io/github/issues/SecNtfy/SecNtfy-Android" alt="SecNtfy Android Library Issues"/>
</a>
<a href="https://github.com/SecNtfy/SecNtfy-Android/commits?author=SecNtfy" target="blank">
    <img src="https://img.shields.io/github/last-commit/SecNtfy/SecNtfy-Android" alt="SecNtfy Android Library Issues"/>
</a>

## SecNtfy Android Library
An android library used to receive encrypted messages, over the service SecNtfy

### 1. Adding SecNtfy to your project

* Include jitpack in your root `settings.gradle.kts` file.

```gradle
pluginManagement {
    repositories {
        ...
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        ...
        maven {
            url = uri("https://jitpack.io")
        }
    }
}
```

* And add it's dependency to your app level `build.gradle.kts` file:

```gradle
dependencies {    
    implementation(libs.secNtfy.android) // or implementation 'com.github.SecNtfy:SecNtfy-Android:0.0.1'
}
```

#### Sync your project, and :scream: boom :fire: you have added SecNtfy successfully. :exclamation:
