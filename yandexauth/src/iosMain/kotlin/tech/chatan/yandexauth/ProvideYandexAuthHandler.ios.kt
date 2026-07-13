package tech.chatan.yandexauth

private val handler = IosYandexAuthHandler()

public actual fun provideYandexAuthHandler(): YandexAuthHandler = handler
