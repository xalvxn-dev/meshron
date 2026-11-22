// Listening and speaking in the Windows app's windows.
// Listening: records the microphone until you stop talking (or 15 s), and Whisper (through the
// Meshron server) turns it into words. Speaking: the lifelike studio voice from the server, or
// Windows' own voice if that isn't available.
;(function () {
  'use strict'
  const A = window.Assistant

  /** Records one request. onLevel(0..1) while it listens. Resolves with the words, or '' if nothing was said. */
  async function listen (onLevel) {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 } })
    const ctx = new AudioContext()
    const an = ctx.createAnalyser(); an.fftSize = 1024
    ctx.createMediaStreamSource(stream).connect(an)
    const buf = new Float32Array(an.fftSize)
    const rec = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' })
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
      // Stop after 1.2 s of quiet once you've spoken, 6 s of nothing at all, or 15 s in total.
      if ((spoke && now - quietSince > 1200) || (!spoke && now - start > 6000) || now - start > 15000) { rec.stop(); return }
      stopTimer = requestAnimationFrame(tick)
    }
    tick()
    listen.cancel = () => { cancelAnimationFrame(stopTimer); spoke = false; if (rec.state === 'recording') rec.stop() }
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
  async function studio (text) {
    const r = await A.tts(text).catch(() => null)
    if (!r || !r.audio || !r.audio.length) return false
    for (const clip of r.audio) {
      await new Promise((resolve) => {
        const a = new Audio('data:audio/wav;base64,' + clip)
        current = a
        a.onended = a.onerror = () => resolve()
        a.play().catch(() => resolve())
      })
      if (current === null) break // stopped
    }
    return true
  }
  /** Says it (queued after anything still being said); onState(true/false) as it starts and ends. */
  function say (text, onState) {
    queue = queue.then(async () => {
      onState && onState(true)
      const s = await A.settings()
      if (!(s.studio && (await studio(text)))) await windowsVoice(text)
      onState && onState(false)
    })
    return queue
  }
  function hush () { speechSynthesis.cancel(); if (current) { current.pause(); current = null } }

  window.Voice = { listen, say, hush }
})()
