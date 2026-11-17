import SwiftUI
import WebKit
import Security

/// Meshron Mod for iPhone: the shared moderator screens (cross/mod) in a full-screen web view.
/// The sign-in key is kept in the iOS Keychain (window.ModStore), like Android's keystore.
@main
struct ModApp: App {
  var body: some Scene {
    WindowGroup {
      ModWebView()
        .ignoresSafeArea()
        .background(Color(red: 11 / 255, green: 13 / 255, blue: 20 / 255))
        .preferredColorScheme(.dark)
    }
  }
}

struct ModWebView: UIViewRepresentable {
  func makeCoordinator() -> Store { Store() }

  func makeUIView(context: Context) -> WKWebView {
    let config = WKWebViewConfiguration()
    let ucc = WKUserContentController()
    ucc.addScriptMessageHandler(context.coordinator, contentWorld: .page, name: "store")
    ucc.addUserScript(WKUserScript(source: """
      window.ModStore = {
        get: (k) => window.webkit.messageHandlers.store.postMessage({ op: 'get', k: String(k) }),
        set: (k, v) => window.webkit.messageHandlers.store.postMessage({ op: 'set', k: String(k), v: v == null ? null : String(v) })
      };
      """, injectionTime: .atDocumentStart, forMainFrameOnly: true))
    config.userContentController = ucc
    let web = WKWebView(frame: .zero, configuration: config)
    web.isOpaque = false
    web.backgroundColor = UIColor(red: 11 / 255, green: 13 / 255, blue: 20 / 255, alpha: 1)
    web.scrollView.contentInsetAdjustmentBehavior = .never
    web.navigationDelegate = context.coordinator
    if let dir = Bundle.main.url(forResource: "mod", withExtension: nil) {
      web.loadFileURL(dir.appendingPathComponent("index.html"), allowingReadAccessTo: dir)
    }
    return web
  }

  func updateUIView(_ uiView: WKWebView, context: Context) {}

  final class Store: NSObject, WKScriptMessageHandlerWithReply, WKNavigationDelegate {
    private let service = "com.xalvxnlab.meshron.mod"

    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage, replyHandler: @escaping (Any?, String?) -> Void) {
      guard let m = message.body as? [String: Any], let op = m["op"] as? String, let k = m["k"] as? String else { return replyHandler(nil, "bad request") }
      if op == "get" { replyHandler(get(k) ?? NSNull(), nil); return }
      if op == "set" { replyHandler(set(k, m["v"] as? String), nil); return }
      replyHandler(nil, "unknown op")
    }

    // Only the bundled screens; links open in Safari.
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
      if let url = action.request.url, url.isFileURL { return decisionHandler(.allow) }
      if let url = action.request.url, url.scheme == "https" { UIApplication.shared.open(url) }
      decisionHandler(.cancel)
    }

    private func base(_ k: String) -> [String: Any] {
      [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: k]
    }
    private func get(_ k: String) -> String? {
      var q = base(k)
      q[kSecReturnData as String] = true
      q[kSecMatchLimit as String] = kSecMatchLimitOne
      var out: AnyObject?
      guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
      return String(data: d, encoding: .utf8)
    }
    private func set(_ k: String, _ v: String?) -> Bool {
      SecItemDelete(base(k) as CFDictionary)
      guard let v = v else { return true }
      var add = base(k)
      add[kSecValueData as String] = Data(v.utf8)
      add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
      return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }
  }
}
