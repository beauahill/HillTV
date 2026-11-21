// swift-tools-version: 5.10
// HillTVCore holds everything that does not need UIKit/SwiftUI: the Xtream
// client, lenient models, URL building, M3U parsing. It builds on macOS and
// Linux too, so `swift test` runs without a tvOS simulator.
import PackageDescription

let package = Package(
    name: "HillTVCore",
    platforms: [
        .tvOS(.v17),
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "HillTVCore", targets: ["HillTVCore"]),
    ],
    targets: [
        .target(name: "HillTVCore"),
        .testTarget(name: "HillTVCoreTests", dependencies: ["HillTVCore"]),
    ]
)
