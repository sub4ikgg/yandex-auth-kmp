package tech.chatan.yandexauth

/**
 * The platform side of sign-in: an `ActivityResultContract` on Android, a Swift bridge on iOS.
 * It takes a callback because Kotlin/Native does not let Swift override a `suspend` function.
 *
 * Rule: every [signIn] must call back exactly once. A handler that stays silent hangs the login screen.
 */
public interface YandexAuthHandler {

    public fun signIn(onResult: (YandexAuthOutcome) -> Unit)

    /**
     * Clears the login the SDK cached. Without it, iOS reuses that login on the next `authorize()` and
     * signs the user back into the account they just left.
     */
    public fun signOut()
}
