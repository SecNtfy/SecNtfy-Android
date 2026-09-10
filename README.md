# SecNtfy-Android
<a href="https://android-arsenal.com/api?level=28" target="blank">
    <img src="https://img.shields.io/badge/API-28%2B-brightgreen.svg?style=flat" alt="SecNtfy Android Library least API level" />
</a>
<a href="https://jitpack.io/#SecNtfy/SecNtfy-Android" target="blank">
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

Android client for the SecNtfy encrypted notification service. Requires Android 9
(API 28) or newer.

### Add the library

Add JitPack to dependency repositories in your root `settings.gradle.kts`.
The content filter restricts JitPack to SecNtfy artifacts. No JitPack entry is
needed in `pluginManagement`.

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.SecNtfy")
            }
        }
    }
}
```

In your app's `build.gradle.kts`, replace `<version>` with the exact Git tag shown
on [JitPack](https://jitpack.io/#SecNtfy/SecNtfy-Android), including any `v` prefix:

```kotlin
dependencies {
    implementation("com.github.SecNtfy:SecNtfy-Android:<version>")
}
```

Alternatively, add this entry to `gradle/libs.versions.toml`:

```toml
[versions]
secntfy = "<version>"

[libraries]
secntfy-android = { module = "com.github.SecNtfy:SecNtfy-Android", version.ref = "secntfy" }
```

Then use `implementation(libs.secntfy.android)`. Pin a release tag for production;
branch snapshots change over time. The sample app in this repository uses
`implementation(project(":secntfy"))` to test local changes.

### Registration and delivery receipts

Initialize SecNtfy with the application context and your HTTPS API URL, then
configure your application key and current FCM token. Register again when the FCM
token changes. Do not embed real credentials or send test receipts on app launch.

```kotlin
val secntfy = SecNtfy.getInstance()
secntfy.initialize(apiUrl = apiUrl, ctx = context)
secntfy.configure(applicationKey)
secntfy.setFCMToken(fcmToken)
// Inside a coroutine owned by your application or worker:
val deviceToken = secntfy.registerDevice()
```

On receipt of an actual notification, pass its message ID unchanged to
`messageReceived`. Call this after successful receipt/processing according to
your app's delivery semantics, rather than on a user tap. Decryption and receipts
are separate operations; decrypting a message does not send an acknowledgement.

```kotlin
// Inside a suspend function or coroutine; messageId comes from the push payload.
try {
    when (secntfy.messageReceived(messageId)) {
        DeliveryReceipt.DELIVERED -> { /* First receipt accepted: API Status 200. */ }
        DeliveryReceipt.ALREADY_DELIVERED -> { /* Receipt already recorded: API Status 201. */ }
    }
} catch (error: NtfyException) {
    // Receipt is unconfirmed. Retain the ID and handle the error; do not mark success.
}
```

Both enum values mean the server acknowledged receipt. `ALREADY_DELIVERED` is a
successful duplicate acknowledgement. Neither value means the user read the
notification. The endpoint is `POST /Message/Receive/{messageId}` with the
`X-NTFYME-DEVICE-KEY` header. The request uses a snapshot of the server URL and
persisted device token, so a background service can call `initialize` and send
receipts without registering again on every push.

HTTP failures and JSON API failures are distinct (`NtfyException.HttpError` and
`ApiError`, each with `statusCode`). Empty/malformed responses, missing IDs or
tokens, network failures, and timeouts cannot count as success. Requests have
bounded timeouts and do not follow redirects with credentials. Coroutine
cancellation propagates normally.

This method does not persist an offline queue or guarantee delivery after process
termination. For reliable offline receipts, the consuming app must persist the
message ID and schedule network-constrained WorkManager work with backoff. Retry
connection failures, timeouts, HTTP 408/429 and temporary 5xx failures; handle
invalid IDs, configuration and authentication failures before retrying. If the
server accepted a receipt but its response was lost, a retry may return
`ALREADY_DELIVERED`. Do not launch receipt work in `GlobalScope` or forget it when
the FCM callback finishes.

The local backend's current receipt endpoint does not validate message ownership
or check the database update result before returning success. End-to-end assurance
requires server-side validation of the message/device association and successful
persistence; the client can only verify the server's acknowledgement.

#### Compatibility notes

Publish these changed failure semantics in a major release and update callers to
handle `NtfyException`. Existing method names remain available for migration.

- `getInstance()` now returns the same thread-safe instance. State is read as a
  consistent snapshot; stale registration responses cannot overwrite newer settings.
- `getNtfyToken(callback)` remains available and calls back once on the caller's
  coroutine context. Cancellation and exceptions thrown by the callback propagate.
- `MessageReceived` remains as a deprecated `Unit` wrapper. It now propagates
  failures; migrate to `messageReceived` to inspect the acknowledgement result.
- `DecryptMessage` remains as a deprecated wrapper for `decryptMessage`. Both now
  throw on failure, rather than returning an error string as decrypted content.
- `PostDevice` remains for compatibility; use `registerDevice` to update the
  persisted device token. Call `registerDevice` again after changing the FCM token.
- Tokens, keys and request bodies are no longer logged. The existing key storage
  and RSA/PKCS1 protocol remain compatible; this is not a cryptographic protocol
  or Android Keystore migration. Use a separate preferences group per identity.

### Build and verify locally

Use JDK 17, Android SDK Platform 35, and Android SDK Build Tools 35.0.0.
Configure the SDK through `ANDROID_HOME` or an untracked `local.properties` file.
The checked-in Gradle 8.13 wrapper matches Android Gradle Plugin 8.11.0.

```sh
./gradlew :secntfy:testReleaseUnitTest :secntfy:lintRelease :app:assembleDebug
./gradlew :secntfy:publishReleasePublicationToMavenLocal -PreleaseVersion=0.0.0-local
```

The second command installs the release AAR, sources JAR, Maven POM, and Gradle
Module Metadata under
`~/.m2/repository/com/github/SecNtfy/SecNtfy-Android/0.0.0-local/`.
To keep verification artifacts separate, append
`-Dmaven.repo.local=/absolute/path/to/temporary/repository`.
The POM and module metadata carry transitive dependencies for consumers.
Sources are published for IDE navigation; Kotlin API documentation is not
currently generated.

### Release through JitPack

1. Run the local checks above and commit the changes.
2. Create and push a new immutable semantic-version tag (for example `2.0.0`).
   Do not move or reuse an existing release tag.
3. Look up that tag on [JitPack](https://jitpack.io/#SecNtfy/SecNtfy-Android)
   and verify its build log before recommending the version to consumers.

`jitpack.yml` selects JDK 17 and publishes only the `:secntfy` release publication.
The sample app is not published. The existing consumer coordinate remains
`com.github.SecNtfy:SecNtfy-Android:<version>`.

Publication versions are resolved in this order:

- `-PreleaseVersion=...` for explicit local verification.
- JitPack's `VERSION` environment variable, which contains the requested tag or commit.
- `0.0.0-SNAPSHOT` for local builds without a release version.

No source edit is required to release a new tag.

Publishing follows the official [JitPack Android guide](https://docs.jitpack.io/android/)
and [Android library publication guide](https://developer.android.com/build/publish-library/upload-library).
