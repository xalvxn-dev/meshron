// Listening and speaking in the Windows app's windows.
// Listening: records the microphone until you stop talking (or 15 s), and Whisper (through the
// Meshron server) turns it into words. Speaking: the lifelike studio voice from the server, or
// Windows' own voice if that isn't available.
;(function () {
  'use strict'
  const A = window.Assistant

  /** Records one request. onLevel(0..1) while it listens. Resolves with the words, or '' if nothing was said. */
  async function listen (onLevel, waitMs = 6000) {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } })
    const ctx = new AudioContext()
    const an = ctx.createAnalyser(); an.fftSize = 1024
    ctx.createMediaStreamSource(stream).connect(an)
    const buf = new Float32Array(an.fftSize)
    const rec = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus', audioBitsPerSecond: 64000 })
    const chunks = []
    rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
    let spoke = false; let quietSince = performance.now(); const start = performance.now()
    let stopTimer = null
    const done = new Promise((resolve) => { rec.onstop = resolve })
    rec.start(250)
    const tick = () => {
      if (rec.state !== 'recording') return
      an.getFloatTimeDomainData(buf)
      let s = 0; for (let i = 0; i < buf.length; i++) s += buf[i] * buf[i]
      const level = Math.min(1, Math.sqrt(s / buf.length) * 9)
      onLevel && onLevel(level)
      const now = performance.now()
      if (level > 0.12) { spoke = true; quietSince = now }
      // Stop after 2 s of quiet once you've spoken (room for pauses), after waitMs if nothing was said, or 30 s in total.
      if ((spoke && now - quietSince > 2000) || (!spoke && now - start > waitMs) || now - start > 30000) { rec.stop(); return }
      stopTimer = setTimeout(tick, 50) // a timer, not animation frames: those pause while the orb is hidden
    }
    tick()
    listen.cancel = () => { clearTimeout(stopTimer); spoke = false; if (rec.state === 'recording') rec.stop() }
    await done
    stream.getTracks().forEach((t) => t.stop()); ctx.close()
    onLevel && onLevel(0)
    if (!spoke || !chunks.length) return ''
    const blob = new Blob(chunks, { type: 'audio/webm' })
    const b64 = await new Promise((ok) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1]); fr.readAsDataURL(blob) })
    const r = await A.stt(b64, 'audio/webm')
    return (r && r.text) || ''
  }

  // ---- Speaking ----
  let current = null
  let queue = Promise.resolve()
  function pickVoice () {
    const vs = speechSynthesis.getVoices()
    const lang = navigator.language.slice(0, 2)
    const mine = vs.filter((v) => v.lang.startsWith(lang))
    return mine.find((v) => /natural|neural/i.test(v.name)) || mine.find((v) => /aria|jenny|guy|zira|david/i.test(v.name)) || mine[0] || vs[0]
  }
  function windowsVoice (text) {
    return new Promise((resolve) => {
      const u = new SpeechSynthesisUtterance(text)
      const v = pickVoice(); if (v) u.voice = v
      u.rate = 1; u.pitch = 1
      u.onend = u.onerror = () => resolve()
      speechSynthesis.speak(u)
    })
  }
  /** Reply text in speakable pieces: the first one short (so speech starts fast), then sentences. */
  function pieces (text) {
    const sentences = String(text).replace(/\s+/g, ' ').trim().split(/(?<=[.!?…])\s+(?=[A-Z0-9"'(])/)
    const out = []
    for (const s of sentences) {
      const last = out[out.length - 1]
      if (last && out.length > 1 && (last + ' ' + s).length < 220) out[out.length - 1] = last + ' ' + s
      else out.push(s)
    }
    return out.filter(Boolean)
  }
  async function studio (text) {
    // Each piece's audio is fetched while the one before it plays, so speech starts after the first
    // sentence is ready instead of after the whole reply.
    const parts = pieces(text)
    if (!parts.length) return false
    const mine = gen
    const fetchPart = (p) => A.tts(p).catch(() => null)
    let next = fetchPart(parts[0])
    let spoke = false
    for (let i = 0; i < parts.length; i++) {
      const r = await next
      if (gen !== mine) return true // stopped while the next sentence was coming
      next = i + 1 < parts.length ? fetchPart(parts[i + 1]) : null
      if (!r || !r.audio || !r.audio.length) { if (!spoke) return false; continue }
      spoke = true
      for (const clip of r.audio) {
        if (gen !== mine) return true
        await new Promise((resolve) => {
          const a = new Audio('data:' + (r.mime || 'audio/wav') + ';base64,' + clip)
          current = a
          a.onended = a.onerror = () => resolve()
          a.play().catch(() => resolve())
        })
        if (gen !== mine) return true // stopped
      }
    }
    return spoke
  }
  /** Says it (queued after anything still being said); onState(true/false) as it starts and ends. */
  function say (text, onState) {
    const asked = gen
    queue = queue.then(async () => {
      if (gen !== asked) return // "stop" came before its turn
      onState && onState(true)
      const s = await A.settings()
      // Kokoro voices run on this PC (no server); the others come from the server; Windows' voice is last.
      const local = s.studio && String(s.studioVoice || '').startsWith('kokoro:') && window.Kokoro && (await kokoro(text, s.studioVoice.slice(7)))
      if (!local && !(s.studio && (await studio(text)))) await windowsVoice(text)
      onState && onState(false)
    })
    return queue
  }
  /** Kokoro: generate on this PC and play each sentence as it's ready. */
  async function kokoro (text, voice) {
    // Not downloaded / warmed up yet: never make the person wait in silence. Another voice speaks
    // now, and Kokoro keeps loading in the background for next time.
    if (!window.Kokoro.ready()) { window.Kokoro.load().catch(() => {}); return false }
    try {
      // And if generating ever stalls, give up after 20 s so speech never gets stuck behind it.
      let timer
      const stall = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Kokoro took too long')), 20000) })
      await Promise.race([stall, window.Kokoro.speak(text, voice, (blob) => new Promise((resolve) => {
        clearTimeout(timer)
        if (current === null && stopped) return resolve(true)
        const a = new Audio(URL.createObjectURL(blob))
        current = a
        a.onended = a.onerror = () => { URL.revokeObjectURL(a.src); resolve(stopped) }
        a.play().catch(() => resolve(false))
      }), (pct) => window.dispatchEvent(new CustomEvent('kokoro-progress', { detail: pct })))])
      clearTimeout(timer)
      return true
    } catch (e) { console.warn('kokoro', e); return false }
  }
  let stopped = false
  let gen = 0 // which reply may speak: "stop" moves it on
  /** Stops speaking now, including anything queued. A paused clip never "ends", so it is ended here
   *  by hand, or whatever waits on it would wait forever (and the orb would stay "speaking"). */
  function hush () {
    gen++
    stopped = true; setTimeout(() => { stopped = false }, 300)
    speechSynthesis.cancel()
    const a = current; current = null
    if (a) { try { a.pause() } catch {} if (a.onended) a.onended() }
  }

  window.Voice = { listen, say, hush }
})()
