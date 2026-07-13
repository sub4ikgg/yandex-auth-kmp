package tech.chatan.yandexauth

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * A fixed key. `registerForActivityResult()` would build one from the registration order, and that
 * changes. The result must find its way back even if the process was killed on the Yandex screen.
 */
private const val RESULT_REGISTRY_KEY = "tech.chatan.yandexauth.result"

/** Holds the Android handler. The only place here that knows about `Activity`. */
public object AndroidYandexAuth {

    private var instance: AndroidYandexAuthHandler? = null

    internal val handler: AndroidYandexAuthHandler
        get() = checkNotNull(instance) {
            "AndroidYandexAuth.init() must run from Application.onCreate()"
        }

    /** Call from `Application.onCreate`. */
    public fun init(application: Application) {
        instance = AndroidYandexAuthHandler(application)
    }

    /**
     * Call from every `Activity.onCreate`. After `super.onCreate`, so the result registry is restored,
     * and before `onStart`, because registering an activity result there throws.
     */
    public fun registerLauncher(activity: ComponentActivity) {
        val handler = handler
        val launcher = activity.activityResultRegistry.register(
            RESULT_REGISTRY_KEY,
            activity,
            handler.contract,
        ) { result -> handler.deliver(result) }

        handler.bindLauncher(launcher)
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    handler.unbindLauncher(launcher)
                }
            },
        )
    }
}
