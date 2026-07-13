import Foundation

/// How a Yandex ID sign-in ended. `cancelled` is a normal answer, not an error.
///
/// This is the package's own type. Nothing here knows about Kotlin, which is what lets the package be
/// a normal SwiftPM dependency. Your adapter maps it onto Kotlin's `YandexAuthOutcome`. See README.
public enum YandexAuthBridgeOutcome {

    /// The OAuth access token. Send it to your backend. This package does not store it.
    case token(String)

    case cancelled

    /// For the log, not for the user.
    case failed(String?)
}
