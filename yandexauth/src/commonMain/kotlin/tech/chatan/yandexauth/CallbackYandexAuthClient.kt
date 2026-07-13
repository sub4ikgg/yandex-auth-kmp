package tech.chatan.yandexauth

import kotlinx.coroutines.CompletableDeferred

/**
 * Turns the platform callback into a suspend call.
 *
 * [CompletableDeferred] and not `suspendCancellableCoroutine`, because `complete()` can be called
 * twice safely. A handler that answers twice is ignored the second time instead of crashing with
 * `Already resumed`. The first answer wins.
 */
internal class CallbackYandexAuthClient(
    private val handler: YandexAuthHandler,
) : YandexAuthClient {

    override suspend fun signIn(): YandexAuthOutcome {
        val outcome = CompletableDeferred<YandexAuthOutcome>()
        handler.signIn { result -> outcome.complete(result) }
        return outcome.await()
    }

    override fun signOut(): Unit = handler.signOut()
}
