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
        VStack(spacing: 40) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 100))
            Text("HillTV")
                .font(.largeTitle.bold())
            Text("Sign in with your Xtream Codes details")
                .foregroundStyle(.secondary)

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
            .frame(width: 1000)

            if let errorText {
                Text(errorText)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 1000)
            }

            Button {
                Task { await submit() }
            } label: {
                if isWorking {
                    ProgressView()
                } else {
                    Text("Sign In")
                        .frame(minWidth: 300)
                }
            }
            .disabled(!canSubmit)
        }
        .padding(80)
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
