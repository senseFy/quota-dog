import ComposeApp
import CryptoKit
import Network
import Security
import UIKit

/// One coordinator per Compose controller. Kotlin may call the synchronous Keychain methods
/// from a worker, including while TLS is requesting a signature.
final class HostSyncPlatform: NSObject, IosPhonePlatform {
    @MainActor weak var presenter: UIViewController?
    @MainActor private var selection: HostSyncQR?
    @MainActor private lazy var network = HostSyncNetwork()
    @MainActor private var detached = false

    func identitySpki() -> Data? {
        HostSyncKeychain.queue.sync {
            guard let key = try? HostSyncKeychain.identity(createIfMissing: true),
                  let publicKey = SecKeyCopyPublicKey(key),
                  let point = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else { return nil }
            return (try? P256.Signing.PublicKey(x963Representation: point))?.derRepresentation
        }
    }

    func signature(message: Data) -> Data? {
        HostSyncKeychain.queue.sync {
            guard let key = try? HostSyncKeychain.identity(createIfMissing: false) else { return nil }
            // Message (not Digest): Security hashes exactly once and returns an X9.62 DER signature.
            return SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, message as CFData, nil) as Data?
        }
    }

    func readPairing() throws -> String {
        try HostSyncKeychain.queue.sync { try HostSyncKeychain.readPairing() ?? "" }
    }

    func writePairing(value: String?) -> Bool {
        HostSyncKeychain.queue.sync { HostSyncKeychain.writePairing(value) }
    }

    func selectQr(image: Bool, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async { [self] in
            guard self.selection == nil, let presenter = self.presenter else { completion(nil); return }
            let request = HostSyncQR { [weak self] result in
                self?.selection = nil
                completion(result)
            }
            self.selection = request
            request.start(image: image, from: presenter)
        }
    }

    func watchNetwork(listener: IosSyncNetworkListener) {
        DispatchQueue.main.async {
            guard !self.detached else { return }
            self.network.start(listener)
        }
    }

    func stopWatchingNetwork() {
        DispatchQueue.main.async { self.network.stop() }
    }

    @MainActor func detach() {
        detached = true
        presenter = nil
        network.stop()
        selection?.cancel()
    }
}

private enum HostSyncKeychain {
    static let queue = DispatchQueue(label: "saien.quotadog.host-sync.keychain")
    private static let service = "saien.quotadog.host-sync.phone.v1"
    private static let keyTag = Data("\(service).identity".utf8)
    private static let pairing: [CFString: Any] = [
        kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: "pairing",
        kSecAttrSynchronizable: false
    ]

    static func readPairing() throws -> String? {
        var query = pairing
        query[kSecReturnData] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw error(status) }
        guard let data = result as? Data, !data.isEmpty, let value = String(data: data, encoding: .utf8) else {
            throw error(errSecDecode)
        }
        return value
    }

    static func writePairing(_ value: String?) -> Bool {
        guard let value else {
            let status = SecItemDelete(pairing as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        guard !value.isEmpty else { return false } // Reserved for a missing record at the Kotlin boundary.
        let data = Data(value.utf8)
        let status = SecItemUpdate(pairing as CFDictionary, [kSecValueData: data] as CFDictionary)
        guard status == errSecItemNotFound else { return status == errSecSuccess }
        var item = pairing
        item[kSecValueData] = data
        item[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    static func identity(createIfMissing: Bool) throws -> SecKey? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassKey, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrApplicationTag: keyTag,
            kSecReturnRef: true
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess {
            guard let result, CFGetTypeID(result) == SecKeyGetTypeID() else { throw error(errSecDecode) }
            return (result as! SecKey)
        }
        guard status == errSecItemNotFound else { throw error(status) }
        // Never mint a new identity to sign for an existing pairing, or when its state can't be read.
        guard createIfMissing, try readPairing() == nil else { return nil }

        var privateAttrs: [CFString: Any] = [kSecAttrIsPermanent: true, kSecAttrApplicationTag: keyTag]
        var attrs: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits: 256]
#if targetEnvironment(simulator)
        privateAttrs[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
#else
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                                                           .privateKeyUsage, nil) else { return nil }
        privateAttrs[kSecAttrAccessControl] = access
        attrs[kSecAttrTokenID] = kSecAttrTokenIDSecureEnclave
#endif
        attrs[kSecPrivateKeyAttrs] = privateAttrs
        return SecKeyCreateRandomKey(attrs as CFDictionary, nil)
    }

    private static func error(_ status: OSStatus) -> NSError {
        NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
}

/// Opaque generations, not socket handles. Restart the monitor after backgrounding so an old
/// satisfied path is never replayed on wake. NWPath equality compares path contents.
@MainActor private final class HostSyncNetwork: NSObject {
    private var listener: IosSyncNetworkListener?
    private var monitor: NWPathMonitor?
    private var lastPath: NWPath?
    private var reported = false
    private var generation: Int64 = 0

    func start(_ listener: IosSyncNetworkListener) {
        guard self.listener !== listener else { return }
        stop()
        self.listener = listener
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(foreground(_:)), name: UIApplication.didBecomeActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(background(_:)), name: UIApplication.didEnterBackgroundNotification, object: nil)
        if UIApplication.shared.applicationState == .background { background() } else { foreground() }
    }

    @objc private func foreground(_ notification: Notification? = nil) {
        guard monitor == nil, listener != nil else { return }
        let monitor = NWPathMonitor()
        self.monitor = monitor
        monitor.pathUpdateHandler = { [weak self, weak monitor] path in
            DispatchQueue.main.async {
                guard let self, let monitor, self.monitor === monitor else { return }
                self.report(path.status == .satisfied ? path : nil)
            }
        }
        monitor.start(queue: .main)
    }

    @objc private func background(_ notification: Notification? = nil) {
        monitor?.cancel()
        monitor = nil
        report(nil)
    }

    private func report(_ path: NWPath?) {
        guard !reported || lastPath != path else { return }
        reported = true
        lastPath = path
        if path != nil { generation += 1 }
        listener?.networkChanged(handle: path == nil ? 0 : generation)
    }

    func stop() {
        NotificationCenter.default.removeObserver(self)
        monitor?.cancel()
        monitor = nil
        listener = nil
        lastPath = nil
        reported = false
    }
}
