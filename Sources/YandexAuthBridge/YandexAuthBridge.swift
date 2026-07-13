import AuthenticationServices
import Foundation
import UIKit
import YandexLoginSDK

/// The Swift side of Yandex ID sign-in. It wraps `YandexLoginSDK`, which is pure Swift, in an API a
/// Kotlin Multiplatform app can call from a short adapter. See README.
///
/// The rule below is that every `signIn` calls back exactly once. The SDK breaks that in three ways.
/// It reports a user cancel as a failure. It never calls back at all if the user opens the Yandex app
/// and walks away. And `authorize()` reuses a stored login right away, before the call returns.
public final class YandexAuthBridge: NSObject {

    public static let shared = YandexAuthBridge()

    /// The deep link with the token arrives after `willEnterForeground`, one runloop pass later. Fire
    /// the watchdog too early and it cancels a sign-in the user finished, throwing the token away.
    private static let watchdogGrace: TimeInterval = 1.5

    /// The switch to the Yandex app happens right after `authorize()`. A user going to read a 2FA code
    /// takes longer than this.
    private static let handoffWindow: TimeInterval = 2.0

    /// `didFinishLogin` does not come on the main thread, but `signIn` and the notifications do. So
    /// everything mutable below is shared state and takes this lock.
    private let lock = NSLock()
    private var pending: ((YandexAuthBridgeOutcome) -> Void)?
    private var authorizeStartedAt: Date?
    private var leftForegroundForHandoff = false
    private var watchdog: DispatchWorkItem?

    private override init() {
        super.init()
    }

    // MARK: - Setup

    /// Starts the SDK. Call once at launch, before anything can ask for a token.
    ///
    /// Returns `false` if the client id is wrong or `Info.plist` has no `yx<clientid>` URL scheme. It
    /// does not throw: a bad `Info.plist` should not crash the app at launch.
    @discardableResult
    public func install(clientID: String) -> Bool {
        do {
            try YandexLoginSDK.shared.activate(with: clientID)
        } catch {
            NSLog("Yandex ID: activation failed, sign-in will be unavailable: \(error)")
            return false
        }

        YandexLoginSDK.shared.add(observer: self)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        return true
    }

    /// The OAuth redirect comes back either as a custom scheme or, if the Yandex app handled the
    /// sign-in, as a universal link. Wire both: `onOpenURL` and `onContinueUserActivity`.
    public func handle(url: URL) {
        _ = YandexLoginSDK.shared.tryHandleOpenURL(url)
    }

    public func handle(userActivity: NSUserActivity) {
        _ = YandexLoginSDK.shared.tryHandleUserActivity(userActivity)
    }

    // MARK: - Sign-in

    /// Starts a sign-in and calls back once. A second call cancels the first one instead of leaving it
    /// hanging.
    public func signIn(onResult: @escaping (YandexAuthBridgeOutcome) -> Void) {
        guard let presenter = Self.topViewController() else {
            onResult(.failed("No view controller to present from"))
            return
        }

        lock.lock()
        let previous = pending
        pending = onResult
        authorizeStartedAt = Date()
        leftForegroundForHandoff = false
        let armed = watchdog
        watchdog = nil
        lock.unlock()

        armed?.cancel()
        previous?(.cancelled)

        do {
            // The callback must already be set. If a login is stored, `authorize` notifies the observer
            // right away, before this call returns.
            try YandexLoginSDK.shared.authorize(with: presenter)
        } catch {
            // Nothing will call back now. Without this the screen would spin forever.
            deliver(.failed("\(error)"))
        }
    }

    /// Without this the SDK reuses the stored login on the next `authorize()` and signs the user back
    /// into the account they just left, with no account picker.
    public func signOut() {
        try? YandexLoginSDK.shared.logout()
    }

    // MARK: - Delivery

    /// Takes the callback before calling it. If nobody is waiting, the result is dropped instead of
    /// delivered twice. That happens on a cold launch through the universal link, or after the watchdog
    /// already answered.
    private func deliver(_ outcome: YandexAuthBridgeOutcome) {
        lock.lock()
        let callback = pending
        pending = nil
        authorizeStartedAt = nil
        leftForegroundForHandoff = false
        let armed = watchdog
        watchdog = nil
        lock.unlock()

        armed?.cancel()
        callback?(outcome)
    }

    // MARK: - Watchdog: only when the app really left for the Yandex app

    @objc private func appDidEnterBackground() {
        lock.lock()
        defer { lock.unlock() }

        guard pending != nil, let startedAt = authorizeStartedAt else { return }

        // Two things mean the SDK switched to the Yandex app. The app went to background right after
        // `authorize()`, and no sheet of ours is on screen, because an in-app
        // `ASWebAuthenticationSession` proves we never left. Without both checks, backgrounding the app
        // while the sheet is open would arm a watchdog that cancels a login the user comes back to
        // finish.
        //
        // This asks the root controller what it presents, not `topViewController()`, which already
        // walked to the top and presents nothing by definition.
        let immediate = Date().timeIntervalSince(startedAt) < Self.handoffWindow
        let noSheetOfOurs = Self.rootViewController()?.presentedViewController == nil

        leftForegroundForHandoff = immediate && noSheetOfOurs
    }

    @objc private func appWillEnterForeground() {
        lock.lock()
        guard pending != nil, leftForegroundForHandoff else {
            lock.unlock()
            return
        }

        // Not a zero-delay `async`: the deep link comes from another runloop source and arrives after
        // this notification, so an immediate check would race the real token.
        let work = DispatchWorkItem { [weak self] in
            self?.deliver(.cancelled)
        }
        watchdog = work
        lock.unlock()

        DispatchQueue.main.asyncAfter(deadline: .now() + Self.watchdogGrace, execute: work)
    }

    // MARK: - Presenter

    /// Looked up when needed instead of stored. Keeping the view controller could create a retain cycle
    /// back through whatever owns the sign-in call and hold a whole screen in memory.
    private static func rootViewController() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?
            .windows.first(where: \.isKeyWindow)?
            .rootViewController
    }

    /// Presenting from a controller that is already presenting something fails, so walk up to the top.
    private static func topViewController() -> UIViewController? {
        var top = rootViewController()
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}

// MARK: - YandexLoginSDKObserver

extension YandexAuthBridge: YandexLoginSDKObserver {

    public func didFinishLogin(with result: Result<LoginResult, any Error>) {
        switch result {
        case .success(let login):
            deliver(.token(login.token))

        case .failure(let error):
            deliver(outcomeFor(error))
        }
    }

    /// The SDK reports a user cancel as a failure, so we have to tell the two apart.
    ///
    /// `ASWebAuthenticationSession` says it plainly through a public error. Closing an
    /// `SFSafariViewController` does not: that error is an internal SDK type we cannot match on. So we
    /// use the flow instead. If the app never left for the Yandex app, our own sheet was on screen, and
    /// the only way it closes without a token is the user closing it.
    ///
    /// The trade-off: an SDK or network failure inside the app also reads as a cancel, so the screen
    /// just goes idle. That is better than showing an error to someone who pressed "Cancel".
    private func outcomeFor(_ error: any Error) -> YandexAuthBridgeOutcome {
        let nsError = error as NSError
        let webCancel = nsError.domain == ASWebAuthenticationSessionErrorDomain
            && nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue

        lock.lock()
        let handedOff = leftForegroundForHandoff
        lock.unlock()

        if webCancel || !handedOff {
            return .cancelled
        }
        return .failed((error as? YandexLoginSDKError)?.message ?? "\(error)")
    }
}
