package tech.chatan.yandexauth

private const val BRIDGE_MISSING = "The Swift Yandex bridge was never installed"

/**
 * Reads [IosYandexAuth.bridge] on every call instead of keeping a copy, so it does not matter whether
 * the handler or the Swift bridge was set up first. A missing bridge is a failure, not a crash.
 */
internal class IosYandexAuthHandler : YandexAuthHandler {

    override fun signIn(onResult: (YandexAuthOutcome) -> Unit) {
        val bridge = IosYandexAuth.bridge
        if (bridge == null) {
            onResult(YandexAuthOutcome.Failed(BRIDGE_MISSING))
            return
        }
        bridge.signIn(onResult)
    }

    override fun signOut() {
        IosYandexAuth.bridge?.signOut()
    }
}
