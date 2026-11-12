// Audio worklets for calls: microphone capture and a small jitter-buffered speaker.
class Mic extends AudioWorkletProcessor {
  process(inputs) { const ch = inputs[0] && inputs[0][0]; if (ch) this.port.postMessage(ch.slice(0)); return true }
}
class Speaker extends AudioWorkletProcessor {
  constructor() { super(); this.q = []; this.cur = null; this.pos = 0; this.port.onmessage = (e) => { this.q.push(e.data); if (this.q.length > 25) this.q.splice(0, this.q.length - 8) } }
  process(_, outputs) {
    const out = outputs[0][0]
    for (let i = 0; i < out.length; i++) {
      if (!this.cur || this.pos >= this.cur.length) { this.cur = this.q.shift() || null; this.pos = 0 }
      out[i] = this.cur ? this.cur[this.pos++] : 0
    }
    for (let c = 1; c < outputs[0].length; c++) outputs[0][c].set(out)
    return true
  }
}
registerProcessor('mesh-mic', Mic)
registerProcessor('mesh-speaker', Speaker)
