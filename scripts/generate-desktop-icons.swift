#!/usr/bin/env swift
//
// Generates the desktop app icon assets from one vector description:
//
//   composeApp/icons/QuotaDog.png        512px, used by -Xdock:icon and Linux
//   composeApp/icons/QuotaDog.icns       macOS bundle icon
//   composeApp/icons/QuotaDog.ico        Windows installer / shortcut icon
//   composeApp/src/commonMain/composeResources/drawable/quotadog_app_icon.png
//
// The tile follows Apple's macOS icon grid (824x824 body on a 1024 canvas) and
// the moon matches the menu-bar glyph: 80% lit on the left, gray on the right.
//
// Usage: swift scripts/generate-desktop-icons.swift
//
// Requires macOS 13+ (SwiftUI ImageRenderer) and Xcode command line tools.

import AppKit
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

private let canvasSize: CGFloat = 1024
private let tileSize: CGFloat = 824
private let cornerRadius: CGFloat = 185.4
private let moonScale: CGFloat = 0.68 // moon diameter / tile size
private let terminatorRatio: CGFloat = 0.6 // 0.6 => 80% illuminated
private let tileColor = Color(red: 245 / 255, green: 245 / 255, blue: 245 / 255)
private let moonGray = Color(red: 184 / 255, green: 184 / 255, blue: 184 / 255)
private let moonBlack = Color(red: 17 / 255, green: 17 / 255, blue: 17 / 255)

/// Left-lit gibbous moon: left semicircle closed by the terminator ellipse.
private struct MoonShape: Shape {
    let ratio: CGFloat

    func path(in rect: CGRect) -> Path {
        let radius = min(rect.width, rect.height) / 2
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let a = radius * ratio
        let k: CGFloat = 0.5522847498 // cubic approximation of a quarter ellipse

        var path = Path()
        path.move(to: CGPoint(x: center.x, y: center.y - radius))
        path.addArc(
            center: center,
            radius: radius,
            startAngle: .degrees(-90),
            endAngle: .degrees(90),
            clockwise: true
        )
        path.addCurve(
            to: CGPoint(x: center.x + a, y: center.y),
            control1: CGPoint(x: center.x + k * a, y: center.y + radius),
            control2: CGPoint(x: center.x + a, y: center.y + k * radius)
        )
        path.addCurve(
            to: CGPoint(x: center.x, y: center.y - radius),
            control1: CGPoint(x: center.x + a, y: center.y - k * radius),
            control2: CGPoint(x: center.x + k * a, y: center.y - radius)
        )
        path.closeSubpath()
        return path
    }
}

@MainActor
private func renderMaster() -> CGImage {
    let tile = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    let moonDiameter = tileSize * moonScale

    let content = ZStack {
        tile
            .fill(tileColor)
            .frame(width: tileSize, height: tileSize)
            .shadow(color: .black.opacity(0.28), radius: 20, x: 0, y: 10)

        ZStack {
            Circle()
                .fill(moonGray)
                .frame(width: moonDiameter, height: moonDiameter)
            MoonShape(ratio: terminatorRatio)
                .fill(moonBlack)
                .frame(width: moonDiameter, height: moonDiameter)
        }
        .frame(width: tileSize, height: tileSize)
        .clipShape(tile)
    }
    .frame(width: canvasSize, height: canvasSize)

    let renderer = ImageRenderer(content: content)
    renderer.scale = 1
    renderer.isOpaque = false
    guard let image = renderer.cgImage else {
        fatalError("ImageRenderer returned no image")
    }
    return image
}

private func resize(_ image: CGImage, to size: Int) -> CGImage {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    guard let context = CGContext(
        data: nil,
        width: size,
        height: size,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: space,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else {
        fatalError("Could not create \(size)px context")
    }
    context.interpolationQuality = .high
    context.draw(image, in: CGRect(x: 0, y: 0, width: size, height: size))
    guard let resized = context.makeImage() else {
        fatalError("Could not resize icon to \(size)px")
    }
    return resized
}

private func pngData(_ image: CGImage) -> Data {
    let data = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(
        data as CFMutableData,
        UTType.png.identifier as CFString,
        1,
        nil
    ) else {
        fatalError("Could not create PNG destination")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        fatalError("Could not encode PNG")
    }
    return data as Data
}

private func writePNG(_ image: CGImage, to url: URL) {
    try! pngData(image).write(to: url)
}

private func writeICNS(_ master: CGImage, to url: URL) {
    let iconset = FileManager.default.temporaryDirectory
        .appendingPathComponent("QuotaDog-\(UUID().uuidString).iconset")
    try! FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: iconset) }

    let entries: [(String, Int)] = [
        ("icon_16x16.png", 16),
        ("icon_16x16@2x.png", 32),
        ("icon_32x32.png", 32),
        ("icon_32x32@2x.png", 64),
        ("icon_128x128.png", 128),
        ("icon_128x128@2x.png", 256),
        ("icon_256x256.png", 256),
        ("icon_256x256@2x.png", 512),
        ("icon_512x512.png", 512),
        ("icon_512x512@2x.png", 1024),
    ]
    for (name, size) in entries {
        writePNG(resize(master, to: size), to: iconset.appendingPathComponent(name))
    }

    let iconutil = Process()
    iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
    iconutil.arguments = ["-c", "icns", iconset.path, "-o", url.path]
    try! iconutil.run()
    iconutil.waitUntilExit()
    guard iconutil.terminationStatus == 0 else {
        fatalError("iconutil failed with status \(iconutil.terminationStatus)")
    }
}

private func writeICO(_ master: CGImage, to url: URL) {
    let images = [16, 32, 48, 64, 128, 256].map { ($0, pngData(resize(master, to: $0))) }

    var data = Data()
    data.append(contentsOf: [0, 0]) // reserved
    data.append(contentsOf: [1, 0]) // type: icon
    data.append(contentsOf: [UInt8(images.count & 0xff), UInt8((images.count >> 8) & 0xff)])

    var offset = 6 + 16 * images.count
    for (size, png) in images {
        let dimension = UInt8(size >= 256 ? 0 : size)
        data.append(contentsOf: [dimension, dimension, 0, 0])
        data.append(contentsOf: [1, 0]) // color planes
        data.append(contentsOf: [32, 0]) // bits per pixel
        withUnsafeBytes(of: UInt32(png.count).littleEndian) { data.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt32(offset).littleEndian) { data.append(contentsOf: $0) }
        offset += png.count
    }
    for (_, png) in images {
        data.append(png)
    }
    try! data.write(to: url)
}

private func write(_ image: CGImage, size: Int, to url: URL) {
    writePNG(resize(image, to: size), to: url)
}

let scriptURL = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
let root = scriptURL.deletingLastPathComponent().deletingLastPathComponent()
let icons = root.appendingPathComponent("composeApp/icons")
let drawable = root.appendingPathComponent(
    "composeApp/src/commonMain/composeResources/drawable"
)

MainActor.assumeIsolated {
    let master = renderMaster()
    write(master, size: 512, to: icons.appendingPathComponent("QuotaDog.png"))
    writeICNS(master, to: icons.appendingPathComponent("QuotaDog.icns"))
    writeICO(master, to: icons.appendingPathComponent("QuotaDog.ico"))
    write(master, size: 512, to: drawable.appendingPathComponent("quotadog_app_icon.png"))
    print("Regenerated QuotaDog.png, QuotaDog.icns, QuotaDog.ico, quotadog_app_icon.png")
}
