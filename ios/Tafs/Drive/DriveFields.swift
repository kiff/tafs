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

    var body: some View {
        field.modifier(DriveFieldMark(words: title, secret: secret || kind == .secure))
    }

    @ViewBuilder private var field: some View {
        switch kind {
        case .line: TextField(title, text: $text)
        case .secure: SecureField(title, text: $text)
        case .editor: TextEditor(text: $text)
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

    var body: some View {
        Group {
            if #available(iOS 18, *), let cursor {
                SelectingField(title: title, text: $text, cursor: cursor)
            } else {
                TextField(title, text: $text, axis: .vertical).lineLimit(1...6)
            }
        }
        .modifier(DriveFieldMark(words: title, secret: false))
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

/// Registers a field with the drive: a tap focuses it, so Coach's `type` goes into it.
struct DriveFieldMark: ViewModifier {
    let words: String
    let secret: Bool
    @State private var anchor = WeakView()

    final class WeakView { weak var view: UIView? }

    func body(content: Content) -> some View {
        content.background(DriveAnchor(entry: DriveTargets.Entry(
            id: "field." + words.prefix(40).lowercased().replacingOccurrences(of: " ", with: "-"),
            words: secret ? "" : words, role: .textField, confirm: false, secret: secret, terms: false,
            actions: [.activate: { [anchor] in DriveFields.focus(near: anchor.view) }], anchor: nil))
            .background(AnchorReader(into: anchor))
            .accessibilityHidden(true))
    }

    /// Keeps a weak reference to a view in place behind the field, to find the field's own text view.
    private struct AnchorReader: UIViewRepresentable {
        let into: WeakView
        func makeUIView(context: Context) -> UIView {
            let v = UIView(frame: .zero)
            v.isUserInteractionEnabled = false
            v.isAccessibilityElement = false
            v.accessibilityElementsHidden = true
            into.view = v
            return v
        }
        func updateUIView(_ view: UIView, context: Context) { into.view = view }
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

    /// Focuses the text view a field's anchor sits behind: the one that overlaps it most.
    static func focus(near anchor: UIView?) {
        guard let anchor, let window = anchor.window, let place = DriveTargets.visibleFrame(anchor) else { return }
        let best = textViews(in: window).compactMap { v -> (UIView, CGFloat)? in
            guard let f = DriveTargets.visibleFrame(v) else { return nil }
            let i = f.intersection(place)
            return i.isNull ? nil : (v, i.width * i.height)
        }.max { $0.1 < $1.1 }
        _ = best?.0.becomeFirstResponder()
    }

    /// The field that has the focus, in the app's own windows.
    static func focused() -> UIView? {
        for window in DriveTargets.appWindows().reversed() {
            if let v = textViews(in: window).first(where: { $0.isFirstResponder }) { return v }
        }
        return nil
    }
}
