package tech.chatan.yandexauth

/**
 * The handler for this platform. One per process, and read late.
 *
 * Read late because retained state (a `ViewModel`, or anything else that survives Activity
 * recreation) lives longer than the Activity that created it. A handler passed in as a constructor
 * argument would point at the old Activity after the first recreation, and the button would stop
 * working with no error.
 *
 * On Android, `AndroidYandexAuth.init()` must run first, from `Application.onCreate`.
 */
public expect fun provideYandexAuthHandler(): YandexAuthHandler
