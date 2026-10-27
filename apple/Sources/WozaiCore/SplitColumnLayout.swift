import Foundation

/// Widths are measured in the current scene's content coordinates, never device pixels.
public struct SplitColumnLayout: Sendable {
    public let total: Double
    public let minimum: Double
    public let maximum: Double
    public let initial: Double
    public let canResize: Bool

    public init(total: Double, minimum: Double, detailMinimum: Double, initial: Double) {
        self.total = total.isFinite ? max(0, total) : 0
        self.minimum = max(0, minimum)
        maximum = max(self.minimum, self.total - max(0, detailMinimum) - 1)
        canResize = self.total >= self.minimum + max(0, detailMinimum) + 1
        self.initial = min(maximum, max(self.minimum, initial))
    }

    public func width(fraction: Double) -> Double {
        clamp(fraction.isFinite && fraction > 0 ? total * fraction : initial)
    }

    public func clamp(_ proposed: Double) -> Double {
        min(maximum, max(minimum, proposed.isFinite ? proposed : initial))
    }

    public func snap(_ proposed: Double, latched: Double?) -> Double {
        let value = clamp(proposed)
        let anchors = [initial, total / 2].filter { $0 >= minimum && $0 <= maximum }
        let nearest = anchors.min { abs($0 - value) < abs($1 - value) }
        if let latched, anchors.contains(latched), abs(value - latched) <= 28 {
            // Nearby anchors must remain reachable even when their hysteresis bands overlap.
            if let nearest, abs(nearest - value) <= 16, abs(nearest - value) + 4 < abs(latched - value) { return nearest }
            return latched
        }
        if let nearest, abs(nearest - value) <= 16 { return nearest }
        return value
    }
}
