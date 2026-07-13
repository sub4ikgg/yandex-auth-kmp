# yandex-auth-kmp

Yandex ID sign-in for Kotlin Multiplatform. One suspend call, on Android and on iOS:

```kotlin
when (val outcome = yandexAuthClient.signIn()) {
    is YandexAuthOutcome.Token -> sendToBackend(outcome.value)
    YandexAuthOutcome.Cancelled -> Unit
    is YandexAuthOutcome.Failed -> log(outcome.reason)
}
```

No `Activity`, no `UIViewController`, no Yandex SDK types above the boundary.

## Why the library ships in two pieces

| Piece | Where it comes from | What it is |
|---|---|---|
| `tech.chatan:yandex-auth-kmp` | Maven Central | The Kotlin API and the Android implementation |
| `YandexAuthBridge` | Swift Package, this same repo | The iOS implementation over Yandex's `YandexLoginSDK` |

The Yandex iOS SDK is written in pure Swift and has no Objective-C headers, so Kotlin/Native cannot
call it at all. Swift has to implement the iOS half. Your app joins the two with a small adapter, and
that adapter is the only code you write yourself. It is in step 4.

Requirements: Android 24+, iOS 14+, Kotlin 2.4.

---

## Step 1. Get a client id from Yandex

Go to [oauth.yandex.ru/client/new](https://oauth.yandex.ru/client/new) and create an app. One app can
serve both platforms, so you do not need two.

**Platform.** Tick `Android application`, `iOS app`, or both.

**For Android**, fill in:

- `Android package name` — your `applicationId`, exactly as in `build.gradle.kts`.
- `SHA256 Fingerprints` — the fingerprint of the certificate you sign with. Get it with:

  ```bash
  ./gradlew signingReport
  ```

  Add every certificate you will actually use. Debug and release builds are signed with different
  keys and therefore have different fingerprints. If Play App Signing is on, the fingerprint that
  matters is the one Google shows in the Play Console, not your upload key.

**For iOS**, fill in:

- `iOS AppId` — team prefix plus bundle id, like `A1B2C3D4E5.com.example.app`.

**Redirect URI.** The SDKs bring the token home through `yx<client-id>://auth/finish`. Fill that in,
substituting the id the console gives you.

**Data access.** Tick the scopes your backend needs. For a sign-in flow that is usually the email
address and the user's name.

You end up with a **Client ID**: a 32-character hex string. It is **not a secret**. A mobile OAuth
flow ships no client secret, and both SDKs use PKCE. Committing it is fine.

---

## Step 2. Add the dependencies

### Kotlin, in your shared module

```kotlin
// shared/build.gradle.kts
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Required. Swift implements `YandexAuthHandler`, so the type has to appear in Shared.h,
            // and only an `export` puts it there.
            export("tech.chatan:yandex-auth-kmp:0.1.0")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: the `export` above needs one to back it.
            api("tech.chatan:yandex-auth-kmp:0.1.0")
        }
    }
}
```

Miss the `export` and the types never reach `Shared.h`, so Swift cannot see them and the adapter in
step 4 will not compile.

### Swift, in your iOS app

In Xcode: **File → Add Package Dependencies**, then paste

```
https://github.com/sub4ikgg/yandex-auth-kmp
```

Pick `Up to Next Major Version` from `0.1.0` and add the `YandexAuthBridge` library to your app
target. It pulls in `YandexLoginSDK` on its own; you do not add that separately.

---

## Step 3. Set up Android

### Manifest placeholders

The Yandex AuthSDK reads its client id from the manifest, not from code. Its own manifest declares a
`<meta-data>` entry and the intent filter that catches the OAuth redirect, and leaves two
placeholders for you:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["YANDEX_CLIENT_ID"] = "your-client-id"
        manifestPlaceholders["YANDEX_OAUTH_HOST"] = "oauth.yandex.ru"
    }
}
```

Skip this and the manifest merge fails outright.

Every **library** module that also sees the Yandex AAR needs the same two values. If that module uses
the KMP `androidLibrary` plugin, it has no `manifestPlaceholders` property, so go through the variant
API instead:

```kotlin
androidComponents {
    onVariants { variant ->
        variant.manifestPlaceholders.put("YANDEX_CLIENT_ID", "your-client-id")
        variant.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        // Host tests build a manifest of their own and do not inherit the above.
        variant.hostTests.forEach { (_, hostTest) ->
            hostTest.manifestPlaceholders.put("YANDEX_CLIENT_ID", "your-client-id")
            hostTest.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        }
    }
}
```

### Two lifecycle hooks

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
        // After super.onCreate, so the activity result registry has restored itself.
        // Before onStart, because registering an activity result there throws.
        AndroidYandexAuth.registerLauncher(this)
    }
}
```

