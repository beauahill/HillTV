import HillTVCore
import SwiftUI

/// The account switcher the rail's badge opens: every saved login, the one
/// in use marked, and Add an account. Select switches; hold Select offers
/// Remove.
///
/// A full-screen cover, so focus goes into it by itself. Switching rebuilds
/// the shell that presents it, so the choice is handed back and carried out
/// once the cover has closed (`MainShell.runAccountAction`).
struct AccountSwitcher: View {
    enum Action: Equatable {
        case switchTo(String)
        case remove(String)
        case add
    }

    let onChoose: (Action) -> Void

    @Environment(AppModel.self) private var app
    @FocusState private var focused: String?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Theme.background.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Accounts")
                    Text("Switch account")
                        .font(.tvTitle)
                        .foregroundStyle(Theme.text)
                }
                .padding(.leading, 24)

                VStack(alignment: .leading, spacing: 6) {
                    ForEach(app.accounts, id: \.self) { account in
                        row(account)
                    }
                    Button {
                        onChoose(.add)
                    } label: {
                        HStack(spacing: 20) {
                            Image(systemName: "plus")
                                .frame(width: 56)
                            Text("Add an account")
                            Spacer(minLength: 0)
                        }
                    }
                    .buttonStyle(TVRowButtonStyle(minHeight: Theme.listRowHeight, font: .tvMeta))
                    .focused($focused, equals: "add")
                }
                .frame(width: 1100)

                Text("Hold Select on an account to remove it from this TV.")
                    .font(.tvSmall)
                    .foregroundStyle(Theme.muted)
                    .padding(.leading, 24)
            }
            .padding(.horizontal, 60)
            .padding(.vertical, 60)
        }
        .defaultFocus($focused, app.currentAccountKey ?? "add")
    }

    private func row(_ account: XtreamAccount) -> some View {
        let key = AccountBook.key(account)
        let inUse = key == app.currentAccountKey
        let connections = app.accountDetails(key)?.user.maxConnections
        return Button {
            onChoose(.switchTo(key))
        } label: {
            HStack(spacing: 20) {
                Text(account.initial)
                    .font(.system(size: 26, weight: .bold))
                    .frame(width: 56, height: 56)
                    .overlay(Circle().stroke(lineWidth: 2.5))
                VStack(alignment: .leading, spacing: 4) {
                    Text(account.username)
                        .font(.tvMeta.weight(.semibold))
                        .lineLimit(1)
                    Text(account.serverURL.host ?? account.serverURL.absoluteString)
                        .font(.tvSmall)
                        .tvSecondary()
                        .lineLimit(1)
                }
                Spacer(minLength: 12)
                if let connections {
                    Text(connections == 1 ? "1 connection" : "\(connections) connections")
                        .font(.tvSmall)
                        .tvSecondary()
                }
                if inUse {
                    Text("In use")
                        .font(.tvSmall.weight(.semibold))
                }
            }
        }
        .buttonStyle(TVRowButtonStyle(isSelected: inUse, minHeight: Theme.listRowHeight, font: .tvMeta))
        .focused($focused, equals: key)
        .contextMenu {
            Button("Remove from this TV", role: .destructive) {
                onChoose(.remove(key))
            }
        }
    }
}
