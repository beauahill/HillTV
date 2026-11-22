import HillTVCore
import SwiftUI

struct LoginView: View {
    @Environment(AppModel.self) private var app

    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var isWorking = false
    @State private var errorText: String?

    private var canSubmit: Bool {
        !isWorking && !server.isEmpty && !username.isEmpty && !password.isEmpty
    }

    var body: some View {
        VStack(spacing: 36) {
            VStack(spacing: 12) {
                Wordmark(size: 96)
                Text("Sign in with your Xtream Codes details")
                    .font(.tvMeta)
                    .foregroundStyle(Theme.muted)
            }

            VStack(spacing: 24) {
                TextField("Server, like http://line.example.com:8080", text: $server)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Username", text: $username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(.password)
            }
            .padding(40)
            .frame(width: 1000)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))

            if let errorText {
                Text(errorText)
                    .font(.tvMeta)
                    .foregroundStyle(Theme.live)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 1000)
            }

            Button {
                Task { await submit() }
            } label: {
                Group {
                    if isWorking {
                        ProgressView()
                    } else {
                        Text("Sign In")
                    }
                }
                .frame(minWidth: 300)
            }
            .buttonStyle(TVActionButtonStyle(prominent: true))
            .disabled(!canSubmit)
        }
        .padding(80)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func submit() async {
        isWorking = true
        errorText = nil
        defer { isWorking = false }
        do {
            try await app.signIn(server: server, username: username, password: password)
        } catch {
            errorText = error.localizedDescription
        }
    }
}
