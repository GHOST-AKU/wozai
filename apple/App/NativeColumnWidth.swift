import SwiftUI
import OSLog

// SwiftUI column widths are preferences. Apply an explicit user resize to the existing
// native container as well, without replacing its delegate, controllers or navigation state.
#if os(macOS)
import AppKit

struct NativeColumnWidth: NSViewRepresentable {
    let width: CGFloat
    let minimum: CGFloat
    let maximum: CGFloat
    let enabled: Bool
    let measured: (CGFloat) -> Void

    func makeNSView(context: Context) -> ColumnWidthView { ColumnWidthView() }
    func updateNSView(_ view: ColumnWidthView, context: Context) {
        view.requested = enabled ? width : nil
        view.minimum = minimum; view.maximum = maximum
        view.measured = measured
        view.applyLater()
    }
    static func dismantleNSView(_ view: ColumnWidthView, coordinator: ()) { view.restore() }

    final class ColumnWidthView: NSView {
        var requested: CGFloat?
        var minimum: CGFloat = 0, maximum: CGFloat = .greatestFiniteMagnitude
        var measured: ((CGFloat) -> Void)?
        private weak var configuredItem: NSSplitViewItem?
        private var original: (CGFloat, CGFloat)?
        private var reportedMissing = false
        override func hitTest(_ point: NSPoint) -> NSView? { nil }
        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); applyLater() }

        func applyLater() {
            DispatchQueue.main.async { [weak self] in self?.apply() }
        }

        private func apply() {
            guard window != nil, let width = requested else { restore(); return }
            var ancestor = superview
            while let view = ancestor {
                if let split = view as? NSSplitView, split.isVertical, split.arrangedSubviews.count >= 2,
                   let primary = split.arrangedSubviews.first, isDescendant(of: primary) {
                    guard !split.isSubviewCollapsed(primary) else { return }
                    let visibleWidth = primary.frame.intersection(split.bounds).width
                    if abs(visibleWidth - width) <= 0.5 { measured?(visibleWidth); return }
                    if let controller = split.delegate as? NSSplitViewController, let item = controller.splitViewItems.first {
                        if configuredItem !== item {
                            restore(); configuredItem = item
                            original = (item.minimumThickness, item.maximumThickness)
                        }
                        // Thickness constraints include native material insets; do not lock them to
                        // the requested divider coordinate, which can add an invisible 8pt offset.
                        item.minimumThickness = min(item.minimumThickness, minimum)
                        item.maximumThickness = max(item.maximumThickness, maximum)
                        item.minimumThickness = minimum; item.maximumThickness = maximum
                    }
                    let leadingOnRight = primary.frame.minX > split.arrangedSubviews[1].frame.minX
                    split.setPosition(leadingOnRight ? split.bounds.maxX - width - split.dividerThickness : split.bounds.minX + width, ofDividerAt: 0)
                    measured?(primary.frame.intersection(split.bounds).width)
                    #if DEBUG
                    Logger(subsystem: "dev.ghost.wozai.apple", category: "Columns")
                        .debug("native columns=\(split.arrangedSubviews.count) requested=\(Double(width)) measured=\(Double(primary.frame.width))")
                    #endif
                    return
                }
                ancestor = view.superview
            }
            #if DEBUG
            if !reportedMissing {
                reportedMissing = true
                var names: [String] = [], node = superview
                while let view = node { names.append(String(describing: type(of: view))); node = view.superview }
                Logger(subsystem: "dev.ghost.wozai.apple", category: "Columns").debug("native split not found: \(names.joined(separator: "/"), privacy: .public)")
            }
            #endif
        }

        func restore() {
            if let item = configuredItem, let original {
                item.minimumThickness = min(item.minimumThickness, original.0)
                item.maximumThickness = max(item.maximumThickness, original.1)
                item.minimumThickness = original.0; item.maximumThickness = original.1
            }
            configuredItem = nil; original = nil
        }
    }
}
#else
import UIKit

struct NativeColumnWidth: UIViewRepresentable {
    let width: CGFloat
    let minimum: CGFloat
    let maximum: CGFloat
    let enabled: Bool
    let measured: (CGFloat) -> Void

    func makeUIView(context: Context) -> ColumnWidthView { ColumnWidthView() }
    func updateUIView(_ view: ColumnWidthView, context: Context) {
        view.requested = enabled ? width : nil
        view.minimum = minimum; view.maximum = maximum
        view.measured = measured
        view.applyLater()
    }
    static func dismantleUIView(_ view: ColumnWidthView, coordinator: ()) { view.restore() }

    final class ColumnWidthView: UIView {
        var requested: CGFloat?
        var minimum: CGFloat = 0, maximum: CGFloat = .greatestFiniteMagnitude
        var measured: ((CGFloat) -> Void)?
        private weak var configuredSplit: UISplitViewController?
        private var original: (CGFloat, CGFloat, CGFloat)?
        override func point(inside point: CGPoint, with event: UIEvent?) -> Bool { false }
        override func didMoveToWindow() { super.didMoveToWindow(); applyLater() }

        func applyLater() { DispatchQueue.main.async { [weak self] in self?.apply() } }

        private func apply() {
            guard window != nil, let width = requested else { restore(); return }
            var responder: UIResponder? = self
            while let current = responder {
                if let controller = current as? UIViewController {
                    var parent: UIViewController? = controller
                    while let candidate = parent {
                        if let split = candidate as? UISplitViewController {
                            guard !split.isCollapsed else { restore(); return }
                            if configuredSplit !== split {
                                restore(); configuredSplit = split
                                original = (split.minimumPrimaryColumnWidth, split.maximumPrimaryColumnWidth, split.preferredPrimaryColumnWidthFraction)
                            }
                            split.minimumPrimaryColumnWidth = min(split.minimumPrimaryColumnWidth, minimum)
                            split.maximumPrimaryColumnWidth = max(split.maximumPrimaryColumnWidth, maximum)
                            split.minimumPrimaryColumnWidth = minimum; split.maximumPrimaryColumnWidth = maximum
                            split.preferredPrimaryColumnWidthFraction = width / max(1, split.view.bounds.width)
                            split.view.setNeedsLayout()
                            split.view.layoutIfNeeded()
                            measured?(split.primaryColumnWidth)
                            return
                        }
                        parent = candidate.parent
                    }
                }
                responder = current.next
            }
        }

        func restore() {
            if let split = configuredSplit, let original {
                split.minimumPrimaryColumnWidth = min(split.minimumPrimaryColumnWidth, original.0)
                split.maximumPrimaryColumnWidth = max(split.maximumPrimaryColumnWidth, original.1)
                split.minimumPrimaryColumnWidth = original.0; split.maximumPrimaryColumnWidth = original.1
                split.preferredPrimaryColumnWidthFraction = original.2
            }
            configuredSplit = nil; original = nil
        }
    }
}
#endif
