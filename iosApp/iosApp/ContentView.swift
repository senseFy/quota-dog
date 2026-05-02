import SwiftUI
import SafariServices
import Network
import ComposeApp

private func debugLog(_ message: String) {
#if DEBUG
    NSLog("QuotaDog \(message)")
#endif
}

struct ContentView: View {
    var body: some View {
        ComposeContainer()
            // Lets Compose draw under the status bar and home indicator.
            .ignoresSafeArea(.all)
    }
}

private struct ComposeContainer: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        NativeOAuthHandlerRegistry.registerIfNeeded()
        AppearanceHandlerRegistry.registerIfNeeded()
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

private enum NativeOAuthHandlerRegistry {
    private static let handler = NativeOAuthHandler()
    private static var registered = false

    static func registerIfNeeded() {
        guard !registered else { return }
        IosNativeOAuthBridge.shared.register(handler: handler)
        registered = true
    }
}

/// Bridges Compose dark/light state to UIKit by overriding the key window's user interface
/// style. This is what makes the system status bar icons (and the home indicator on devices
/// without a notch) flip colour with the in-app theme.
private final class AppearanceHandler: IosAppearanceHandler {
    func applyAppearance(dark: Bool) {
        DispatchQueue.main.async {
            let style: UIUserInterfaceStyle = dark ? .dark : .light
            for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
                for window in scene.windows {
                    window.overrideUserInterfaceStyle = style
                }
            }
        }
    }
}

private enum AppearanceHandlerRegistry {
    private static let handler = AppearanceHandler()
    private static var registered = false

    static func registerIfNeeded() {
        guard !registered else { return }
        IosAppearanceBridge.shared.register(handler: handler)
        registered = true
    }
}

private final class NativeOAuthHandler: NSObject, IosNativeOAuthHandler, SFSafariViewControllerDelegate {
    private let queue = DispatchQueue(label: "saien.quotadog.oauth.callback")
    private var listener: NWListener?
    private var completion: ((String?) -> Void)?
    private var safariViewController: SFSafariViewController?
    private var didPresentSafari = false
    private var currentSessionId = UUID()

    func openAuthorizationUrl(
        url: String,
        port: Int32,
        path: String,
        completion: @escaping (String?) -> Void
    ) -> Bool {
        guard let authURL = URL(string: url),
              let callbackPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            return false
        }

