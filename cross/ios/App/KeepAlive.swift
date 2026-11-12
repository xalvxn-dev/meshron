import AVFoundation

/// Keeps Meshron running in the background (connected, notifying, shown as online) by
/// playing silence in a loop. It mixes with other audio, so music and calls in other apps
/// are not interrupted. Needs "audio" in UIBackgroundModes.
final class KeepAlive {
  static let shared = KeepAlive()
  private var player: AVAudioPlayer?

  /// Returns false if the audio session couldn't be started (then the app suspends as usual).
  @discardableResult
  func start() -> Bool {
    if player?.isPlaying == true { return true }
    do {
      let session = AVAudioSession.sharedInstance()
      try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
      try session.setActive(true)
      let p = try AVAudioPlayer(data: KeepAlive.silence())
      p.numberOfLoops = -1
      p.volume = 0
      guard p.play() else { return false }
      player = p
      return true
    } catch {
      NSLog("Meshron: keep-alive audio failed: \(error)")
      return false
    }
  }

  func stop() {
    player?.stop()
    player = nil
    try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
  }

  /// One second of silent 8 kHz mono 16-bit PCM, as a WAV file.
  private static func silence() -> Data {
    let rate: UInt32 = 8000
    let samples = Int(rate)
    var d = Data()
    func u32(_ v: UInt32) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 4)) }
    func u16(_ v: UInt16) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 2)) }
    d.append("RIFF".data(using: .ascii)!)
    u32(UInt32(36 + samples * 2))
    d.append("WAVEfmt ".data(using: .ascii)!)
    u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
    d.append("data".data(using: .ascii)!)
    u32(UInt32(samples * 2))
    d.append(Data(count: samples * 2))
    return d
  }
}
