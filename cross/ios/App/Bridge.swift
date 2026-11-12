import BareKit
import SwiftUI
import UIKit
import UserNotifications
import WebKit

/// Owns the Bare worklet (shared host + networking) and relays JSON between it and the web
/// view. Calls whose method starts with "native." are handled here (clipboard, share sheet,
/// badge); everything else goes to the worklet. Keychain requests come back from the worklet.
@MainActor
final class Bridge: NSObject, ObservableObject, WKScriptMessageHandler {
  private var worklet: Worklet?
  private var ipc: IPC?
  private var pending = Data()
  private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
  private var inBackground = false
  private var queuedLink: String?
  weak var webView: WKWebView?

  override init() {
    super.init()
    startWorklet()
    AppDelegate.onNotificationTap = { [weak self] conv in
      self?.deliver(["ev": "open-chat", "data": ["conv": conv]])
    }
  }

  private func startWorklet() {
    do {
      let worklet = try Worklet()
      let dataDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Meshron", isDirectory: true)
      try? FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
      var excluded = URLResourceValues()
      excluded.isExcludedFromBackup = true
      var dir = dataDir
      try? dir.setResourceValues(excluded)
      try worklet.start("app.bundle", arguments: [dataDir.path])
      let ipc = try IPC(worklet: worklet)
      self.worklet = worklet
      self.ipc = ipc
      Task.detached { [weak self] in
        for await chunk in ipc {
          await self?.receive(chunk)
        }
      }
    } catch {
      NSLog("Meshron: could not start the worklet: \(error)")
    }
  }

  // MARK: Worklet -> app

  private func receive(_ chunk: Data) {
    pending.append(chunk)
    while let nl = pending.firstIndex(of: 10) {
      let line = pending.subdata(in: pending.startIndex..<nl)
      pending.removeSubrange(pending.startIndex...nl)
      guard !line.isEmpty,
        let obj = try? JSONSerialization.jsonObject(with: line) as? [String: Any]
      else { continue }
      handleFromWorklet(obj, raw: line)
    }
  }

  private func handleFromWorklet(_ m: [String: Any], raw: Data) {
    if let sid = m["sid"], let op = m["secret"] as? String, let name = m["name"] as? String {
      var value: Any = NSNull()
      if op == "get" {
        if let v = Keychain.get(name) { value = v }
      } else if let v = m["value"] as? String {
        value = Keychain.set(name, v) ? true : NSNull()
      }
      writeToWorklet(["sid": sid, "value": value])
      return
    }
    if let ev = m["ev"] as? String {
      if ev == "incoming", inBackground, let data = m["data"] as? [String: Any] { notify(data) }
      if ev == "summaries", let list = m["data"] as? [[String: Any]] {
        let total = list.reduce(0) { $0 + (($1["unread"] as? Int) ?? 0) }
        UNUserNotificationCenter.current().setBadgeCount(total)
      }
    }
    deliverRaw(raw)
  }

  private func notify(_ data: [String: Any]) {
    let content = UNMutableNotificationContent()
    content.title = (data["title"] as? String) ?? "Meshron"
    if let row = data["row"] as? [String: Any] {
      let kind = row["kind"] as? String
      content.body = kind == "file" ? "Sent a file" : kind == "invite" ? "Added you to a group" : ((row["body"] as? String) ?? "")
    }
    content.sound = .default
    if let conv = data["conv"] as? String {
      content.userInfo = ["conv": conv]
      content.threadIdentifier = conv
    }
    UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
  }

  // MARK: App -> web view

  private func deliverRaw(_ json: Data) {
    guard let s = String(data: json, encoding: .utf8) else { return }
    webView?.evaluateJavaScript("window.__meshronReceive && window.__meshronReceive(\(s))")
  }

  private func deliver(_ obj: [String: Any]) {
    if let data = try? JSONSerialization.data(withJSONObject: obj) { deliverRaw(data) }
  }

  private func writeToWorklet(_ obj: [String: Any]) {
    guard let ipc, var data = try? JSONSerialization.data(withJSONObject: obj) else { return }
    data.append(10)
    Task { try? await ipc.write(data) }
  }

  // MARK: Web view -> app

