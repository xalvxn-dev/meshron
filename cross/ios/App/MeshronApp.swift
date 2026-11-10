import SwiftUI
import UserNotifications

/// Meshron for iPhone: a full-screen web view showing the shared screens (cross/ui), backed by
/// the shared host running in a Bare Kit worklet (see Bridge.swift).
@main
struct MeshronApp: App {
  @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
  @StateObject private var bridge = Bridge()
  @Environment(\.scenePhase) private var scenePhase

  var body: some Scene {
    WindowGroup {
      WebView(bridge: bridge)
        .ignoresSafeArea()
        .background(Color(red: 10 / 255, green: 10 / 255, blue: 15 / 255))
        .preferredColorScheme(.dark)
        .onOpenURL { url in bridge.openLink(url.absoluteString) }
    }
    .onChange(of: scenePhase) { phase in
      switch phase {
      case .background: bridge.enterBackground()
      case .active: bridge.enterForeground()
      default: break
      }
    }
  }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
  static var onNotificationTap: ((String) -> Void)?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    UNUserNotificationCenter.current().delegate = self
    UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    return true
  }

  // Show banners while the app is open too, unless the screens are already showing that chat.
  func userNotificationCenter(
    _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    completionHandler([])
  }

  func userNotificationCenter(
    _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    if let conv = response.notification.request.content.userInfo["conv"] as? String {
      AppDelegate.onNotificationTap?(conv)
    }
    completionHandler()
  }
}
