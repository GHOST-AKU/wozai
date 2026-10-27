import SwiftUI

// SwiftUI column widths are preferences. Apply an explicit user resize to the existing
// native container as well, without replacing its delegate, controllers or navigation state.
#if os(macOS)
import AppKit

struct NativeColumnWidth: NSViewRepresentable {
    let width: CGFloat
    let enabled: Bool

    func makeNSView(context: Context) -> ColumnWidthView { ColumnWidthView() }
    func updateNSView(_ view: ColumnWidthView, context: Context) {
        view.requested = enabled ? width : nil
        view.applyLater()
    }
    static func dismantleNSView(_ view: ColumnWidthView, coordinator: ()) { view.restore() }

    final class ColumnWidthView: NSView {
        var requested: CGFloat?
        private weak var configuredItem: NSSplitViewItem?
        private var original: (CGFloat, CGFloat)?
        override func hitTest(_ point: NSPoint) -> NSView? { nil }
        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); applyLater() }

        func applyLater() {
            DispatchQueue.main.async { [weak self] in self?.apply() }
        }

        private func apply() {
            guard window != nil, let width = requested else { restore(); return }
            var ancestor = superview
            while let view = ancestor {
                if let split = view as? NSSplitView, split.isVertical, split.arrangedSubviews.count == 2 {
                    let primary = split.arrangedSubviews[0]
                    guard !split.isSubviewCollapsed(primary), abs(primary.frame.width - width) > 0.5 else { return }
                    if let controller = split.delegate as? NSSplitViewController, let item = controller.splitViewItems.first {
                        if configuredItem !== item {
                            restore(); configuredItem = item
                            original = (item.minimumThickness, item.maximumThickness)
                        }
                        item.minimumThickness = min(item.minimumThickness, width)
                        item.maximumThickness = max(item.maximumThickness, width)
                        item.minimumThickness = width; item.maximumThickness = width
                    }
                    let leadingOnRight = primary.frame.minX > split.arrangedSubviews[1].frame.minX
                    split.setPosition(leadingOnRight ? split.bounds.width - width - split.dividerThickness : width, ofDividerAt: 0)
                    return
                }
                ancestor = view.superview
            }
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
    let enabled: Bool

    func makeUIView(context: Context) -> ColumnWidthView { ColumnWidthView() }
    func updateUIView(_ view: ColumnWidthView, context: Context) {
        view.requested = enabled ? width : nil
        view.applyLater()
    }
    static func dismantleUIView(_ view: ColumnWidthView, coordinator: ()) { view.restore() }

    final class ColumnWidthView: UIView {
        var requested: CGFloat?
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
                            split.minimumPrimaryColumnWidth = min(split.minimumPrimaryColumnWidth, width)
                            split.maximumPrimaryColumnWidth = max(split.maximumPrimaryColumnWidth, width)
                            split.minimumPrimaryColumnWidth = width; split.maximumPrimaryColumnWidth = width
                            split.preferredPrimaryColumnWidthFraction = width / max(1, split.view.bounds.width)
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