`init` builds the handler once for the whole process. `registerLauncher` binds a fresh launcher to
each Activity and drops it on destroy. The handler outlives the Activity on purpose: see step 5.

---

## Step 4. Set up iOS

### Info.plist

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleTypeRole</key>
        <string>Editor</string>
        <key>CFBundleURLName</key>
        <string>YandexLoginSDK</string>
        <key>CFBundleURLSchemes</key>
        <array>
            <string>yx&lt;your-client-id&gt;</string>
        </array>
    </dict>
</array>
<key>LSApplicationQueriesSchemes</key>
<array>
    <string>primaryyandexloginsdk</string>
    <string>secondaryyandexloginsdk</string>
</array>
```

The URL scheme is the literal text `yx` followed by your client id, with no separator. It is how the
OAuth redirect finds its way back into your app. The queried schemes are how the SDK notices an
installed Yandex app to hand off to.

Both are checked when the SDK starts, so a mistake here shows up at launch and not later as a button
that does nothing.

### The adapter

This is the one file the library cannot write for you, because it has to `import` your umbrella
framework, and only you know its name.

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

### Wire it up at launch

```swift
@main
struct MyApp: App {

    init() {
        // Before the first view exists, so nothing can ask for a token too early.
        if YandexAuthBridge.shared.install(clientID: "your-client-id") {
            IosYandexAuth.shared.bridge = YandexAuthAdapter()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // The redirect comes back as a custom scheme, or as a universal link when the
                // Yandex app handled the sign-in. Wire both.
                .onOpenURL { YandexAuthBridge.shared.handle(url: $0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) {
                    YandexAuthBridge.shared.handle(userActivity: $0)
                }
        }
    }
}
```

`install` returns `false` if the client id is malformed or the `Info.plist` is wrong. It does not
throw and it does not crash: a bad plist should not take the app down at launch. Install the adapter
only when it returns `true`, and a tap on the Yandex button will report a plain failure instead of
hanging.

---

## Step 5. Use it

Build the client once, at process scope, and read the handler late:

```kotlin
object AppGraph {
    val yandexAuthClient: YandexAuthClient by lazy {
        YandexAuthClient.factory(provideYandexAuthHandler())
    }
}
```

Late, and this matters. Retained state (a `ViewModel`, or anything else that survives Activity
recreation) lives longer than the Activity that created it. A handler passed in as a constructor
argument would still point at the old Activity after the first recreation, and the Yandex button
would quietly stop working. Reading it from `provideYandexAuthHandler()` at the moment of use makes
that impossible.

Then, anywhere:

```kotlin
scope.launch {
    when (val outcome = AppGraph.yandexAuthClient.signIn()) {
        is YandexAuthOutcome.Token -> authRepository.signInWithYandex(outcome.value)
        YandexAuthOutcome.Cancelled -> Unit
        is YandexAuthOutcome.Failed -> showError()
    }
}
```

`signIn()` always comes back, exactly once. It never throws.

## What the outcomes mean

`Token` carries the OAuth access token. Send it to your backend. The library stores nothing.

`Cancelled` means the user backed out. It is a normal answer, not an error, and the screen should
simply go idle. On iOS it also covers a few SDK failures that the SDK does not let us tell apart from
a cancel, so a rare in-app network failure reads as a cancel too. That is a conscious trade: showing
an error to someone who pressed "Cancel" is worse than staying quiet on a failure they can retry.

`Failed` means the SDK itself broke. The reason is for your log, not for the user.

`signOut()` clears the login the SDK cached. On iOS you have to call it, otherwise the next
`signIn()` silently reuses the stored account and signs the user back into the one they just left.
On Android it does nothing, because the SDK keeps nothing between sign-ins.

## Licence

MIT
