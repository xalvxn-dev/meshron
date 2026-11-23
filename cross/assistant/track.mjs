// Hand and face tracking for game mode (MediaPipe, on this PC; the camera never leaves it).
// Turns raw landmarks into what games need: a mirrored fingertip cursor, which fingers are up,
// named gestures (point, pinch, palm, fist, two, three, thumbs, rock, call, L, ILY), swipes,
// punches, and from the face: a mood, head turn (yaw / pitch) and how open the mouth is.
import { FilesetResolver, HandLandmarker, FaceLandmarker } from './vendor/mediapipe/vision_bundle.mjs'

const d = (a, b) => Math.hypot(a.x - b.x, a.y - b.y)

export async function createTracker (video, models) {
  const files = await FilesetResolver.forVisionTasks('vendor/mediapipe/wasm')
  const opt = (buf) => ({ modelAssetBuffer: buf, delegate: 'GPU' })
  const make = async (Cls, buf, extra) => {
    try { return await Cls.createFromOptions(files, { baseOptions: opt(buf), runningMode: 'VIDEO', ...extra }) } catch {
      return Cls.createFromOptions(files, { baseOptions: { modelAssetBuffer: buf, delegate: 'CPU' }, runningMode: 'VIDEO', ...extra })
    }
  }
  const hands = await make(HandLandmarker, models.hand, { numHands: 2, minHandDetectionConfidence: 0.5, minTrackingConfidence: 0.5 })
  const face = await make(FaceLandmarker, models.face, { numFaces: 1, outputFaceBlendshapes: true })

  const past = [] // palm centre and size over the last half second, per hand slot
  let lastT = -1
  let neutral = null // the head's resting pose, so "turn" means turned from how you sit
  let faceOn = true
  let swipeCool = 0; let punchCool = 0

  function handInfo (lm, handed, W, H, now, slot) {
    // Mirror (a selfie view): moving your right hand right moves the cursor right.
    const P = lm.map((p) => ({ x: (1 - p.x) * W, y: p.y * H, z: p.z }))
    const size = d(P[0], P[9]) || 1
    const ext = [
      d(P[4], P[17]) > d(P[3], P[17]) * 1.08 && d(P[4], P[5]) > size * 0.45,
      d(P[0], P[8]) > d(P[0], P[6]) * 1.12,
      d(P[0], P[12]) > d(P[0], P[10]) * 1.12,
      d(P[0], P[16]) > d(P[0], P[14]) * 1.12,
      d(P[0], P[20]) > d(P[0], P[18]) * 1.12
    ]
    const pinch = d(P[4], P[8]) < size * 0.32
    const [t, i, m, r, p] = ext
    let g = 'none'
    const fingers = (a, b, c, e) => i === a && m === b && r === c && p === e
    if (pinch && !m && !r) g = 'pinch'
    else if (pinch && m && r && p) g = 'ok'
    else if (t && fingers(true, true, true, true)) g = 'palm'
    else if (!t && fingers(true, true, true, true)) g = 'four'
    else if (fingers(false, false, false, false)) g = t && P[4].y < P[0].y - size * 0.6 && P[4].y < P[5].y ? 'thumbs' : 'fist'
    else if (fingers(true, false, false, false)) g = t ? 'L' : 'point'
    else if (fingers(true, true, false, false)) g = 'two'
    else if (fingers(true, true, true, false)) g = 'three'
    else if (fingers(true, false, false, true)) g = t ? 'ily' : 'rock'
    else if (fingers(false, false, false, true)) g = t ? 'call' : 'pinky'
    const palm = { x: (P[0].x + P[5].x + P[17].x) / 3, y: (P[0].y + P[5].y + P[17].y) / 3 }
    // Motion: swipes (fast sideways) and punches (the hand grows fast toward the camera).
    const hist = past[slot] || (past[slot] = [])
    hist.push({ t: now, x: palm.x, y: palm.y, s: size })
    while (hist.length && now - hist[0].t > 450) hist.shift()
    let swipe = null; let punch = false
    const old = hist.find((h) => now - h.t < 320)
    if (old && now > swipeCool) {
      const dx = palm.x - old.x; const dy = palm.y - old.y
      if (Math.abs(dx) > W * 0.22 && Math.abs(dy) < Math.abs(dx) * 0.6) { swipe = dx > 0 ? 'right' : 'left'; swipeCool = now + 650 }
    }
    const before = hist.find((h) => now - h.t < 200)
    if (before && now > punchCool && size / before.s > 1.28 && (g === 'fist' || g === 'thumbs' || g === 'none')) { punch = true; punchCool = now + 380 }
    return { P, size, ext, pinch, gesture: g, handed, tip: P[8], palm, swipe, punch }
  }

  function faceInfo (res, W, H) {
    if (!res || !res.faceLandmarks || !res.faceLandmarks.length) return null
    const L = res.faceLandmarks[0]
    const bs = {}
    for (const c of (res.faceBlendshapes && res.faceBlendshapes[0] && res.faceBlendshapes[0].categories) || []) bs[c.categoryName] = c.score
    const v = (k) => bs[k] || 0
    const smile = (v('mouthSmileLeft') + v('mouthSmileRight')) / 2
    const frown = (v('mouthFrownLeft') + v('mouthFrownRight')) / 2
    const browDown = (v('browDownLeft') + v('browDownRight')) / 2
    const wide = (v('eyeWideLeft') + v('eyeWideRight')) / 2
    const blink = (v('eyeBlinkLeft') + v('eyeBlinkRight')) / 2
    const jaw = v('jawOpen')
    let mood = 'neutral'
    if (smile > 0.45) mood = 'happy'
    else if (jaw > 0.35 && (v('browInnerUp') > 0.3 || wide > 0.25)) mood = 'surprised'
    else if (browDown > 0.45) mood = 'angry'
    else if (frown > 0.25 || (v('browInnerUp') > 0.45 && smile < 0.1)) mood = 'sad'
    else if (blink > 0.65) mood = 'sleepy'
    // Head turn from the nose against the eyes (mirrored), measured from the resting pose.
    const le = L[33]; const re = L[263]; const nose = L[1]; const chin = L[152]
    const eyeX = (le.x + re.x) / 2; const eyeY = (le.y + re.y) / 2; const span = Math.hypot(le.x - re.x, le.y - re.y) || 0.1
    let yaw = -(nose.x - eyeX) / span
    let pitch = (nose.y - eyeY) / (Math.abs(chin.y - eyeY) || 0.1)
    if (!neutral) neutral = { yaw, pitch }
    yaw -= neutral.yaw; pitch -= neutral.pitch
    const box = { x: (1 - Math.max(...L.map((p) => p.x))) * W, y: Math.min(...L.map((p) => p.y)) * H, w: (Math.max(...L.map((p) => p.x)) - Math.min(...L.map((p) => p.x))) * W, h: (Math.max(...L.map((p) => p.y)) - Math.min(...L.map((p) => p.y))) * H }
    return { mood, smile, frown, jaw, blink, browDown, wide, yaw, pitch, box, points: L, blend: bs }
  }

  return {
    /** Runs both trackers on the current video frame (W x H: the drawing area). */
    frame (W, H) {
      const now = performance.now()
      if (video.readyState < 2 || now === lastT) return null
      lastT = now
      const hr = hands.detectForVideo(video, now)
      const out = { hands: [], face: null, t: now }
      ;(hr.landmarks || []).forEach((lm, k) => {
        const handed = hr.handednesses && hr.handednesses[k] && hr.handednesses[k][0] ? hr.handednesses[k][0].categoryName : ''
        // MediaPipe's handedness assumes a mirrored image; ours is the raw camera, so swap it.
        out.hands.push(handInfo(lm, handed === 'Left' ? 'right' : 'left', W, H, now, k))
      })
      if (faceOn) out.face = faceInfo(face.detectForVideo(video, now), W, H)
      return out
    },
    /** Face tracking costs time: games that don't need it turn it off. */
    useFace (on) { faceOn = on },
    /** Takes the current head pose as "straight ahead". */
    recenter () { neutral = null },
    close () { try { hands.close(); face.close() } catch {} }
  }
}
