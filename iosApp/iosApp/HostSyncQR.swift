import AVFoundation
import ImageIO
import PhotosUI
import UIKit
import UniformTypeIdentifiers
import Vision

/// Owns exactly one selection, from requesting permission through dismissing the native UI.
@MainActor final class HostSyncQR: NSObject, PHPickerViewControllerDelegate, UIAdaptivePresentationControllerDelegate {
    private var completion: ((String?) -> Void)?
    private weak var presenter: UIViewController?
    private var presented: UIViewController?
    private var scanner: QRScanner?
    private var imageLoad: Progress?

    init(completion: @escaping (String?) -> Void) { self.completion = completion }

    func start(image: Bool, from presenter: UIViewController) {
        self.presenter = presenter
        guard canPresent else { cancel(); return }
        NotificationCenter.default.addObserver(self, selector: #selector(enteredBackground(_:)),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        if image {
            // The system picker grants access only to the selected image; no Photos permission.
            var config = PHPickerConfiguration()
            config.filter = .images
            config.selectionLimit = 1
            config.preferredAssetRepresentationMode = .current
            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self
            present(picker)
            return
        }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: showScanner()
        case .notDetermined:
            guard AVCaptureDevice.default(for: .video) != nil else { cancel(); return }
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted { self?.showScanner() } else { self?.cancel() }
                }
            }
        default: cancel()
        }
    }

    private var canPresent: Bool {
        guard completion != nil, let presenter, presenter.viewIfLoaded?.window != nil else { return false }
        return UIApplication.shared.applicationState != .background &&
            !presenter.isBeingDismissed && presenter.presentedViewController == nil
    }

    private func showScanner() {
        guard canPresent, let camera = AVCaptureDevice.default(for: .video) else { cancel(); return }
        let scanner = QRScanner(camera: camera) { [weak self] value in self?.finish(value) }
        self.scanner = scanner
        let nav = UINavigationController(rootViewController: scanner)
        nav.modalPresentationStyle = .fullScreen
        present(nav)
    }

    private func present(_ controller: UIViewController) {
        presented = controller
        presenter?.present(controller, animated: true) { [weak self, weak controller] in
            // Backgrounding or teardown can win even during UIKit's presentation animation.
            if self?.completion == nil { controller?.dismiss(animated: false) }
        }
        controller.presentationController?.delegate = self
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard completion != nil, picker === presented else { return }
        guard let provider = results.first?.itemProvider,
              let type = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) == true }) else {
            cancel(); return
        }
        guard imageLoad == nil else { return }
        imageLoad = provider.loadFileRepresentation(forTypeIdentifier: type) { [weak self] url, error in
            // NSItemProvider calls on its internal queue and deletes the URL when this block returns.
            let value = autoreleasepool { error == nil ? url.flatMap(readHostQR) : nil }
            DispatchQueue.main.async { self?.finish(value) }
        }
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { cancel() }
    @objc private func enteredBackground(_ notification: Notification) { cancel() }
    func cancel() { finish(nil) }

    private func finish(_ value: String?) {
        guard let completion else { return }
        self.completion = nil
        NotificationCenter.default.removeObserver(self)
        imageLoad?.cancel()
        imageLoad = nil
        scanner?.stop()
        scanner = nil
        if let presented, presented.presentingViewController != nil, !presented.isBeingDismissed {
            presented.dismiss(animated: false)
        }
        presented = nil
        completion(value)
    }
}

