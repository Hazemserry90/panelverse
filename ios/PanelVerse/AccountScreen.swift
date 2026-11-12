import FirebaseAuth
import FirebaseCore
import SwiftUI
import UIKit

final class PanelVerseAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        if let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
           let options = FirebaseOptions(contentsOfFile: path) {
            FirebaseApp.configure(options: options)
        }
        return true
    }
}

@MainActor
final class AccountStore: ObservableObject {
    @Published private(set) var ready = false
    @Published private(set) var available = false
    @Published private(set) var email: String?
    @Published private(set) var verified = false
    private var auth: Auth?

    func start() {
        guard !ready, auth == nil else { return }
        guard FirebaseApp.app() != nil else {
            ready = true
            return
        }
        let auth = Auth.auth()
        self.auth = auth
        available = true
        auth.addStateDidChangeListener { [weak self] _, _ in
            Task { @MainActor in
                self?.update()
                self?.ready = true
            }
        }
    }

    func authenticate(email: String, password: String, create: Bool) async throws {
        guard let auth else { throw AccountError.unavailable }
        if create {
            let result = try await auth.createUser(withEmail: email, password: password)
            update()
            do { try await result.user.sendEmailVerification() }
            catch { throw AccountError.verificationDelivery }
        } else {
            _ = try await auth.signIn(withEmail: email, password: password)
            update()
        }
    }

    func sendReset(to email: String) async throws {
        guard let auth else { throw AccountError.unavailable }
        do { try await auth.sendPasswordReset(withEmail: email) }
        catch where AccountError.firebaseCode(error) == .userNotFound { return }
    }

    func resendVerification() async throws {
        guard let user = auth?.currentUser else { throw AccountError.unavailable }
        try await user.sendEmailVerification()
    }

    func refreshVerification() async throws {
        guard let user = auth?.currentUser else { throw AccountError.unavailable }
        try await user.reload()
        update()
    }

    func signOut() throws {
        try auth?.signOut()
        update()
    }

    private func update() {
        email = auth?.currentUser?.email
        verified = auth?.currentUser?.isEmailVerified ?? false
    }
}

private enum AccountError: LocalizedError {
    case unavailable, verificationDelivery

    var errorDescription: String? {
        switch self {
        case .unavailable: return "Account services are unavailable."
        case .verificationDelivery: return "Your account was created. Tap Resend verification email to try sending the link again."
        }
    }

    static func firebaseCode(_ error: Error) -> AuthErrorCode? {
        let details = error as NSError
        guard details.domain == AuthErrorDomain else { return nil }
        return AuthErrorCode(rawValue: details.code)
    }

    static func message(for error: Error, fallback: String) -> String {
        if let local = error as? AccountError { return local.localizedDescription }
        guard let code = firebaseCode(error) else { return fallback }

        switch code {
        case .networkError:
            return "Couldn’t reach the account service. Check your internet connection and try again."
        case .tooManyRequests:
            return "There have been too many attempts. Wait a while before trying again."
        case .operationNotAllowed:
            return "Email and password accounts are disabled for this Firebase project. (\(code.rawValue))"
        case .invalidEmail:
            return "Enter a valid email address."
        case .weakPassword, .passwordDoesNotMeetRequirements:
            return "This password doesn’t meet the account service’s requirements. Try a longer password with uppercase and lowercase letters, numbers, and symbols."
        case .keychainError:
            return "PanelVerse couldn’t save your session in the iPhone Keychain. Check the app’s signing before retrying. (\(code.rawValue))"
        case .appNotAuthorized, .invalidAPIKey, .internalError:
            if let expected = FirebaseApp.app()?.options.bundleID,
               let installed = Bundle.main.bundleIdentifier, expected != installed {
                return "The installed app’s bundle ID differs from its Firebase configuration. Reinstall using the original bundle ID. (\(code.rawValue))"
            }
            if code == .internalError { return "\(fallback) (Firebase \(code.rawValue))" }
            return "Firebase rejected this build’s app configuration. Check the iOS API key and bundle ID. (\(code.rawValue))"
        default:
            return fallback
        }
    }
}

@MainActor
struct AccountScreen: View {
    @EnvironmentObject private var account: AccountStore
    @Environment(\.dismiss) private var dismiss
    @State private var create = false
    @State private var email = ""
    @State private var password = ""
    @State private var confirmation = ""
    @State private var busy = false
    @State private var message: String?

