package tech.chatan.yandexauth

/** How a Yandex ID sign-in ended. [Cancelled] is a normal answer, not an error. */
public sealed interface YandexAuthOutcome {

    /** The OAuth access token. Send it to your backend. This library does not store it. */
    public data class Token(val value: String) : YandexAuthOutcome

    public data object Cancelled : YandexAuthOutcome

    /** [reason] is for the log, not for the user. */
    public data class Failed(val reason: String?) : YandexAuthOutcome
}
