package tech.chatan.yandexauth

public actual fun provideYandexAuthHandler(): YandexAuthHandler = AndroidYandexAuth.handler
