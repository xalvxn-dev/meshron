// Sends email. Brevo (brevo.com) when BREVO_KEY is set: free, and works with one verified
// sender address (BREVO_SENDER), no domain needed. Otherwise Resend (RESEND_KEY + FROM).
// Returns true when the provider accepted the email.

export async function sendMail (env, { to, subject, html, text, attachment }) {
  const list = (Array.isArray(to) ? to : [to]).filter(Boolean)
  if (!list.length) return false
  let r
  if (env.BREVO_KEY) {
    r = await fetch('https://api.brevo.com/v3/smtp/email', {
      method: 'POST',
      headers: { 'api-key': env.BREVO_KEY, 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({
        sender: { name: 'Meshron', email: env.BREVO_SENDER },
        to: list.map((email) => ({ email })),
        subject,
        htmlContent: html,
        ...(text ? { textContent: text } : {}),
        ...(attachment ? { attachment: [{ name: attachment.name, content: attachment.content }] } : {})
      })
    }).catch(() => null)
  } else if (env.RESEND_KEY) {
    r = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { authorization: 'Bearer ' + env.RESEND_KEY, 'content-type': 'application/json' },
      body: JSON.stringify({
        from: env.FROM || 'Meshron <onboarding@resend.dev>', to: list, subject, html,
        ...(text ? { text } : {}),
        ...(attachment ? { attachments: [{ filename: attachment.name, content: attachment.content }] } : {})
      })
    }).catch(() => null)
  } else return false
  if (!r || !r.ok) {
    console.log('email failed', env.BREVO_KEY ? 'brevo' : 'resend', r ? r.status : 'network', r ? (await r.text()).slice(0, 300) : '')
    return false
  }
  return true
}
