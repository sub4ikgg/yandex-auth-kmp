# yandex-auth-kmp

[![Maven Central](https://img.shields.io/maven-central/v/tech.chatan/yandex-auth-kmp?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/tech.chatan/yandex-auth-kmp)
[![Swift Package Manager](https://img.shields.io/badge/SwiftPM-compatible-brightgreen)](https://swiftpackageindex.com/sub4ikgg/yandex-auth-kmp)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Platforms](https://img.shields.io/badge/Platforms-Android%2024%2B%20%7C%20iOS%2014%2B-lightgrey)](#)
[![Licence](https://img.shields.io/github/license/sub4ikgg/yandex-auth-kmp?color=green)](LICENSE)

[English](README.md) · **Русский**

Вход через Яндекс ID для Kotlin Multiplatform. Один suspend-вызов на Android и iOS:

```kotlin
when (val outcome = yandexAuthClient.signIn()) {
    is YandexAuthOutcome.Token -> sendToBackend(outcome.value)
    YandexAuthOutcome.Cancelled -> Unit
    is YandexAuthOutcome.Failed -> log(outcome.reason)
}
```

Две части, потому что iOS-SDK Яндекса написан на чистом Swift и Kotlin/Native его не видит:

| Часть | Откуда |
|---|---|
| `tech.chatan:yandex-auth-kmp` | Maven Central. Kotlin-API + Android. |
| `YandexAuthBridge` | SwiftPM, этот репозиторий. iOS. |

Соединяются одним адаптером, который пишете вы (шаг 4, 20 строк). Требования: Android 24+, iOS 14+,
Kotlin 2.4.

---

## Шаг 1 · Регистрируем приложение у Яндекса и получаем Client ID

Кода пока нет. Здесь вы сообщаете Яндексу, какому приложению позволено просить токены, и забираете
идентификатор, по которому его узнают.

Создайте приложение на [oauth.yandex.ru/client/new](https://oauth.yandex.ru/client/new). Одно
приложение покрывает обе платформы.

| Поле | Что вписать |
|---|---|
| Платформа | `Android-приложение`, `iOS-приложение` или обе |
| Package name для Android | ваш `applicationId` |
| SHA256-фингерпринты | `./gradlew signingReport`. Добавьте debug **и** release; при Play App Signing — отпечаток из Play Console |
| AppId для iOS | `TEAMPREFIX.com.example.app` |
| Redirect URI | `yx<client-id>://auth/finish` |
| Доступ к данным | что нужно бэкенду (обычно почта и имя) |

Client ID — публичный идентификатор, не секрет. В мобильном OAuth нет client secret, оба SDK
используют PKCE. Коммитьте спокойно.

## Шаг 2 · Подключаем обе половины как зависимости

Kotlin-половина приезжает из Maven Central, iOS-половина — из SwiftPM. Два пакетных менеджера на одну
библиотеку, и это не наша прихоть: так её разделил Яндекс.

**Gradle**, в shared-модуле:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            export("tech.chatan:yandex-auth-kmp:0.1.0")   // ← обязательно
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("tech.chatan:yandex-auth-kmp:0.1.0")      // ← `api`, потому что этого требует `export`
        }
    }
}
```

> **Зачем `export`.** Swift реализует `YandexAuthHandler`, значит тип обязан попасть в `Shared.h`.
> Кладёт его туда только `export`. Без него шаг 4 не скомпилируется.

**SwiftPM**, в Xcode: *File → Add Package Dependencies* →
`https://github.com/sub4ikgg/yandex-auth-kmp` → добавьте `YandexAuthBridge` к таргету приложения.
`YandexLoginSDK` подтянется сам.

## Шаг 3 · Настраиваем Android: плейсхолдеры манифеста, потом два вызова в жизненном цикле

SDK Яндекса читает client id из манифеста, а не из кода, — сначала вы заполняете то, что он оставил
пустым. Потом отдаёте ему handler на весь процесс и launcher на каждую Activity.

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["YANDEX_CLIENT_ID"] = "ваш-client-id"
        manifestPlaceholders["YANDEX_OAUTH_HOST"] = "oauth.yandex.ru"
    }
}
```

> **Зачем.** AAR Яндекса объявляет свой `<meta-data>` и intent-фильтр редиректа через эти два
> плейсхолдера. Без них слияние манифестов падает.

Те же два значения нужны **каждому library-модулю**, который видит AAR. У KMP-плагина
`androidLibrary` свойства `manifestPlaceholders` нет, поэтому:

```kotlin
androidComponents {
    onVariants { variant ->
        variant.manifestPlaceholders.put("YANDEX_CLIENT_ID", "ваш-client-id")
        variant.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        variant.hostTests.forEach { (_, hostTest) ->            // host-тесты собирают свой манифест
            hostTest.manifestPlaceholders.put("YANDEX_CLIENT_ID", "ваш-client-id")
            hostTest.manifestPlaceholders.put("YANDEX_OAUTH_HOST", "oauth.yandex.ru")
        }
    }
}
```

Дальше два вызова в жизненном цикле:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidYandexAuth.init(this)                  // один раз на процесс
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)            // ← после super, до onStart
        AndroidYandexAuth.registerLauncher(this)
    }
}
```

> **Зачем такой порядок.** После `super.onCreate` реестр результатов уже восстановлен. В `onStart`
> регистрация activity result бросает исключение.

## Шаг 4 · Настраиваем iOS: Info.plist, адаптер и установка на старте

Здесь вы пишете тот единственный файл, который библиотека не может привезти за вас. Он учит
Swift-пакет отвечать Kotlin-стороне и ставит себя на место до того, как кто-то успеет попросить токен.

**Info.plist:**

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleTypeRole</key><string>Editor</string>
        <key>CFBundleURLName</key><string>YandexLoginSDK</string>
        <key>CFBundleURLSchemes</key>
        <array><string>yx&lt;ваш-client-id&gt;</string></array>
    </dict>
</array>
<key>LSApplicationQueriesSchemes</key>
<array>
    <string>primaryyandexloginsdk</string>
    <string>secondaryyandexloginsdk</string>
</array>
```

> **Зачем.** Схема — это `yx` + client id слитно, без разделителя: по ней редирект возвращается в
> приложение. Запрашиваемые схемы нужны, чтобы SDK заметил установленное приложение Яндекса. И то и
> другое проверяется на старте, так что опечатка вылезет сразу при запуске.

**Адаптер.** Единственный файл, который библиотека не может привезти сама: ему нужен `import` вашего
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

**Включить на старте:**

```swift
@main
struct MyApp: App {

    init() {
        if YandexAuthBridge.shared.install(clientID: "ваш-client-id") {
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

> **Зачем `if`.** При битом client id или plist `install` возвращает `false`, а не бросает исключение.
> Сломанная конфигурация станет `Failed` на экране входа, а не падением на запуске.
>
> **Зачем оба обработчика URL.** Редирект приходит либо своей схемой, либо universal link — если вход
> обработало приложение Яндекса.

## Шаг 5 · Вызываем из общего кода

Всё, что выше, было платформенной обвязкой. Дальше платформы неотличимы: собираете клиент один раз и
зовёте одну suspend-функцию.

```kotlin
val yandexAuthClient: YandexAuthClient by lazy {
    YandexAuthClient.factory(provideYandexAuthHandler())
}
```

> **Почему `lazy`, а не аргумент конструктора.** Retained-состояние (`ViewModel` и всё, что переживает
> пересоздание Activity) живёт дольше создавшей его Activity. Переданный аргументом handler после
> первого пересоздания будет указывать на мёртвую Activity, и кнопка тихо перестанет работать.

```kotlin
scope.launch {
    when (val outcome = yandexAuthClient.signIn()) {
        is YandexAuthOutcome.Token -> authRepository.signInWithYandex(outcome.value)
        YandexAuthOutcome.Cancelled -> Unit
        is YandexAuthOutcome.Failed -> showError()
    }
}
```

`signIn()` всегда возвращается, ровно один раз, и не бросает исключений.

| Исход | Что значит |
|---|---|
| `Token` | OAuth-токен. Отправьте на бэкенд; библиотека ничего не хранит. |
| `Cancelled` | Пользователь передумал. Экран возвращается в покой. На iOS сюда же попадают несколько сбоев SDK, которые он не даёт отличить от отмены. |
| `Failed` | Сломался SDK. `reason` — для лога, не для пользователя. |

`signOut()` стирает закешированный вход. **На iOS вызывать обязательно**, иначе следующий `signIn()`
молча переиспользует сохранённый аккаунт. На Android не делает ничего (SDK там ничего не кеширует).

## Лицензия

MIT
