import SwiftUI

/// Design tokens for a 1920x1080 canvas viewed from across the room.
///
/// - Dark, blue-tinted surfaces.
/// - Blue marks focus, selection and progress. Red is only for "live".
/// - Sans throughout. The big display size is condensed and bold.
/// - Nothing smaller than 20 pt.
enum Theme {
    static let background = Color(hex: 0x0D1117)
    static let sidebar = Color(hex: 0x0A0D13)
    static let surface = Color(hex: 0x151B26)
    static let control = Color(hex: 0x1D2636)
    static let controlRaised = Color(hex: 0x283448)
    static let text = Color(hex: 0xE8ECF4)
    static let muted = Color(hex: 0xA6B0C2)
    static let accent = Color(hex: 0x5B9BFF)
    static let onAccent = Color(hex: 0x07101F)
    static let live = Color(hex: 0xE5484D)
    static let line = Color(hex: 0x1F2836)

    static let radius: CGFloat = 14
    static let focusRing: CGFloat = 5
    static let focusScale: CGFloat = 1.06
    static let selectedBar: CGFloat = 5
    static let railWidth: CGFloat = 104
    static let railExpandedWidth: CGFloat = 340
    static let rowHeight: CGFloat = 72
    static let listRowHeight: CGFloat = 96

    static let focusAnimation = Animation.easeOut(duration: 0.16)
}

extension Font {
    static let tvDisplay = Font.system(size: 76, weight: .bold).width(.condensed)
    static let tvTitle = Font.system(size: 52, weight: .bold)
    static let tvSubtitle = Font.system(size: 38, weight: .semibold)
    static let tvHeading = Font.system(size: 32, weight: .semibold)
    static let tvBody = Font.system(size: 28)
    static let tvMeta = Font.system(size: 24)
    static let tvSmall = Font.system(size: 20)
    static let tvEyebrow = Font.system(size: 22, weight: .semibold)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

// MARK: - Focus-aware text colors

/// Inside a focused row the fill turns blue, so text has to flip to dark.
/// `isFocused` reads the nearest focusable ancestor, which is the button.
private struct FocusAwareForeground: ViewModifier {
    let secondary: Bool
    @Environment(\.isFocused) private var isFocused

    func body(content: Content) -> some View {
        content.foregroundStyle(color)
    }

    private var color: Color {
        if isFocused {
            return secondary ? Theme.onAccent.opacity(0.75) : Theme.onAccent
        }
        return secondary ? Theme.muted : Theme.text
    }
}

extension View {
    /// Muted text that stays readable when its row takes focus.
    func tvSecondary() -> some View {
        modifier(FocusAwareForeground(secondary: true))
    }
}

// MARK: - Button styles

/// Sidebar items, categories, channels, episodes, settings choices.
/// Focus: blue fill with dark text. Selected: a blue bar on the leading edge.
struct TVRowButtonStyle: ButtonStyle {
    var isSelected = false
    var minHeight: CGFloat = Theme.rowHeight
    var font: Font = .tvBody

    func makeBody(configuration: Configuration) -> some View {
        RowBody(configuration: configuration, isSelected: isSelected, minHeight: minHeight, font: font)
    }

    private struct RowBody: View {
        let configuration: ButtonStyleConfiguration
        let isSelected: Bool
        let minHeight: CGFloat
        let font: Font
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .font(font)
                .foregroundStyle(isFocused ? Theme.onAccent : Theme.text)
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .leading)
                .background {
                    RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                        .fill(isFocused ? Theme.accent : (isSelected ? Theme.control : Color.clear))
                }
                .overlay(alignment: .leading) {
                    if isSelected && !isFocused {
                        Capsule()
                            .fill(Theme.accent)
                            .frame(width: Theme.selectedBar)
                            .padding(.vertical, 14)
                    }
                }
                .contentShape(Rectangle())
                .scaleEffect(isFocused ? 1.02 : 1)
                .opacity(configuration.isPressed ? 0.85 : 1)
                .animation(Theme.focusAnimation, value: isFocused)
        }
    }
}

/// Standalone actions and chips: Watch, Sign In, Try Again, seasons.
/// Prominent buttons are light at rest; every button turns blue with a ring on focus.
struct TVActionButtonStyle: ButtonStyle {
    var prominent = false
    var isSelected = false

    func makeBody(configuration: Configuration) -> some View {
        ActionBody(configuration: configuration, prominent: prominent, isSelected: isSelected)
    }

    private struct ActionBody: View {
        let configuration: ButtonStyleConfiguration
        let prominent: Bool
        let isSelected: Bool
        @Environment(\.isFocused) private var isFocused
        @Environment(\.isEnabled) private var isEnabled

        private var fill: Color {
            if isFocused { return Theme.accent }
            return prominent ? Theme.text : Theme.control
        }

        private var foreground: Color {
            if isFocused { return Theme.onAccent }
            return prominent ? Theme.background : Theme.text
        }

        var body: some View {
            configuration.label
                .font(.tvMeta.weight(.semibold))
                .foregroundStyle(foreground)
                .padding(.horizontal, 32)
                .frame(minHeight: Theme.rowHeight)
                .background {
                    RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                        .fill(fill)
                }
                .overlay {
                    if isFocused {
                        RoundedRectangle(cornerRadius: Theme.radius + 7, style: .continuous)
                            .stroke(Theme.accent, lineWidth: 4)
                            .padding(-9)
                    }
                }
                .overlay(alignment: .bottom) {
                    if isSelected && !isFocused {
                        Capsule()
                            .fill(Theme.accent)
                            .frame(height: Theme.selectedBar)
                            .padding(.horizontal, 18)
                    }
                }
                .opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4)
                .animation(Theme.focusAnimation, value: isFocused)
        }
    }
}

/// No chrome of its own. The label draws focus itself (see `PosterCard`),
/// which keeps the system's default focus platter out of the way.
struct TVCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

// MARK: - Small building blocks

/// Small uppercase label above titles and sections.
struct Eyebrow: View {
    let text: String
    var color: Color = Theme.accent

    var body: some View {
        Text(text.uppercased())
            .font(.tvEyebrow)
            .tracking(3)
            .foregroundStyle(color)
    }
}

/// Rounded metadata chip: year, genre, rating, duration.
struct Pill: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.tvSmall.weight(.medium))
            .foregroundStyle(Theme.text)
            .lineLimit(1)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Theme.control, in: Capsule())
    }
}

/// Thin progress bar on a dark track.
struct ProgressBar: View {
    let value: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.control)
                Capsule()
                    .fill(Theme.accent)
                    .frame(width: geo.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 8)
    }
}

/// Wraps children onto new lines, like inline text. Used for pills.
struct FlowLayout: Layout {
    var spacing: CGFloat = 10

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width
            widest = max(widest, x)
            x += spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: proposal.width ?? widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// Blurred, darkened artwork behind detail pages.
struct Backdrop: View {
    let url: URL?

    var body: some View {
        ZStack {
            Theme.background
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image
                        .resizable()
                        .scaledToFill()
                        .blur(radius: 40)
                        .opacity(0.4)
                }
            }
            LinearGradient(
                colors: [Theme.background.opacity(0.3), Theme.background],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .clipped()
        .ignoresSafeArea()
    }
}