        resetAuthorizationState(notify: false)
        let sessionId = UUID()
        currentSessionId = sessionId
        self.completion = completion
        didPresentSafari = false

        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true
            let listener = try NWListener(using: parameters, on: callbackPort)
            self.listener = listener
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    self?.presentSafariOnce(url: authURL, sessionId: sessionId)
                case .failed(let error):
                    debugLog("native callback listener failed: \(String(describing: error))")
                    self?.finish(callbackURL: nil, sessionId: sessionId)
                case .cancelled:
                    break
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.handle(connection: connection, port: Int(port), path: path, sessionId: sessionId)
            }
            listener.start(queue: queue)
            return true
        } catch {
            debugLog("native callback listener start error: \(String(describing: error))")
            self.completion = nil
            return false
        }
    }

    func cancelAuthorization() {
        resetAuthorizationState(notify: false)
    }

    func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
        guard controller === safariViewController else { return }
        finish(callbackURL: nil, sessionId: currentSessionId)
    }

    private func presentSafariOnce(url: URL, sessionId: UUID) {
        queue.async { [weak self] in
            guard let self, self.currentSessionId == sessionId, !self.didPresentSafari else { return }
            self.didPresentSafari = true
            DispatchQueue.main.async {
                guard self.currentSessionId == sessionId else { return }
                guard let presenter = Self.topViewController() else {
                    debugLog("native auth missing presenter")
                    self.finish(callbackURL: nil, sessionId: sessionId)
                    return
                }
                let safari = SFSafariViewController(url: url)
                safari.delegate = self
                self.safariViewController = safari
                presenter.present(safari, animated: true)
            }
        }
    }

    private func handle(connection: NWConnection, port: Int, path: String, sessionId: UUID) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, _, _ in
            guard let self,
                  self.currentSessionId == sessionId,
                  let data,
                  let request = String(data: data, encoding: .utf8),
                  let requestLine = request.split(separator: "\r\n").first else {
                connection.cancel()
                return
            }

            let target = Self.requestTarget(from: String(requestLine))
            if target.hasPrefix(path) {
                let callbackURL = "http://localhost:\(port)\(target)"
                self.sendResponse(connection: connection, status: "200 OK", body: Self.successHTML(port: port)) {
                    self.finish(callbackURL: callbackURL, sessionId: sessionId)
                }
            } else if target == "/close" {
                self.sendResponse(connection: connection, status: "200 OK", body: Self.closedHTML) {
                    self.closeSafari(sessionId: sessionId)
                }
            } else {
                debugLog("native auth ignored request target")
                self.sendResponse(connection: connection, status: "404 Not Found", body: "Not found") {
                    connection.cancel()
                }
            }
        }
    }

    private func sendResponse(
        connection: NWConnection,
        status: String,
        body: String,
        completion: @escaping () -> Void
    ) {
        let bodyData = Data(body.utf8)
        var header = ""
        header += "HTTP/1.1 \(status)\r\n"
        header += "Content-Type: text/html; charset=utf-8\r\n"
        header += "Content-Length: \(bodyData.count)\r\n"
        header += "Connection: close\r\n"
        header += "\r\n"
        var response = Data(header.utf8)
        response.append(bodyData)
        connection.send(content: response, completion: .contentProcessed { _ in
            connection.cancel()
            completion()
        })
    }

    private func finish(callbackURL: String?, sessionId: UUID) {
        queue.async { [weak self] in
            guard let self, self.currentSessionId == sessionId else { return }
            let completion = self.completion
            self.completion = nil
            if callbackURL == nil {
                self.listener?.cancel()
                self.listener = nil
                self.didPresentSafari = false
                self.currentSessionId = UUID()
                let safari = self.safariViewController
                self.safariViewController = nil
                DispatchQueue.main.async {
                    safari?.dismiss(animated: true)
                    completion?(nil)
                }
            } else {
                // Keep the listener alive so the success page's /close button can dismiss Safari.
                self.didPresentSafari = false
                DispatchQueue.main.async {
                    completion?(callbackURL)
                }
            }
        }
    }

    private func closeSafari(sessionId: UUID) {
        queue.async { [weak self] in
            guard let self, self.currentSessionId == sessionId else { return }
            self.listener?.cancel()
            self.listener = nil
            self.didPresentSafari = false
            self.currentSessionId = UUID()
            let safari = self.safariViewController
            self.safariViewController = nil
            DispatchQueue.main.async {
                safari?.dismiss(animated: true)
            }
        }
    }

    private func resetAuthorizationState(notify: Bool) {
        let completion = self.completion
        self.completion = nil
        self.listener?.cancel()
        self.listener = nil
        self.didPresentSafari = false
        self.currentSessionId = UUID()
        let safari = self.safariViewController
        self.safariViewController = nil
        DispatchQueue.main.async {
            safari?.dismiss(animated: true)
            if notify {
                completion?(nil)
            }
        }
    }

    private static func requestTarget(from requestLine: String) -> String {
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return "" }
        return String(parts[1])
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        var top = window?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }

    private static func successHTML(port: Int) -> String {
        """
    <html>
      <body style="font-family: -apple-system, BlinkMacSystemFont, sans-serif; padding: 24px; line-height: 1.5;">
        <h2>QuotaDog</h2>
        <p>Sign-in complete. You can return to the app.</p>
        <p><a href="http://localhost:\(port)/close" style="display: inline-block; margin-top: 12px; padding: 10px 16px; border-radius: 10px; background: #111; color: white; text-decoration: none;">Close</a></p>
      </body>
    </html>
    """
    }

    private static let closedHTML = """
    <html>
      <body style="font-family: -apple-system, BlinkMacSystemFont, sans-serif; padding: 24px;">
        <p>You can return to the app.</p>
      </body>
    </html>
    """
}