    private var address: String { email.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var validEmail: Bool { address.contains("@") && !address.contains(where: \.isWhitespace) }
    private var validPassword: Bool {
        let pattern = #"^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[[:punct:]]).{8,}$"#
        return password.range(of: pattern, options: .regularExpression) != nil
    }

    var body: some View {
        NavigationStack {
            Form {
                if !account.available {
                    Text("Account services haven’t been configured for this build.")
                } else if let signedInEmail = account.email {
                    Section {
                        Text(signedInEmail)
                        if !account.verified {
                            Text("Verify your email using the link we sent you.")
                            Button("I’ve verified my email") {
                                perform(failure: "Couldn’t check your email verification. Try again.") {
                                    try await account.refreshVerification()
                                    if !account.verified { message = "Your email is still awaiting verification." }
                                }
                            }
                            Button("Resend verification email") {
                                perform(failure: "Couldn’t send the verification email. Try again.") {
                                    try await account.resendVerification()
                                    message = "Check your inbox for the verification link."
                                }
                            }
                        }
                    }
                    Button("Sign out", role: .destructive) {
                        do { try account.signOut() }
                        catch { message = AccountError.message(for: error, fallback: "Couldn’t sign out. Try again.") }
                    }
                } else {
                    Section {
                        TextField("Email", text: $email)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        PasswordEntry(title: "Password", text: $password,
                                      contentType: create ? .newPassword : .password)
                            .id(create)
                        if create {
                            PasswordEntry(title: "Confirm password", text: $confirmation,
                                          contentType: .newPassword, fieldName: "password confirmation")
                        }
                    } footer: {
                        if create { Text("Use at least 8 characters, with uppercase and lowercase letters, a number, and a symbol.") }
                    }
                    Button(create ? "Create account" : "Sign in") {
                        perform(failure: create ? "Couldn’t create your account. If you already have an account, try signing in or resetting your password." :
                                                  "Couldn’t sign in. Check your email and password and try again.") {
                            try await account.authenticate(email: address, password: password, create: create)
                            clearPasswords()
                        }
                    }
                    .disabled(!validEmail || password.isEmpty || (create && (!validPassword || password != confirmation)))
                    if !create {
                        Button("Send password reset email") {
                            perform(failure: "Couldn’t request a reset. Try again.") {
                                try await account.sendReset(to: address)
                                message = "If there’s an account for this email, check your inbox for a reset link."
                            }
                        }
                        .disabled(!validEmail)
                    }
                    Button(create ? "Back to sign in" : "Create an account") {
                        clearPasswords()
                        create.toggle()
                    }
                }
                if busy { ProgressView().accessibilityLabel("Working") }
            }
            .disabled(busy)
            .scrollContentBackground(.hidden)
            .background(Ink.background)
            .navigationTitle(account.email != nil || !account.available ? "Account" : (create ? "Sign up" : "Sign in"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() }.disabled(busy) }
            }
            .alert("Account", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK", role: .cancel) { message = nil }
            } message: {
                Text(message ?? "")
            }
        }
        .interactiveDismissDisabled(busy)
    }

    private func clearPasswords() {
        password = ""
        confirmation = ""
    }

    private func perform(failure: String, _ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do { try await action() }
            catch { message = AccountError.message(for: error, fallback: failure) }
            if account.email != nil { clearPasswords() }
        }
    }
}

private struct PasswordEntry: View {
    let title: String
    @Binding var text: String
    let contentType: UITextContentType
    var fieldName = "password"

    private enum Field: Hashable { case masked, visible }
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var focusedField: Field?
    @State private var isVisible = false

    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                SecureField(title, text: $text)
                    .textContentType(contentType)
                    .focused($focusedField, equals: .masked)
                    .opacity(isVisible ? 0 : 1)
                    .allowsHitTesting(!isVisible)
                    .accessibilityHidden(isVisible)

                TextField(title, text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focusedField, equals: .visible)
                    .opacity(isVisible ? 1 : 0)
                    .allowsHitTesting(isVisible)
                    .accessibilityHidden(!isVisible)
            }
            Button {
                let wasFocused = focusedField != nil
                isVisible.toggle()
                if wasFocused { focusedField = isVisible ? .visible : .masked }
            } label: {
                Image(systemName: isVisible ? "eye.slash" : "eye")
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(isVisible ? "Hide \(fieldName)" : "Show \(fieldName)")
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background, isVisible {
                if focusedField == .visible { focusedField = nil }
                isVisible = false
            }
        }
    }
}
