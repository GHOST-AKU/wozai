// swift-tools-version: 5.9
import PackageDescription
import Foundation

let meshBuilt = FileManager.default.fileExists(atPath: URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().appendingPathComponent("MeshCore.xcframework").path)

let package = Package(
    name: "WozaiApple",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "WozaiCore", targets: ["WozaiCore"]),
        .library(name: "WozaiMesh", targets: ["WozaiMesh"]),
        .executable(name: "WozaiInterop", targets: ["WozaiInterop"])
    ],
    targets: [
        .target(name: "WozaiCore"),
        .target(name: "CMeshBridge", dependencies: meshBuilt ? ["MeshRust"] : [],
                linkerSettings: [.linkedFramework("Security"), .linkedFramework("CoreFoundation")]),
        .target(name: "WozaiMesh", dependencies: ["CMeshBridge"]),
        .testTarget(name: "WozaiMeshTests", dependencies: ["WozaiMesh"]),
        .executableTarget(name: "WozaiInterop", dependencies: ["WozaiCore"], path: "Tests/Interop"),
        .testTarget(name: "WozaiCoreTests", dependencies: ["WozaiCore"])
    ] + (meshBuilt ? [.binaryTarget(name: "MeshRust", path: "MeshCore.xcframework")] : [])
)
