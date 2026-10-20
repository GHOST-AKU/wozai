// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WozaiApple",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "WozaiCore", targets: ["WozaiCore"])],
    targets: [
        .target(name: "WozaiCore"),
        .testTarget(name: "WozaiCoreTests", dependencies: ["WozaiCore"])
    ]
)
