package tech.chatan.yandexauth

/**
 * Where Swift plugs in. The Yandex iOS SDK is pure Swift with no Objective-C headers, so Kotlin/Native
 * cannot call it. Swift implements [YandexAuthHandler] and puts itself here at launch.
 */
public object IosYandexAuth {

    public var bridge: YandexAuthHandler? = null
}
