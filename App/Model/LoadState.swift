import SwiftUI

enum LoadState: Equatable {
    case idle
    case loading
    case loaded
    case empty
    case failed(String)
}

/// Spinner / empty / error placeholder for a column or grid.
struct LoadStateView: View {
    let state: LoadState
    var emptyText = "Nothing here"
    var retry: (() -> Void)?

    var body: some View {
        switch state {
        case .idle, .loaded:
            EmptyView()
        case .loading:
            ProgressView()
                .tint(Theme.accent)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            ContentUnavailableView(emptyText, systemImage: "tray")
                .foregroundStyle(Theme.muted)
        case .failed(let message):
            VStack(spacing: 24) {
                ContentUnavailableView("Couldn't load", systemImage: "exclamationmark.triangle", description: Text(message))
                    .foregroundStyle(Theme.muted)
                if let retry {
                    Button("Try Again", action: retry)
                        .buttonStyle(TVActionButtonStyle(prominent: true))
                }
            }
        }
    }
}
