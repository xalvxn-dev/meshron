// A stand-in for the AI router (OpenAI-compatible), for local tests only.
const http = require('http')
http.createServer((req, res) => {
  let body = ''
  req.on('data', (d) => { body += d })
  req.on('end', () => {
    const o = JSON.parse(body || '{}')
    const sys = o.messages[0].content
    const last = o.messages[o.messages.length - 1].content
    let content
    // Meshron Assistant: answers with tool calls the way the real model does.
    if (sys.includes('You are Meshron Assistant')) {
      const reply = (message) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ choices: [{ message }] })) }
      const prev = o.messages[o.messages.length - 1]
      if (prev.role === 'tool') return reply({ role: 'assistant', content: 'Done: ' + String(prev.content).slice(0, 80) })
      const call = (name, args) => reply({ role: 'assistant', content: null, tool_calls: [{ id: 'call_' + name, type: 'function', function: { name, arguments: JSON.stringify(args) } }] })
      const text = Array.isArray(last) ? last[0].text : String(last)
      if (/news|weather/i.test(text)) return call('web_search', { query: text })
      if (/torch|flashlight/i.test(text)) return call('flashlight', { on: true })
      if (/text|sms/i.test(text)) return call('send_sms', { to: 'Mum', text: 'On my way' })
      return reply({ role: 'assistant', content: (Array.isArray(last) ? 'I see a picture. ' : '') + 'At your service. You said: ' + text })
    }
    if (o.model && /compound/.test(o.model)) content = 'Sunny, 28°C in Accra today (mock search).'
    else if (sys.includes('captions')) content = JSON.stringify({ caption: 'Sunset vibes only', hashtags: ['sunset', '#chill', 'meshron'] })
    else if (sys.includes('suggest replies')) content = '```json\n' + JSON.stringify({ replies: ['Sounds good!', 'On my way', 'Can we do 7?'] }) + '\n```'
    else if (sys.includes('AI moderator of Meshron')) content = JSON.stringify(/ban me/i.test(last) ? { action: 'ban', severity: 5, category: 'violence', reason: 'Threatens violence', human: false } : /kill you|idiot/i.test(last) ? { action: 'hide', severity: 4, category: 'harassment', reason: 'Threatens another person', human: false } : /unsure/i.test(last) ? { action: 'none', severity: 2, category: 'other', reason: 'Not sure', human: true } : { action: 'none', severity: 1, category: 'none', reason: 'Looks fine', human: false })
    else if (sys.includes('moderate')) content = JSON.stringify(/idiot|kill you/i.test(last) ? { ok: false, category: 'harassment', reason: 'Insults another person' } : { ok: true, category: 'none', reason: '' })
    else if (sys.includes('Translate')) content = 'Hola, ¿cómo estás?'
    else content = 'Hi! I am Meshron AI. You said: ' + last
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content } }] }))
  })
}).listen(8800, () => console.log('mock AI on 8800'))