  func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
    guard let text = message.body as? String, let data = text.data(using: .utf8),
      let m = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let method = m["method"] as? String
    else { return }
    let id = m["id"] ?? NSNull()
    let args = m["args"] as? [String: Any] ?? [:]
    if method.hasPrefix("native.") {
      handleNative(method, args: args) { result in
        self.deliver(["id": id, "result": result ?? NSNull()])
      }
      return
    }
    if method == "init", let link = queuedLink {
      queuedLink = nil
      DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.openLink(link) }
    }
    writeToWorklet(["id": id, "method": method, "args": args])
  }

  private func handleNative(_ method: String, args: [String: Any], done: @escaping (Any?) -> Void) {
    switch method {
    case "native.copy":
      if let t = args["text"] as? String { UIPasteboard.general.string = t }
      done(true)
    case "native.share":
      present([args["text"] as? String ?? ""])
      done(true)
    case "native.shareFile":
      if let p = args["path"] as? String { present([URL(fileURLWithPath: p)]) }
      done(true)
    case "native.keepAwake":
      // Calls: the screen stays on until the call ends.
      UIApplication.shared.isIdleTimerDisabled = (args["on"] as? Bool) ?? false
      done(true)
    case "native.badge":
      UNUserNotificationCenter.current().setBadgeCount(args["count"] as? Int ?? 0)
      done(true)
    default:
      done(nil)
    }
  }

  private func present(_ items: [Any]) {
    guard let root = webView?.window?.rootViewController else { return }
    let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
    sheet.popoverPresentationController?.sourceView = webView
    var top = root
    while let next = top.presentedViewController { top = next }
    top.present(sheet, animated: true)
  }

  // MARK: Lifecycle

  func openLink(_ link: String) {
    if webView == nil { queuedLink = link; return }
    deliver(["ev": "open-link", "data": ["text": link]])
  }

  /// Leaving the app: stay connected (messages, notifications, "online") until the app is
  /// swiped away. iOS normally suspends apps after ~30 s; a silent audio session that mixes
  /// with other audio keeps Meshron running. There's no push server, so once the app is
  /// swiped away nothing can arrive until it's opened again.
  func enterBackground() {
    inBackground = true
    writeToWorklet(["lifecycle": "flush"])
    if !KeepAlive.shared.start() {
      // Couldn't keep running: use the time iOS gives, then suspend cleanly.
      writeToWorklet(["lifecycle": "suspend"])
      backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "meshron-sync") { [weak self] in
        self?.endBackgroundTask()
      }
      DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in
        guard let self, self.inBackground else { return }
        self.worklet?.suspend(linger: 5000)
        self.endBackgroundTask()
      }
    }
  }

  func enterForeground() {
    inBackground = false
    KeepAlive.shared.stop()
    worklet?.resume()
    writeToWorklet(["lifecycle": "resume"])
    endBackgroundTask()
  }

  private func endBackgroundTask() {
    if backgroundTask != .invalid {
      UIApplication.shared.endBackgroundTask(backgroundTask)
      backgroundTask = .invalid
    }
  }
}

/// The screens: cross/ui/index.html from the app bundle in a full-screen WKWebView.
struct WebView: UIViewRepresentable {
  let bridge: Bridge

  func makeUIView(context: Context) -> WKWebView {
    let config = WKWebViewConfiguration()
    config.userContentController.add(LeakAvoider(bridge), name: "meshron")
    config.allowsInlineMediaPlayback = true
    config.mediaTypesRequiringUserActionForPlayback = []
    let web = WKWebView(frame: .zero, configuration: config)
    web.isOpaque = false
    web.backgroundColor = UIColor(red: 10 / 255, green: 10 / 255, blue: 15 / 255, alpha: 1)
    web.scrollView.backgroundColor = web.backgroundColor
    web.scrollView.bounces = false
    web.scrollView.contentInsetAdjustmentBehavior = .never
    web.navigationDelegate = context.coordinator
    web.uiDelegate = context.coordinator
    bridge.webView = web
    if let index = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "ui") {
      web.loadFileURL(index, allowingReadAccessTo: index.deletingLastPathComponent())
    }
    return web
  }

  func updateUIView(_ view: WKWebView, context: Context) {}

  func makeCoordinator() -> Coordinator { Coordinator() }

  final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
    // Links in messages open in Safari; the app itself never navigates away.
    func webView(
      _ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
      decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
      // Web links, and SOS call / text links, open in Safari / Phone / Messages.
      if let url = action.request.url, ["http", "https", "tel", "sms"].contains(url.scheme ?? "") {
        UIApplication.shared.open(url)
        decisionHandler(.cancel)
        return
      }
      decisionHandler(.allow)
    }

    /// Calls: iOS already asked the user for the microphone/camera (Info.plist strings);
    /// don't ask a second time for the page.
    @available(iOS 15.0, *)
    func webView(
      _ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
      initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
      decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
      decisionHandler(.grant)
    }

    func webView(
      _ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
      for action: WKNavigationAction, windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
      if let url = action.request.url { UIApplication.shared.open(url) }
      return nil
    }
  }
}

/// WKUserContentController keeps its handlers alive; this keeps it from retaining the bridge.
final class LeakAvoider: NSObject, WKScriptMessageHandler {
  weak var target: (WKScriptMessageHandler & AnyObject)?
  init(_ target: WKScriptMessageHandler & AnyObject) { self.target = target }
  func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
    target?.userContentController(c, didReceive: m)
  }
}
