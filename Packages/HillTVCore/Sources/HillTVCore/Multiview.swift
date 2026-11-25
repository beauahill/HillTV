import Foundation

/// Rules for watching several live channels at once.
public enum Multiview {
    /// Most channels on screen together.
    public static let mostTiles = 4

    /// How many channels may play together on this account. Every tile is
    /// its own connection to the provider, so the account's limit is the
    /// real ceiling. A panel that doesn't say gets the benefit of the doubt:
    /// a tile it refuses shows an error instead.
    public static func tileLimit(maxConnections: Int?) -> Int {
        guard let maxConnections else { return mostTiles }
        return min(mostTiles, max(1, maxConnections))
    }

    /// Multiview needs room for at least two channels.
    public static func isAvailable(maxConnections: Int?) -> Bool {
        tileLimit(maxConnections: maxConnections) >= 2
    }

    /// Columns and rows for a number of cells: one, two side by side, or a
    /// two by two grid.
    public static func grid(for cells: Int) -> (columns: Int, rows: Int) {
        switch cells {
        case ...1: return (1, 1)
        case 2: return (2, 1)
        default: return (2, 2)
        }
    }

    public struct Frame: Equatable, Sendable {
        public let x: Double
        public let y: Double
        public let width: Double
        public let height: Double
    }

    /// Where each cell goes: a 16:9 box centered in its part of the grid,
    /// in reading order.
    public static func frames(cells: Int, width: Double, height: Double, spacing: Double) -> [Frame] {
        guard cells > 0, width > 0, height > 0 else { return [] }
        let grid = grid(for: cells)
        let cellWidth = (width - spacing * Double(grid.columns - 1)) / Double(grid.columns)
        let cellHeight = (height - spacing * Double(grid.rows - 1)) / Double(grid.rows)
        let boxWidth = min(cellWidth, cellHeight * 16 / 9)
        let boxHeight = boxWidth * 9 / 16
        return (0..<min(cells, grid.columns * grid.rows)).map { index in
            let column = index % grid.columns
            let row = index / grid.columns
            return Frame(
                x: Double(column) * (cellWidth + spacing) + (cellWidth - boxWidth) / 2,
                y: Double(row) * (cellHeight + spacing) + (cellHeight - boxHeight) / 2,
                width: boxWidth,
                height: boxHeight
            )
        }
    }

    /// The largest picture worth asking for with this many channels
    /// playing, as width and height. Nil means no limit: one channel, or a
    /// tile shown full screen.
    ///
    /// The player passes this on as a ceiling. It only changes anything when
    /// the provider's stream comes in more than one quality; a stream with
    /// a single quality plays as it is.
    public static func pictureCap(tiles: Int, fullScreen: Bool) -> (width: Int, height: Int)? {
        if fullScreen || tiles <= 1 { return nil }
        return tiles == 2 ? (1280, 720) : (960, 540)
    }
}

/// The channels in the multiview, in the order they were added.
public struct MultiviewTiles: Equatable, Sendable {
    public enum AddResult: Equatable, Sendable {
        case added
        case alreadyThere
        /// The account's connections are all in use.
        case full
    }

    public private(set) var streams: [LiveStream] = []

    public init() {}

    public var isEmpty: Bool { streams.isEmpty }

    public func contains(_ streamID: Int) -> Bool {
        streams.contains { $0.id == streamID }
    }

    @discardableResult
    public mutating func add(_ stream: LiveStream, limit: Int) -> AddResult {
        if contains(stream.id) { return .alreadyThere }
        guard streams.count < limit else { return .full }
        streams.append(stream)
        return .added
    }

    public mutating func remove(_ streamID: Int) {
        streams.removeAll { $0.id == streamID }
    }

    public mutating func removeAll() {
        streams = []
    }
}
