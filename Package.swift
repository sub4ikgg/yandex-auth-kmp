// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "YandexAuthBridge",
    platforms: [
        .iOS(.v14)
    ],
    products: [
        .library(
            name: "YandexAuthBridge",
            targets: ["YandexAuthBridge"]
        )
    ],
    dependencies: [
        .package(
            url: "https://github.com/yandexmobile/yandex-login-sdk-ios",
            from: "3.1.1"
        )
    ],
    targets: [
        .target(
            name: "YandexAuthBridge",
            dependencies: [
                .product(name: "YandexLoginSDK", package: "yandex-login-sdk-ios")
            ]
        )
    ]
)
