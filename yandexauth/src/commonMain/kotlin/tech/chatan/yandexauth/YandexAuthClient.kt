package tech.chatan.yandexauth

/**
 * Yandex sign-in as your code sees it: one suspend call. No SDK types, no `Activity`, no
 * `UIViewController`.
 */
public interface YandexAuthClient {

    public suspend fun signIn(): YandexAuthOutcome

    /** See [YandexAuthHandler.signOut]. It cannot fail, so it returns nothing. */
    public fun signOut()

    public companion object {
        public fun factory(handler: YandexAuthHandler): YandexAuthClient =
            CallbackYandexAuthClient(handler)
    }
}
