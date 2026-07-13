# yandex-auth-kmp

[![Maven Central](https://img.shields.io/maven-central/v/tech.chatan/yandex-auth-kmp?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/tech.chatan/yandex-auth-kmp)
[![Swift Package Manager](https://img.shields.io/badge/SwiftPM-compatible-brightgreen)](https://swiftpackageindex.com/sub4ikgg/yandex-auth-kmp)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Platforms](https://img.shields.io/badge/Platforms-Android%2024%2B%20%7C%20iOS%2014%2B-lightgrey)](#)
[![Licence](https://img.shields.io/github/license/sub4ikgg/yandex-auth-kmp?color=green)](LICENSE)

[English](README.md) · **Русский**

Вход через Яндекс ID для Kotlin Multiplatform. Один suspend-вызов, одинаково на Android и на iOS:

```kotlin
when (val outcome = yandexAuthClient.signIn()) {
    is YandexAuthOutcome.Token -> sendToBackend(outcome.value)
    YandexAuthOutcome.Cancelled -> Unit
    is YandexAuthOutcome.Failed -> log(outcome.reason)
}
```

Ни `Activity`, ни `UIViewController`, ни типов SDK Яндекса выше границы.

## Почему библиотека состоит из двух частей

| Часть | Откуда берётся | Что это |
|---|---|---|
| `tech.chatan:yandex-auth-kmp` | Maven Central | Kotlin-API и реализация под Android |
| `YandexAuthBridge` | Swift Package, этот же репозиторий | Реализация под iOS поверх `YandexLoginSDK` |

iOS-SDK Яндекса написан на чистом Swift и не имеет Objective-C заголовков, поэтому Kotlin/Native не
может обратиться к нему вообще. iOS-половину обязан реализовать Swift. Ваше приложение соединяет две
части небольшим адаптером, и этот адаптер — единственный код, который вы пишете сами. Он в шаге 4.

Требования: Android 24+, iOS 14+, Kotlin 2.4.

---

## Шаг 1. Получить client id у Яндекса

Зайдите на [oauth.yandex.ru/client/new](https://oauth.yandex.ru/client/new) и создайте приложение.
Одно приложение обслуживает обе платформы, заводить два не нужно.

**Платформа.** Отметьте `Android-приложение`, `iOS-приложение` или обе.

**Для Android** заполните:

- `Package name для Android` — ваш `applicationId`, ровно как в `build.gradle.kts`.
- `SHA256-фингерпринты` — отпечаток сертификата, которым вы подписываете приложение. Получить так:

  ```bash
  ./gradlew signingReport
  ```

  Добавьте все сертификаты, которыми реально пользуетесь. Debug и release подписываются разными
  ключами, а значит и отпечатки у них разные. Если включена подпись через Play App Signing, нужен тот
  отпечаток, который показывает Play Console, а не ваш upload-ключ.

**Для iOS** заполните:

- `AppId для iOS` — префикс команды и bundle id, например `A1B2C3D4E5.com.example.app`.

**Redirect URI.** SDK возвращает токен через `yx<client-id>://auth/finish`. Впишите это, подставив
выданный вам id.

**Доступ к данным.** Отметьте права, которые нужны вашему бэкенду. Для входа это обычно почта и имя
пользователя.

На выходе получите **Client ID** — 32 символа в шестнадцатеричном виде. Это **не секрет**: мобильный
OAuth-поток не содержит client secret, оба SDK используют PKCE. Коммитить его можно спокойно.

---

## Шаг 2. Подключить зависимости

### Kotlin, в вашем shared-модуле

```kotlin
// shared/build.gradle.kts
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Обязательно. Swift реализует `YandexAuthHandler`, значит тип должен попасть в Shared.h,
            // а туда его кладёт только `export`.
            export("tech.chatan:yandex-auth-kmp:0.1.0")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, а не `implementation`: `export` выше требует именно её.
            api("tech.chatan:yandex-auth-kmp:0.1.0")
        }
    }
}
```

Забудете `export` — типы не дойдут до `Shared.h`, Swift их не увидит, и адаптер из шага 4 не
скомпилируется.

### Swift, в вашем iOS-приложении

В Xcode: **File → Add Package Dependencies**, вставьте

```
https://github.com/sub4ikgg/yandex-auth-kmp
```

Выберите `Up to Next Major Version` от `0.1.0` и добавьте библиотеку `YandexAuthBridge` к таргету
приложения. `YandexLoginSDK` она подтянет сама, отдельно подключать его не надо.

---

## Шаг 3. Настроить Android

### Плейсхолдеры манифеста

AuthSDK Яндекса читает client id из манифеста, а не из кода. В своём манифесте он объявляет
`<meta-data>` и intent-фильтр, который ловит OAuth-редирект, и оставляет вам два плейсхолдера:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["YANDEX_CLIENT_ID"] = "ваш-client-id"
        manifestPlaceholders["YANDEX_OAUTH_HOST"] = "oauth.yandex.ru"
    }
}
```

Пропустите это — и слияние манифестов упадёт сразу.

Те же два значения нужны **каждому library-модулю**, который тоже видит AAR Яндекса. Если модуль на
KMP-плагине `androidLibrary`, свойства `manifestPlaceholders` у него нет, и путь один — через
variant API:

```kotlin
androidComponents {
    onVariants { variant ->
        variant.manifestPlaceholders.put("YANDEX_CLIENT_ID", "ваш-client-id")
        variant.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        // Host-тесты собирают свой манифест и это не наследуют.
        variant.hostTests.forEach { (_, hostTest) ->
            hostTest.manifestPlaceholders.put("YANDEX_CLIENT_ID", "ваш-client-id")
            hostTest.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        }
    }
}
```

### Две точки жизненного цикла

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
        // После super.onCreate, чтобы реестр результатов успел восстановиться.
        // До onStart, потому что там регистрация activity result уже бросает исключение.
        AndroidYandexAuth.registerLauncher(this)
    }
}
```

`init` создаёт handler один раз на весь процесс. `registerLauncher` привязывает свежий launcher к
каждой Activity и отпускает его при destroy. Handler живёт дольше Activity намеренно — см. шаг 5.

---

## Шаг 4. Настроить iOS

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
            <string>yx&lt;ваш-client-id&gt;</string>
        </array>
    </dict>
</array>
<key>LSApplicationQueriesSchemes</key>
<array>
    <string>primaryyandexloginsdk</string>
    <string>secondaryyandexloginsdk</string>
</array>
```

URL-схема — это буквально `yx`, слитно с вашим client id, без разделителя. По ней OAuth-редирект
находит дорогу обратно в приложение. Запрашиваемые схемы нужны, чтобы SDK заметил установленное
приложение Яндекса и передал вход ему.

И то и другое проверяется при старте SDK, так что ошибка здесь всплывёт на запуске, а не потом в виде
кнопки, которая ничего не делает.

### Адаптер

Это единственный файл, который библиотека не может написать за вас: ему нужен `import` вашего
umbrella-фреймворка, а его имя знаете только вы.

```swift
// YandexAuthAdapter.swift
import Shared            // ваш KMP-фреймворк
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

### Включить всё это на старте

```swift
@main
struct MyApp: App {

    init() {
        // До первого view, чтобы никто не успел попросить токен слишком рано.
        if YandexAuthBridge.shared.install(clientID: "ваш-client-id") {
            IosYandexAuth.shared.bridge = YandexAuthAdapter()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // Редирект приходит либо своей схемой, либо universal link, если вход обработало
                // приложение Яндекса. Подключите оба.
                .onOpenURL { YandexAuthBridge.shared.handle(url: $0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) {
                    YandexAuthBridge.shared.handle(userActivity: $0)
                }
        }
    }
}
```

`install` возвращает `false`, если client id битый или `Info.plist` собран неправильно. Он не бросает
исключение и не падает: плохой plist не должен ронять приложение на запуске. Ставьте адаптер только
когда вернулось `true` — тогда нажатие на кнопку Яндекса вернёт обычную ошибку вместо зависания.

---

## Шаг 5. Пользоваться

Соберите клиент один раз, на уровне процесса, и читайте handler поздно:

```kotlin
object AppGraph {
    val yandexAuthClient: YandexAuthClient by lazy {
        YandexAuthClient.factory(provideYandexAuthHandler())
    }
}
```

Поздно — и это важно. Retained-состояние (`ViewModel` или что угодно другое, что переживает
пересоздание Activity) живёт дольше, чем Activity, которая его создала. Handler, переданный
аргументом конструктора, после первого же пересоздания будет указывать на старую Activity, и кнопка
Яндекса тихо перестанет работать. Чтение через `provideYandexAuthHandler()` в момент вызова делает
это невозможным.

Дальше — где угодно:

```kotlin
scope.launch {
    when (val outcome = AppGraph.yandexAuthClient.signIn()) {
        is YandexAuthOutcome.Token -> authRepository.signInWithYandex(outcome.value)
        YandexAuthOutcome.Cancelled -> Unit
        is YandexAuthOutcome.Failed -> showError()
    }
}
```

`signIn()` всегда возвращается, ровно один раз. Исключений не бросает.

## Что означают исходы

`Token` несёт OAuth-токен. Отправьте его на свой бэкенд. Библиотека ничего не хранит.

`Cancelled` значит, что пользователь передумал. Это нормальный ответ, а не ошибка, и экран должен
просто вернуться в покой. На iOS сюда же попадают несколько сбоев SDK, которые SDK не даёт отличить
от отмены, так что редкий сетевой сбой внутри приложения тоже прочтётся как отмена. Это осознанный
размен: показать ошибку тому, кто нажал «Отмена», хуже, чем промолчать про сбой, который он всё равно
может повторить.

`Failed` значит, что сломался сам SDK. Причина — для вашего лога, не для пользователя.

`signOut()` стирает вход, который SDK закешировал. На iOS вызывать обязательно, иначе следующий
`signIn()` молча переиспользует сохранённый аккаунт и вернёт пользователя туда, откуда он только что
вышел. На Android не делает ничего: Android-SDK между входами ничего не хранит.

## Лицензия

MIT
