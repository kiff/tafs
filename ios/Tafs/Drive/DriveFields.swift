import SwiftUI
import UIKit

// Every text field of the app is one of these (docs/drive.md): each registers with the drive as a
// field, with its words and its secret mark, so Coach can find it, tap it and type into it. A CI
// check keeps it so: no TextField, SecureField or TextEditor outside this file.

/// A text field, a secure field or a text editor, drivable.
struct DriveField: View {
    enum Kind { case line, secure, editor }

    let title: String
    @Binding var text: String
    var kind: Kind = .line
    /// A vendor's key, a password, a recovery code: blanked in the screenshot, its words out of the tree.
    var secret = false

    init(_ title: String, text: Binding<String>, kind: Kind = .line, secret: Bool = false) {
        self.title = title
        self._text = text
        self.kind = kind
        self.secret = secret
    }

    // No modifier of the drive's own around the field: an identifier set on it stays one element. The drive
    // finds the field's text view on screen (DriveTargets.shown), by its placeholder, and a secret field by
    // its secure entry.
    @ViewBuilder var body: some View {
        if secret || kind == .secure {
            SecureField(title, text: $text)
        } else if kind == .editor {
            TextEditor(text: $text)
        } else {
            TextField(title, text: $text)
        }
    }
}

/// The chat's message field: it grows to six lines.
struct ChatField: View {
    let title: String
    @Binding var text: String
    /// Where the insertion point is, in characters, for dictation to put its words there;
    /// told from iOS 18, which says where it is.
    var cursor: ((Int) -> Void)? = nil

    @ViewBuilder var body: some View {
        if #available(iOS 18, *), let cursor {
            SelectingField(title: title, text: $text, cursor: cursor)
        } else {
            TextField(title, text: $text, axis: .vertical).lineLimit(1...6)
        }
    }
}

/// A message box that says where its insertion point is.
@available(iOS 18, *)
private struct SelectingField: View {
    let title: String
    @Binding var text: String
    let cursor: (Int) -> Void
    @State private var selection: TextSelection?

    var body: some View {
        TextField(title, text: $text, selection: $selection, axis: .vertical).lineLimit(1...6)
            .onChange(of: selection) { _, now in
                // Gone when the box loses focus (the mic's tap): the last point stays.
                guard let now, case .selection(let range) = now.indices, range.upperBound <= text.endIndex else { return }
                cursor(text.distance(from: text.startIndex, to: range.upperBound))
            }
    }
}

@MainActor
enum DriveFields {
    /// The app's text views in a window, shown and editable.
    static func textViews(in root: UIView) -> [UIView] {
        var out: [UIView] = []
        func visit(_ v: UIView) {
            if v.isHidden || v.alpha < 0.01 { return }
            if let f = v as? UITextField, f.isEnabled { out.append(f) }
            else if let t = v as? UITextView, t.isEditable { out.append(t) }
            v.subviews.forEach(visit)
        }
        visit(root)
        return out
    }

    /// The fields in a screen as the drive's entries: a tap focuses one. Named by its placeholder; a secure
    /// field is secret, its words left out.
    static func entries(in top: UIView) -> [DriveTargets.Entry] {
        var unnamed = 0
        return textViews(in: top).map { v in
            let field = v as? UITextField
            let placeholder = (field?.placeholder ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let secret = field?.isSecureTextEntry == true
            let id: String
            if placeholder.isEmpty {
                unnamed += 1
                id = "field.\(unnamed)"
            } else {
                id = "field." + placeholder.prefix(40).lowercased().replacingOccurrences(of: " ", with: "-")
            }
            return DriveTargets.Entry(id: id, words: secret ? "" : placeholder, role: .textField, confirm: false, secret: secret, terms: false,
                                      actions: [.activate: { [weak v] in _ = v?.becomeFirstResponder() }], anchor: v)
        }
    }

    /// The field that has the focus, in the app's own windows.
    static func focused() -> UIView? {
        for window in DriveTargets.appWindows().reversed() {
            if let v = textViews(in: window).first(where: { $0.isFirstResponder }) { return v }
        }
        return nil
    }
}
