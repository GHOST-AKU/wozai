import CoreImage
import Darwin
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Vision

/// Offline verification only: QR content never supplies an address or a new identity.
public enum VerificationQR {
    public static func payload(local: Data, remote: Data) throws -> String {
        "WOZAI-ID1:" + (try SafetyCode.make(local, remote))
    }
    public static func matches(_ value: String, local: Data, remote: Data) throws -> Bool {
        let expected = Array(try payload(local: local, remote: remote).utf8), actual = Array(value.utf8)
        guard actual.count == expected.count else { return false }
        return zip(actual, expected).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
    public static func png(local: Data, remote: Data) throws -> Data {
        let text = try payload(local: local, remote: remote)
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { throw WozaiError.storage }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let symbol = filter.outputImage else { throw WozaiError.storage }
        let scaled = symbol.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let bounds = scaled.extent.insetBy(dx: -32, dy: -32)
        let white = CIImage(color: CIColor(red: 1, green: 1, blue: 1)).cropped(to: bounds)
        guard let image = CIContext().createCGImage(scaled.composited(over: white), from: bounds) else { throw WozaiError.storage }
        let bytes = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(bytes, UTType.png.identifier as CFString, 1, nil) else { throw WozaiError.storage }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw WozaiError.storage }
        return bytes as Data
    }
    public static func read(_ url: URL) throws -> [String] {
        let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw WozaiError.storage }
        var metadata = stat()
        guard Darwin.fstat(fd, &metadata) == 0, metadata.st_mode & S_IFMT == S_IFREG, metadata.st_size >= 0,
              metadata.st_size <= 16 * 1024 * 1024 else { Darwin.close(fd); throw WozaiError.storage }
        let file = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? file.close() }
        let data = try file.read(upToCount: 16 * 1024 * 1024 + 1) ?? Data()
        return try read(data)
    }
    public static func read(_ data: Data) throws -> [String] {
        guard !data.isEmpty, data.count <= 16 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 32768, height <= 32768, Int64(width) * Int64(height) <= 100_000_000,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 2048] as CFDictionary) else { throw WozaiError.storage }
        let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        return Array(Set((request.results ?? []).compactMap(\.payloadStringValue).filter { $0.utf8.count <= 128 })).sorted()
    }
}
