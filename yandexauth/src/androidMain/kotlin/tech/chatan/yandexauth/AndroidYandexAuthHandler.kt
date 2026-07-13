package tech.chatan.yandexauth

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import com.yandex.authsdk.YandexAuthSdkContract
import com.yandex.authsdk.internal.strategy.LoginType

/**
 * Runs the Yandex SDK's `ActivityResultContract`.
 *
 * It lives as long as the process, because the code waiting for the result also survives Activity
 * recreation. Only the launcher belongs to the Activity: a new one is bound on each `onCreate`.
 */
public class AndroidYandexAuthHandler internal constructor(
    context: Context,
) : YandexAuthHandler {

    // Lazy, because building the options reads manifest meta-data through the package manager, and
    // this object is built in `Application.onCreate`. The client id comes from that meta-data, which
    // the app fills with its YANDEX_CLIENT_ID placeholder. See README.
    private val sdk: YandexAuthSdk by lazy { YandexAuthSdk.create(YandexAuthOptions(context)) }

    private var launcher: ActivityResultLauncher<YandexAuthLoginOptions>? = null
    private var pending: ((YandexAuthOutcome) -> Unit)? = null

    internal val contract: YandexAuthSdkContract get() = sdk.contract

    override fun signIn(onResult: (YandexAuthOutcome) -> Unit) {
        cancelPending()

        val target = launcher
        if (target == null) {
            // No Activity on screen to show the SDK. Nothing broke, there is just nowhere to go.
            onResult(YandexAuthOutcome.Cancelled)
            return
        }

        pending = onResult
        // NATIVE only says where the SDK starts. With no Yandex app installed it falls back to a
        // Custom Tab or a WebView by itself.
        target.launch(YandexAuthLoginOptions(loginType = LoginType.NATIVE))
    }

    /** The Android SDK stores nothing between sign-ins, so there is nothing to clear. */
    override fun signOut(): Unit = Unit

    internal fun bindLauncher(launcher: ActivityResultLauncher<YandexAuthLoginOptions>) {
        this.launcher = launcher
    }

    /** The Activity is going away and takes its launcher with it. The request it served stays. */
    internal fun unbindLauncher(launcher: ActivityResultLauncher<YandexAuthLoginOptions>) {
        if (this.launcher === launcher) this.launcher = null
    }

    internal fun deliver(result: YandexAuthResult) {
        // Take the callback before calling it. If nobody is waiting, drop the result: the process died
        // mid-flow and the registry redelivered it into a fresh one.
        val callback = pending ?: return
        pending = null
        callback(result.toOutcome())
    }

    /** A second tap while one sign-in is running. Answer the first caller instead of leaving it hanging. */
    private fun cancelPending() {
        val previous = pending ?: return
        pending = null
        previous(YandexAuthOutcome.Cancelled)
    }
}

private fun YandexAuthResult.toOutcome(): YandexAuthOutcome =
    when (this) {
        is YandexAuthResult.Success -> YandexAuthOutcome.Token(token.value)
        is YandexAuthResult.Failure -> YandexAuthOutcome.Failed(exception.errors.joinToString())
        YandexAuthResult.Cancelled -> YandexAuthOutcome.Cancelled
    }
