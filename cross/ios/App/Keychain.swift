import Foundation
import Security

/// The identity seed and the storage key live in the iOS Keychain, readable only on this
/// iPhone after it has been unlocked once since boot (so background wake-ups can still read them).
enum Keychain {
  private static let service = "com.xalvxnlab.meshron.secrets"

  static func get(_ name: String) -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: name,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var out: AnyObject?
    guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
    return String(data: data, encoding: .utf8)
  }

  @discardableResult
  static func set(_ name: String, _ value: String) -> Bool {
    let base: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: name,
    ]
    SecItemDelete(base as CFDictionary)
    var add = base
    add[kSecValueData as String] = Data(value.utf8)
    add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
  }
}
