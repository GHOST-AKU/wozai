import SwiftUI
import WozaiCore
import OSLog
#if os(macOS)
import AppKit
#endif

/// Keep NavigationSplitView's native navigation and compact collapse while adjusting its column.
struct AdjustableSidebar: ViewModifier {
    let total: CGFloat
    let initial: CGFloat
    let minimum: CGFloat
    @ScaledMetric(relativeTo: .body) private var detailMinimum = 340
    @Environment(\.layoutDirection) private var direction
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @SceneStorage private var fraction: Double
    @State private var actual: CGFloat = 0
    @State private var proposed: Double?
    @State private var dragOrigin: Double?
    @State private var latched: Double?
    @GestureState private var dragging = false
    #if os(macOS)
    @State private var hovering = false
    #endif

    init(key: String, total: CGFloat, initial: CGFloat, minimum: CGFloat) {
        self.total = total; self.initial = initial; self.minimum = minimum
        _fraction = SceneStorage(wrappedValue: 0, key)
    }

    private var layout: SplitColumnLayout {
        SplitColumnLayout(total: Double(total), minimum: Double(minimum),
                          detailMinimum: Double(detailMinimum), initial: Double(initial))
    }

    private var enabled: Bool {
        #if os(iOS)
        return sizeClass == .regular && layout.canResize && actual < total - 100
        #else
        return layout.canResize && actual < total - 100
        #endif
    }

    func body(content: Content) -> some View {
        let width = CGFloat(proposed ?? layout.width(fraction: fraction))
        content
            .navigationSplitViewColumnWidth(min: minimum,
                                            ideal: enabled ? width : initial,
                                            max: enabled ? CGFloat(layout.maximum) : max(initial, minimum))
            .background(NativeColumnWidth(width: width, minimum: minimum, maximum: CGFloat(layout.maximum),
                                          enabled: enabled, measured: { actual = $0 }))
            .overlay(alignment: .trailing) {
                if enabled {
                    Color.clear
                        .overlay(alignment: .trailing) { Capsule().fill(.secondary).frame(width: 3, height: 32).padding(.trailing, 6) }
                        .frame(width: 44)
                        .frame(maxHeight: .infinity)
                        .contentShape(Rectangle())
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text("Column divider"))
                        .accessibilityValue(Text(verbatim: String(Int(actual.rounded()))))
                        .accessibilityHint(Text("Drag to resize. The default position and middle snap into place."))
                        .accessibilityIdentifier("columnDivider")
                        .accessibilityAdjustableAction { adjustment in
                            switch adjustment {
                            case .increment: save(layout.clamp(Double(actual) + 24))
                            case .decrement: save(layout.clamp(Double(actual) - 24))
                            @unknown default: break
                            }
                        }
                        .accessibilityAction(named: Text("Equal columns")) { save(layout.clamp(layout.total / 2)) }
                        .accessibilityAction(named: Text("Default column widths")) { fraction = 0 }
                        .onTapGesture { save(layout.clamp(layout.total / 2)) }
                        .gesture(DragGesture(minimumDistance: 2, coordinateSpace: .global)
                            .updating($dragging) { _, value, _ in value = true }
                            .onChanged { value in
                                if dragOrigin == nil { dragOrigin = actual > 0 ? Double(actual) : layout.width(fraction: fraction) }
                                let delta = Double(value.translation.width) * (direction == .rightToLeft ? -1 : 1)
                                let raw = (dragOrigin ?? Double(actual)) + delta
                                let snapped = layout.snap(raw, latched: latched)
                                latched = snapped == layout.clamp(raw) ? nil : snapped
                                proposed = snapped
                                #if DEBUG
                                Logger(subsystem: "dev.ghost.wozai.apple", category: "Columns")
                                    .debug("resize actual=\(Double(actual), privacy: .public) requested=\(snapped, privacy: .public)")
                                #endif
                            }
                            .onEnded { _ in
                                if let proposed { save(proposed) }
                                clearDrag()
                            })
                        .contextMenu {
                            Button("Equal columns") { save(layout.clamp(layout.total / 2)) }
                            Button("Default column widths") { fraction = 0 }
                        }
                        #if os(macOS)
                        .onHover { inside in
                            guard inside != hovering else { return }
                            hovering = inside
                            if inside { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() }
                        }
                        .onDisappear { if hovering { hovering = false; NSCursor.pop() } }
                        #endif
                }
            }
            .onChange(of: dragging) { _, value in if !value { clearDrag() } }
            .onChange(of: total) { _, _ in clearDrag() }
    }

    private func save(_ width: Double) {
        guard layout.canResize, layout.total > 0 else { return }
        fraction = layout.clamp(width) / layout.total
    }

    private func clearDrag() { proposed = nil; dragOrigin = nil; latched = nil }
}

struct LocalPathLabel: View {
    let path: LocalPathSnapshot

    private var title: LocalizedStringKey {
        switch path.kind {
        case .wifi: return "Wi-Fi · system-selected route"
        case .ethernet: return "Ethernet"
        case .loopback: return "Loopback"
        case .other: return "Local network · system-selected route"
        case .unavailable: return "Route not recorded"
        }
    }

    var body: some View {
        Label(title, systemImage: path.kind == .wifi ? "wifi" : "network")
            .font(.caption).foregroundStyle(.secondary)
            .accessibilityIdentifier("localPathStatus")
    }
}
