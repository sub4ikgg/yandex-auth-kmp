# yandex-auth-kmp

[![Maven Central](https://img.shields.io/maven-central/v/tech.chatan/yandex-auth-kmp?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/tech.chatan/yandex-auth-kmp)
[![Swift Package Manager](https://img.shields.io/badge/SwiftPM-compatible-brightgreen)](https://swiftpackageindex.com/sub4ikgg/yandex-auth-kmp)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Platforms](https://img.shields.io/badge/Platforms-Android%2024%2B%20%7C%20iOS%2014%2B-lightgrey)](#)
[![Licence](https://img.shields.io/github/license/sub4ikgg/yandex-auth-kmp?color=green)](LICENSE)

**English** · [Русский](README_RU.md)

Yandex ID sign-in for Kotlin Multiplatform. One suspend call on Android and iOS:

```kotlin
when (val outcome = yandexAuthClient.signIn()) {
    is YandexAuthOutcome.Token -> sendToBackend(outcome.value)
    YandexAuthOutcome.Cancelled -> Unit
    is YandexAuthOutcome.Failed -> log(outcome.reason)
}
```

Ships in two pieces, because Yandex's iOS SDK is pure Swift and Kotlin/Native cannot call it:

| Piece | Where from |
|---|---|
| `tech.chatan:yandex-auth-kmp` | Maven Central. Kotlin API + Android. |
| `YandexAuthBridge` | SwiftPM, this repo. iOS. |

You write one adapter to join them (step 4, 20 lines). Requirements: Android 24+, iOS 14+, Kotlin 2.4.

---

## Step 1 · Register the app with Yandex and get a Client ID

Create an app at [oauth.yandex.ru/client/new](https://oauth.yandex.ru/client/new). One app covers both
platforms.

| Field | Value |
|---|---|
| Platform | `Android application`, `iOS app`, or both |
| Android package name | your `applicationId` |
| SHA256 Fingerprints | `./gradlew signingReport`. Add debug **and** release; with Play App Signing, use the fingerprint from Play Console |
| iOS AppId | `TEAMPREFIX.com.example.app` |
| Redirect URI | `yx<client-id>://auth/finish` |
| Data access | whatever your backend needs (usually email and name) |

## Step 2 · Add both halves as dependencies

Kotlin half from Maven Central, iOS half from SwiftPM.

**Gradle.** Make sure the repository is there:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()   // ← the artifact lives here
    }
}
```

Then, in your shared module:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            export("tech.chatan:yandex-auth-kmp:0.1.0")   // ← puts YandexAuthHandler in Shared.h; step 4 needs it
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("tech.chatan:yandex-auth-kmp:0.1.0")      // ← `api`, because `export` needs one
        }
    }
}
```

**SwiftPM**, in Xcode: *File → Add Package Dependencies* →
`https://github.com/sub4ikgg/yandex-auth-kmp` → add `YandexAuthBridge` to your app target. It pulls in
`YandexLoginSDK` itself.

## Step 3 · Wire up Android: manifest placeholders, then two lifecycle calls

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["YANDEX_CLIENT_ID"] = "your-client-id"
        manifestPlaceholders["YANDEX_OAUTH_HOST"] = "oauth.yandex.ru"
    }
}
```

> The Yandex AAR declares its `<meta-data>` and redirect intent-filter with these two placeholders.
> Miss them and the manifest merge fails.

Every **library** module that also sees the AAR needs the same two. On the KMP `androidLibrary`
plugin there is no `manifestPlaceholders` property, so:

```kotlin
androidComponents {
    onVariants { variant ->
        variant.manifestPlaceholders.put("YANDEX_CLIENT_ID", "your-client-id")
        variant.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        variant.hostTests.forEach { (_, hostTest) ->            // host tests build their own manifest
            hostTest.manifestPlaceholders.put("YANDEX_CLIENT_ID", "your-client-id")
            hostTest.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        }
    }
}
```

Then two lifecycle calls:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidYandexAuth.init(this)                  // once per process
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)            // ← the result registry is restored here
        AndroidYandexAuth.registerLauncher(this)      // ← in onStart this would throw
    }
}
```

## Step 4 · Wire up iOS: Info.plist, the adapter, and installing it at launch

**Info.plist:**

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleTypeRole</key><string>Editor</string>
        <key>CFBundleURLName</key><string>YandexLoginSDK</string>
        <key>CFBundleURLSchemes</key>
        <array><string>yx&lt;your-client-id&gt;</string></array>
    </dict>
</array>
<key>LSApplicationQueriesSchemes</key>
<array>
    <string>primaryyandexloginsdk</string>
    <string>secondaryyandexloginsdk</string>
</array>
```

> The scheme is `yx` + client id, no separator — it is how the redirect gets back into your app. The
> queried schemes let the SDK spot an installed Yandex app. Both are checked at launch, so a typo
> fails loudly there.

**The adapter.** The one file this library cannot ship, because it has to `import` your umbrella
framework and only you know its name:

```swift
// YandexAuthAdapter.swift
import Shared            // your KMP framework
import YandexAuthBridge

final class YandexAuthAdapter: YandexAuthHandler {

    func signIn(onResult: @escaping (any YandexAuthOutcome) -> Void) {
        YandexAuthBridge.shared.signIn { outcome in
            switch outcome {
            case .token(let value):   onResult(YandexAuthOutcomeToken(value: value))
            case .cancelled:          onResult(YandexAuthOutcomeCancelled.shared)
            case .failed(let reason): onResult(YandexAuthOutcomeFailed(reason: reason))
            }
        }
    }

    func signOut() {
        YandexAuthBridge.shared.signOut()
    }
}
```

**Install it at launch:**

```swift
@main
struct MyApp: App {

    init() {
        // returns false on a bad client id or plist instead of throwing:
        // a broken config is a `Failed` on the login screen, not a crash at launch
        if YandexAuthBridge.shared.install(clientID: "your-client-id") {
            IosYandexAuth.shared.bridge = YandexAuthAdapter()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // the redirect comes back either as a custom scheme...
                .onOpenURL { YandexAuthBridge.shared.handle(url: $0) }
                // ...or as a universal link, when the Yandex app handled the sign-in
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) {
                    YandexAuthBridge.shared.handle(userActivity: $0)
                }
        }
    }
}
```

## Step 5 · Call it from common code

```kotlin
val yandexAuthClient: YandexAuthClient by lazy {
    YandexAuthClient.factory(provideYandexAuthHandler())
}
```

> `lazy`, not a constructor argument: a `ViewModel` outlives the Activity that made it, so a handler
> passed in would point at a dead Activity after the first recreation — and the button would stop
> working with no error.

```kotlin
scope.launch {
    when (val outcome = yandexAuthClient.signIn()) {
        is YandexAuthOutcome.Token -> authRepository.signInWithYandex(outcome.value)
        YandexAuthOutcome.Cancelled -> Unit
        is YandexAuthOutcome.Failed -> showError()
    }
}
```

`signIn()` always returns, exactly once, and never throws.

| Outcome | Meaning |
|---|---|
| `Token` | The OAuth token. Send it to your backend; nothing is stored here. |
| `Cancelled` | The user backed out. Go idle. On iOS this also covers a few SDK failures it will not let us tell apart from a cancel. |
| `Failed` | The SDK broke. `reason` is for your log, not the user. |

`signOut()` clears the cached login. **Call it on iOS**, or the next `signIn()` silently reuses the
stored account. On Android it does nothing (the SDK caches nothing).

## Licence

MIT
