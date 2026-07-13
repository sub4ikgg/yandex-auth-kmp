# yandex-auth-kmp

Yandex ID sign-in for Kotlin Multiplatform. One suspend call on Android and iOS:

```kotlin
when (val outcome = yandexAuthClient.signIn()) {
    is YandexAuthOutcome.Token -> sendToBackend(outcome.value)
    YandexAuthOutcome.Cancelled -> Unit
    is YandexAuthOutcome.Failed -> log(outcome.reason)
}
```

No `Activity`, no `UIViewController`, no Yandex SDK types above the boundary.

The library ships in two halves, because it has to:

| | |
|---|---|
| `tech.chatan:yandex-auth-kmp` | Maven Central. The Kotlin API plus the Android implementation. |
| `YandexAuthBridge` | Swift Package. The iOS implementation over Yandex's pure-Swift `YandexLoginSDK`. |

Kotlin/Native cannot call the Yandex iOS SDK at all: it is pure Swift, with no Objective-C headers to
bind to. So Swift implements the iOS half, and your app joins the two with a short adapter. That
adapter is the only code you have to write yourself, and it is below.

## Install

### Gradle

```kotlin
// build.gradle.kts of your shared module
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Required. Swift implements `YandexAuthHandler`, so the type must appear in Shared.h.
            export("tech.chatan:yandex-auth-kmp:0.1.0")
        }
    }
    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: `export` above needs one.
            api("tech.chatan:yandex-auth-kmp:0.1.0")
        }
    }
}
```

Without the `export`, the types never reach `Shared.h` and Swift cannot see them.

### Swift Package Manager

In Xcode: **File → Add Package Dependencies**, then
`https://github.com/sub4ikgg/yandex-auth-kmp`.

## Android setup

The Yandex AuthSDK reads its client id from the manifest, not from code, and leaves two placeholders
for you to fill:

```kotlin
// build.gradle.kts of your app module
android {
    defaultConfig {
        manifestPlaceholders["YANDEX_CLIENT_ID"] = "your-client-id"
        manifestPlaceholders["YANDEX_OAUTH_HOST"] = "oauth.yandex.ru"
    }
}
```

The merge fails outright if you skip this. Any *library* module that also sees the Yandex AAR needs
the same two values; with the KMP `androidLibrary` plugin, which has no `manifestPlaceholders`
property, go through the variant API:

```kotlin
androidComponents {
    onVariants { variant ->
        variant.manifestPlaceholders.put("YANDEX_CLIENT_ID", "your-client-id")
        variant.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        // Host tests build their own manifest and do not inherit the above.
        variant.hostTests.forEach { (_, hostTest) ->
            hostTest.manifestPlaceholders.put("YANDEX_CLIENT_ID", "your-client-id")
            hostTest.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        }
    }
}
```

Then wire the two lifecycle points:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidYandexAuth.init(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After super.onCreate, before onStart.
        AndroidYandexAuth.registerLauncher(this)
    }
}
```

## iOS setup

`Info.plist` needs the redirect scheme and the two schemes the SDK checks for an installed Yandex app:

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleURLSchemes</key>
        <array><string>yx&lt;your-client-id&gt;</string></array>
    </dict>
</array>
<key>LSApplicationQueriesSchemes</key>
<array>
    <string>yandexauth</string>
    <string>yandexauth2</string>
    <string>primaryyandexloginsdk</string>
    <string>secondaryyandexloginsdk</string>
</array>
```

Then the adapter. It is the only Swift this library cannot write for you, because it has to `import`
your umbrella framework, and only you know its name:

```swift
import Shared          // your KMP framework
import YandexAuthBridge

final class YandexAuthAdapter: YandexAuthHandler {

    func signIn(onResult: @escaping (any YandexAuthOutcome) -> Void) {
        YandexAuthBridge.shared.signIn { outcome in
            switch outcome {
            case .token(let value):  onResult(YandexAuthOutcomeToken(value: value))
            case .cancelled:         onResult(YandexAuthOutcomeCancelled.shared)
            case .failed(let reason): onResult(YandexAuthOutcomeFailed(reason: reason))
            }
        }
    }

    func signOut() {
        YandexAuthBridge.shared.signOut()
    }
}
```

Install it at launch, before the first view exists:

```swift
@main
struct MyApp: App {

    init() {
        if YandexAuthBridge.shared.install(clientID: "your-client-id") {
            IosYandexAuth.shared.bridge = YandexAuthAdapter()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { YandexAuthBridge.shared.handle(url: $0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) {
                    YandexAuthBridge.shared.handle(userActivity: $0)
                }
        }
    }
}
```

The client id is not a secret. A mobile OAuth flow ships no client secret, and both SDKs use PKCE.

## Use

Build the client once, at process scope, and read the handler late:

```kotlin
val yandexAuthClient: YandexAuthClient by lazy {
    YandexAuthClient.factory(provideYandexAuthHandler())
}
```

Late on purpose. Retained state (a `ViewModel`, a Decompose component) outlives the Activity that
created it, so a handler passed in as a constructor argument would point at the old Activity after the
first recreation, and the button would stop working with no error.

`signIn()` always returns, exactly once. `Cancelled` covers backing out of the Yandex screen and, on
iOS, a few SDK failures the SDK does not let us tell apart from a cancel.

## Requirements

Android 24+ · iOS 14+ · Kotlin 2.4

## Licence

MIT
