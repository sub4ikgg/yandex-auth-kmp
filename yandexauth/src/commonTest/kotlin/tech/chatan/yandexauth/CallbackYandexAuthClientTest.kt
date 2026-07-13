package tech.chatan.yandexauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallbackYandexAuthClientTest {

    @Test
    fun handsBackTheTokenTheHandlerDelivers() = runTest {
        val client = YandexAuthClient.factory(ImmediateHandler(YandexAuthOutcome.Token("oauth-token")))

        assertEquals(YandexAuthOutcome.Token("oauth-token"), client.signIn())
    }

    @Test
    fun cancellationIsAnOutcomeLikeAnyOther() = runTest {
        val client = YandexAuthClient.factory(ImmediateHandler(YandexAuthOutcome.Cancelled))

        assertEquals(YandexAuthOutcome.Cancelled, client.signIn())
    }

    @Test
    fun failureCarriesTheReason() = runTest {
        val client = YandexAuthClient.factory(ImmediateHandler(YandexAuthOutcome.Failed("sdk said no")))

        assertEquals(YandexAuthOutcome.Failed("sdk said no"), client.signIn())
    }

    /**
     * The important one. A handler that answers twice must not crash. On iOS the watchdog can fire
     * just as the token arrives, and `suspendCancellableCoroutine` would throw "Already resumed".
     */
    @Test
    fun theFirstOutcomeWinsAndTheSecondIsIgnored() = runTest {
        val handler = TwiceHandler(
            first = YandexAuthOutcome.Token("real-token"),
            second = YandexAuthOutcome.Cancelled,
        )

        assertEquals(YandexAuthOutcome.Token("real-token"), YandexAuthClient.factory(handler).signIn())
    }

    @Test
    fun signOutReachesTheHandler() = runTest {
        val handler = ImmediateHandler(YandexAuthOutcome.Cancelled)

        YandexAuthClient.factory(handler).signOut()

        assertTrue(handler.signedOut)
    }
}

private class ImmediateHandler(
    private val outcome: YandexAuthOutcome,
) : YandexAuthHandler {

    var signedOut: Boolean = false
        private set

    override fun signIn(onResult: (YandexAuthOutcome) -> Unit) = onResult(outcome)

    override fun signOut() {
        signedOut = true
    }
}

private class TwiceHandler(
    private val first: YandexAuthOutcome,
    private val second: YandexAuthOutcome,
) : YandexAuthHandler {

    override fun signIn(onResult: (YandexAuthOutcome) -> Unit) {
        onResult(first)
        onResult(second)
    }

    override fun signOut() = Unit
}
