// iOS-style gestures for the shared screens (iPhone; also fine with a touch screen on Windows):
// swipe from the left edge to go back (chat, pages), drag a bottom sheet down to close it,
// and pages slide out when closed.

;(function () {
  'use strict'
  const EDGE = 28
  let drag = null // { el, kind, x0, y0, t0, w, moved }

  const topPage = () => {
    const pages = [...document.querySelectorAll('.page-full, .r-page, .live-view')].filter((p) => p.isConnected)
    return pages[pages.length - 1] || null
  }
  function backOf (el) {
    if (el.classList.contains('page-full')) return el.querySelector('.phead button')
    if (el.classList.contains('r-page')) return el.querySelector('.r-page-head button')
    if (el.classList.contains('live-view')) return el.querySelector('.lv-head button[title="Close"], .lv-head button[title="End"]')
    return null
  }

  document.addEventListener('touchstart', (e) => {
    if (e.touches.length !== 1) return
    const t = e.touches[0]
    // Bottom sheet: grab near its top.
    const sheet = e.target.closest && e.target.closest('.modal')
    if (sheet && window.innerWidth <= 820) {
      const r = sheet.getBoundingClientRect()
      if (t.clientY - r.top < 56 && !e.target.closest('input, textarea, button')) {
        drag = { el: sheet, kind: 'sheet', x0: t.clientX, y0: t.clientY, t0: Date.now() }
        return
      }
    }
    if (t.clientX > EDGE) return
    const page = topPage()
    if (page && !page.classList.contains('live-view')) { drag = { el: page, kind: 'page', x0: t.clientX, y0: t.clientY, t0: Date.now(), w: page.offsetWidth }; return }
    const shell = document.getElementById('shell')
    if (shell && shell.classList.contains('chat-open') && window.innerWidth <= 820) {
      drag = { el: document.getElementById('main'), kind: 'chat', x0: t.clientX, y0: t.clientY, t0: Date.now(), w: window.innerWidth }
    }
  }, { passive: true })

  document.addEventListener('touchmove', (e) => {
    if (!drag) return
    const t = e.touches[0]
    const dx = t.clientX - drag.x0
    const dy = t.clientY - drag.y0
    if (!drag.moved) {
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return
      // A mostly vertical move from the edge is a scroll, not a back swipe.
      if (drag.kind !== 'sheet' && Math.abs(dy) > Math.abs(dx)) { drag = null; return }
      if (drag.kind === 'sheet' && dy < 0) { drag = null; return }
      drag.moved = true
      if (drag.kind === 'chat') document.getElementById('shell').classList.add('dragging')
      if (drag.kind === 'sheet') drag.el.classList.add('dragging')
      drag.el.style.animation = 'none'
    }
    if (drag.kind === 'sheet') drag.el.style.transform = 'translateY(' + Math.max(0, dy) + 'px)'
    else {
      const x = Math.max(0, dx)
      drag.el.style.transform = 'translateX(' + x + 'px)'
      if (drag.kind === 'chat') document.getElementById('side').style.transform = 'translateX(' + (-28 + 28 * x / drag.w) + '%)'
    }
  }, { passive: true })

  document.addEventListener('touchend', (e) => {
    if (!drag) return
    const d = drag
    drag = null
    if (!d.moved) return
    const t = e.changedTouches[0]
    const dt = Math.max(1, Date.now() - d.t0)
    const ease = 'transform .32s cubic-bezier(.32,.72,0,1)'
    if (d.kind === 'sheet') {
      const dy = t.clientY - d.y0
      d.el.classList.remove('dragging')
      d.el.style.transition = ease
      if (dy > 120 || dy / dt > 0.6) {
        d.el.style.transform = 'translateY(110%)'
        setTimeout(() => { const c = d.el.querySelector('.head button[aria-label="Close"]'); if (c) c.click(); else d.el.closest('.scrim') && d.el.closest('.scrim').remove() }, 260)
      } else { d.el.style.transform = '' }
      return
    }
    const dx = t.clientX - d.x0
    const go = dx > d.w * 0.33 || dx / dt > 0.5
    d.el.style.transition = ease
    if (d.kind === 'chat') {
      const shell = document.getElementById('shell')
      const side = document.getElementById('side')
      shell.classList.remove('dragging')
      side.style.transition = ease
      if (go) {
        d.el.style.transform = 'translateX(100%)'
        side.style.transform = 'translateX(0)'
        setTimeout(() => { d.el.style.transform = ''; d.el.style.transition = ''; side.style.transform = ''; side.style.transition = ''; if (window.MeshronUI && window.MeshronUI.closeChat) window.MeshronUI.closeChat() }, 300)
      } else {
        d.el.style.transform = ''
        side.style.transform = ''
        setTimeout(() => { d.el.style.transition = ''; side.style.transition = '' }, 320)
      }
      return
    }
    if (go) {
      d.el.style.transform = 'translateX(100%)'
      setTimeout(() => { const b = backOf(d.el); if (b) b.click(); else d.el.remove() }, 300)
    } else { d.el.style.transform = '' }
  }, { passive: true })

  // Pages slide out when their back button is tapped (a short delay before they go).
  document.addEventListener('click', (e) => {
    const b = e.target.closest && e.target.closest('.page-full .phead button, .r-page .r-page-head > button:first-child')
    if (!b || b.dataset.closing) return
    const page = b.closest('.page-full, .r-page')
    if (!page || page.classList.contains('closing') || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    e.stopPropagation(); e.preventDefault()
    page.classList.add('closing')
    b.dataset.closing = '1'
    setTimeout(() => b.click(), 260)
  }, true)
})()
