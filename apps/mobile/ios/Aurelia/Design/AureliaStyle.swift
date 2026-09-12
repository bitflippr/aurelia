import SwiftUI

private struct AureliaSidebarVisibleKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var aureliaSidebarVisible: Bool {
        get { self[AureliaSidebarVisibleKey.self] }
        set { self[AureliaSidebarVisibleKey.self] = newValue }
    }
}

enum AureliaSpacing {
    static let xs: CGFloat = 6
    static let s: CGFloat = 10
    static let m: CGFloat = 16
    static let l: CGFloat = 24
    static let xl: CGFloat = 32
    static let xxl: CGFloat = 40
}

enum AureliaRadius {
    static let s: CGFloat = 10
    static let m: CGFloat = 16
    static let l: CGFloat = 24
    static let xl: CGFloat = 32
}

enum AureliaLayout {
    static func isWide(_ width: CGFloat) -> Bool {
        width >= 720
    }

    static func usesSidebar(width: CGFloat, accessibilitySize: Bool) -> Bool {
        width >= 760 && !accessibilitySize
    }

    static func usesSplitPlayer(width: CGFloat, height: CGFloat, accessibilitySize: Bool) -> Bool {
        width >= 840 && height >= 600 && !accessibilitySize
    }
}

enum AureliaPalette {
    static func background(for scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0.055, green: 0.06, blue: 0.075) : Color(.systemBackground)
    }
    static func tint(for scheme: ColorScheme) -> Color {
        switch scheme {
        case .dark:
            Color(red: 0.70, green: 0.55, blue: 1.00)
        default:
            Color(red: 0.46, green: 0.34, blue: 0.95)
        }
    }

    static func glassBorder(for scheme: ColorScheme) -> Color {
        switch scheme {
        case .dark:
            Color.white.opacity(0.18)
        default:
            Color.white.opacity(0.35)
        }
    }

    static func shadowColor(for scheme: ColorScheme) -> Color {
        scheme == .dark ? Color.black.opacity(0.45) : Color.black.opacity(0.15)
    }
}

struct AureliaBackground: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        AureliaPalette.background(for: scheme)
            .ignoresSafeArea()
    }
}

struct GlassCard<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    var cornerRadius: CGFloat = AureliaRadius.l
    var padding: CGFloat = AureliaSpacing.m
    var showsShadow: Bool = true
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .padding(padding)
            .background(
                .ultraThinMaterial,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(AureliaPalette.glassBorder(for: scheme), lineWidth: 1)
            )
            .shadow(
                color: showsShadow ? AureliaPalette.shadowColor(for: scheme) : .clear,
                radius: showsShadow ? 14 : 0,
                x: 0,
                y: 8
            )
    }
}

struct AureliaSectionHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.title3.bold())
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
        .padding(.horizontal, AureliaSpacing.m)
    }
}

extension View {
    @ViewBuilder
    func aureliaGlass<S: Shape>(in shape: S, interactive: Bool = false) -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular.interactive(interactive), in: shape)
        } else {
            background(.ultraThinMaterial, in: shape)
        }
    }

    @ViewBuilder
    func aureliaGlassButton(prominent: Bool = false) -> some View {
        if #available(iOS 26, *) {
            if prominent { buttonStyle(.glassProminent) } else { buttonStyle(.glass) }
        } else {
            if prominent { buttonStyle(.borderedProminent) } else { buttonStyle(.bordered) }
        }
    }

    func aureliaScreen() -> some View {
        background(AureliaBackground())
            .navigationBarTitleDisplayMode(.inline)
    }

    func aureliaInsetCard(cornerRadius: CGFloat = AureliaRadius.l) -> some View {
        GlassCard(cornerRadius: cornerRadius) {
            self
        }
    }

    func aureliaRootTabHeader(_ title: String) -> some View {
        modifier(AureliaRootTabHeaderModifier(title: title))
    }
}

private struct AureliaRootTabHeaderModifier: ViewModifier {
    let title: String
    @Environment(\.aureliaSidebarVisible) private var sidebarVisible
    @Environment(\.tabBarPlacement) private var tabBarPlacement

    private var resolvedTitle: String {
        if tabBarPlacement == .sidebar {
            return title
        }
        if tabBarPlacement == .topBar {
            return ""
        }
        return title
    }

    func body(content: Content) -> some View {
        content
            .navigationTitle(resolvedTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if title != "Settings", !sidebarVisible {
                    ToolbarItem(placement: .topBarTrailing) { AccountMenuButton() }
                }
            }
    }
}

struct AureliaGlassGroup<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 12, content: content)
        } else {
            content()
        }
    }
}
