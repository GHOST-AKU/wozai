// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WozaiApple",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "WozaiCore", targets: ["WozaiCore"]),
        .executable(name: "WozaiInterop", targets: ["WozaiInterop"])
    ],
    targets: [
        .target(name: "WozaiCore"),
        .executableTarget(name: "WozaiInterop", dependencies: ["WozaiCore"], path: "Tests/Interop"),
        .testTarget(name: "WozaiCoreTests", dependencies: ["WozaiCore"])
    ]
)
