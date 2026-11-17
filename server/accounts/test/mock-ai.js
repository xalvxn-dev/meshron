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
    if (sys.includes('captions')) content = JSON.stringify({ caption: 'Sunset vibes only', hashtags: ['sunset', '#chill', 'meshron'] })
    else if (sys.includes('suggest replies')) content = '```json\n' + JSON.stringify({ replies: ['Sounds good!', 'On my way', 'Can we do 7?'] }) + '\n```'
    else if (sys.includes('AI moderator of Meshron')) content = JSON.stringify(/ban me/i.test(last) ? { action: 'ban', severity: 5, category: 'violence', reason: 'Threatens violence', human: false } : /kill you|idiot/i.test(last) ? { action: 'hide', severity: 4, category: 'harassment', reason: 'Threatens another person', human: false } : /unsure/i.test(last) ? { action: 'none', severity: 2, category: 'other', reason: 'Not sure', human: true } : { action: 'none', severity: 1, category: 'none', reason: 'Looks fine', human: false })
    else if (sys.includes('moderate')) content = JSON.stringify(/idiot|kill you/i.test(last) ? { ok: false, category: 'harassment', reason: 'Insults another person' } : { ok: true, category: 'none', reason: '' })
    else if (sys.includes('Translate')) content = 'Hola, ¿cómo estás?'
    else content = 'Hi! I am Meshron AI. You said: ' + last
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content } }] }))
  })
}).listen(8800, () => console.log('mock AI on 8800'))
