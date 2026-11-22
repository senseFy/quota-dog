import Foundation
import Vision

// AXe sees the app underneath the out-of-process Photos picker. Locate only the
// thumbnail matching this invitation; never print or persist either QR payload.
func barcodes(_ data: Data) throws -> [VNBarcodeObservation] {
    let request = VNDetectBarcodesRequest()
    request.symbologies = [.qr]
    try VNImageRequestHandler(data: data).perform([request])
    return request.results ?? []
}

let image = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
guard let target = try barcodes(image).first?.payloadStringValue else { exit(2) }
let screen = FileHandle.standardInput.readDataToEndOfFile()
guard let qr = try barcodes(screen).first(where: { $0.payloadStringValue == target }) else { exit(1) }
print(qr.boundingBox.midX, 1 - qr.boundingBox.midY)