/// Read just the first frame. Limit encoded bytes and declared dimensions before decoding,
/// then give Vision an oriented thumbnail, never a full-resolution UIImage.
private func readHostQR(_ url: URL) -> String? {
    guard url.isFileURL, let info = try? FileManager.default.attributesOfItem(atPath: url.path),
          info[.type] as? FileAttributeType == .typeRegular, let bytes = info[.size] as? Int,
          (1...16 * 1024 * 1024).contains(bytes), let file = try? FileHandle(forReadingFrom: url) else { return nil }
    defer { try? file.close() }
    let options = [kCGImageSourceShouldCache: false] as CFDictionary
    guard let data = try? file.read(upToCount: bytes + 1), data.count == bytes,
          let source = CGImageSourceCreateWithData(data as CFData, options),
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any],
          let width = properties[kCGImagePropertyPixelWidth] as? Int,
          let height = properties[kCGImagePropertyPixelHeight] as? Int,
          (1...16_384).contains(width), (1...16_384).contains(height), width * height <= 64_000_000 else { return nil }
    let thumbnail: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: 2048, kCGImageSourceShouldCacheImmediately: true
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnail as CFDictionary) else { return nil }
    // QR needs only revision 1. Later revisions require a Vision inference context
    // that is not available on every supported runtime.
    let request = VNDetectBarcodesRequest()
    request.revision = VNDetectBarcodesRequestRevision1
    request.symbologies = [.qr]
    do {
        try VNImageRequestHandler(cgImage: image).perform([request])
        return request.results?.compactMap(\.payloadStringValue).first { !$0.isEmpty }
    } catch { return nil }
}

private final class QRScanner: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let camera: AVCaptureDevice
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "saien.quotadog.host-sync.camera")
    private lazy var preview = AVCaptureVideoPreviewLayer(session: session)
    private var onResult: ((String?) -> Void)?

    init(camera: AVCaptureDevice, onResult: @escaping (String?) -> Void) {
        self.camera = camera
        self.onResult = onResult
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        guard onResult != nil else { return } // A teardown may have cancelled before UIKit loaded the view.
        title = "Scan pairing QR code"
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self,
                                                            action: #selector(cancel))
        view.backgroundColor = .black
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        for name in [AVCaptureSession.runtimeErrorNotification, AVCaptureSession.wasInterruptedNotification] {
            NotificationCenter.default.addObserver(self, selector: #selector(sessionFailed(_:)), name: name, object: session)
        }
        queue.async { [weak self, session, camera] in
            guard let self else { return }
            if Self.configure(session, camera: camera, delegate: self) {
                session.startRunning()
                if session.isRunning { return }
            }
            self.sessionFailed()
        }
    }

    private nonisolated static func configure(_ session: AVCaptureSession, camera: AVCaptureDevice,
                                               delegate: AVCaptureMetadataOutputObjectsDelegate) -> Bool {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        guard let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else { return false }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return false }
        session.addOutput(output)
        guard output.availableMetadataObjectTypes.contains(.qr) else { return false }
        output.setMetadataObjectsDelegate(delegate, queue: .main)
        output.metadataObjectTypes = [.qr]
        return true
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview.frame = view.bounds
        guard let connection = preview.connection, connection.isVideoOrientationSupported,
              let orientation = view.window?.windowScene?.interfaceOrientation else { return }
        switch orientation {
        case .portrait: connection.videoOrientation = .portrait
        case .portraitUpsideDown: connection.videoOrientation = .portraitUpsideDown
        case .landscapeLeft: connection.videoOrientation = .landscapeLeft
        case .landscapeRight: connection.videoOrientation = .landscapeRight
        default: break
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        cancel()
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        for case let code as AVMetadataMachineReadableCodeObject in objects where code.type == .qr {
            if let value = code.stringValue, !value.isEmpty { onResult?(value); return }
        }
    }

    @objc private func cancel() { onResult?(nil) }
    @objc private nonisolated func sessionFailed(_ notification: Notification? = nil) {
        DispatchQueue.main.async { [weak self] in self?.cancel() }
    }

    func stop() {
        onResult = nil
        NotificationCenter.default.removeObserver(self)
        queue.async { [session] in
            for case let output as AVCaptureMetadataOutput in session.outputs {
                output.setMetadataObjectsDelegate(nil, queue: nil)
            }
            session.stopRunning() // Also cancels the restart request of an interrupted session.
        }
    }
}
