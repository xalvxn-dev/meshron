// Meshron shared screens (iPhone + Windows). Plain DOM, no framework, no build step.
// Talks to the shared host through window.M (bridge.js).

;(function () {
  'use strict'

  const VERSION = '1.5.2'
  const DOWNLOAD_URL = 'https://github.com/xalvxn-dev/meshron/releases/latest'
  const PRIVACY_URL = 'https://xalvxn-dev.github.io/meshron/PRIVACY'
  const GROUP = 'g:'
  const PENDING = 0, SENT = 1, DELIVERED = 2, READ = 3
  const PAGE = 120

  // ---- Tiny DOM helpers ----------------------------------------------------------

  const $ = (sel) => document.querySelector(sel)

  function h(tag, props, ...kids) {
    const el = document.createElement(tag)
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue
      if (k === 'class') el.className = v
      else if (k === 'style') Object.assign(el.style, v)
      else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v)
      else if (k === 'value') el.value = v
      else el.setAttribute(k, v === true ? '' : v)
    }
    for (const kid of kids.flat(Infinity)) {
      if (kid == null || kid === false) continue
      el.appendChild(typeof kid === 'string' || typeof kid === 'number' ? document.createTextNode(String(kid)) : kid)
    }
    return el
  }

  // Android's icons (Material Icons, Rounded; Apache 2.0) so every platform looks the same.
  const ICONS = {
    play: "<path d=\"M8 6.82v10.36c0 .79.87 1.27 1.54.84l8.14-5.18a1 1 0 0 0 0-1.69L9.54 5.98A.998.998 0 0 0 8 6.82z\"/>",
    flag: "<path d=\"m14.4 6-.24-1.2c-.09-.46-.5-.8-.98-.8H6c-.55 0-1 .45-1 1v15c0 .55.45 1 1 1s1-.45 1-1v-6h5.6l.24 1.2c.09.47.5.8.98.8H19c.55 0 1-.45 1-1V7c0-.55-.45-1-1-1h-4.6z\"/>",
    doc: "<path d=\"M14.59 2.59c-.38-.38-.89-.59-1.42-.59H6c-1.1 0-2 .9-2 2v16c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8.83c0-.53-.21-1.04-.59-1.41l-4.82-4.83zM15 18H9c-.55 0-1-.45-1-1s.45-1 1-1h6c.55 0 1 .45 1 1s-.45 1-1 1zm0-4H9c-.55 0-1-.45-1-1s.45-1 1-1h6c.55 0 1 .45 1 1s-.45 1-1 1zm-2-6V3.5L18.5 9H14c-.55 0-1-.45-1-1z\"/>",
    exit: "<path d=\"M5 5h6c.55 0 1-.45 1-1s-.45-1-1-1H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h6c.55 0 1-.45 1-1s-.45-1-1-1H5V5z\"/><path d=\"m20.65 11.65-2.79-2.79a.501.501 0 0 0-.86.35V11h-7c-.55 0-1 .45-1 1s.45 1 1 1h7v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.19.2-.51.01-.7z\"/>",
    heart: "<path d=\"M13.35 20.13c-.76.69-1.93.69-2.69-.01l-.11-.1C5.3 15.27 1.87 12.16 2 8.28c.06-1.7.93-3.33 2.34-4.29 2.64-1.8 5.9-.96 7.66 1.1 1.76-2.06 5.02-2.91 7.66-1.1 1.41.96 2.28 2.59 2.34 4.29.14 3.88-3.3 6.99-8.55 11.76l-.1.09z\"/>",
    bookmark: "<path d=\"M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z\"/>",
    music: "<path d=\"M12 5v8.55c-.94-.54-2.1-.75-3.33-.32-1.34.48-2.37 1.67-2.61 3.07a4.007 4.007 0 0 0 4.59 4.65c1.96-.31 3.35-2.11 3.35-4.1V7h2c1.1 0 2-.9 2-2s-.9-2-2-2h-2c-1.1 0-2 .9-2 2z\"/>",
    volumeOff: "<path d=\"M3.63 3.63a.996.996 0 0 0 0 1.41L7.29 8.7 7 9H4c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71v-4.17l4.18 4.18c-.49.37-1.02.68-1.6.91-.36.15-.58.53-.58.92 0 .72.73 1.18 1.39.91.8-.33 1.55-.77 2.22-1.31l1.34 1.34a.996.996 0 1 0 1.41-1.41L5.05 3.63c-.39-.39-1.02-.39-1.42 0zM19 12c0 .82-.15 1.61-.41 2.34l1.53 1.53c.56-1.17.88-2.48.88-3.87 0-3.83-2.4-7.11-5.78-8.4-.59-.23-1.22.23-1.22.86v.19c0 .38.25.71.61.85C17.18 6.54 19 9.06 19 12zm-8.71-6.29-.17.17L12 7.76V6.41c0-.89-1.08-1.33-1.71-.7zM16.5 12A4.5 4.5 0 0 0 14 7.97v1.79l2.48 2.48c.01-.08.02-.16.02-.24z\"/>",
    eyeOff: "<path d=\"M12 6.5c2.76 0 5 2.24 5 5 0 .51-.1 1-.24 1.46l3.06 3.06c1.39-1.23 2.49-2.77 3.18-4.53C21.27 7.11 17 4 12 4c-1.27 0-2.49.2-3.64.57l2.17 2.17c.47-.14.96-.24 1.47-.24zM2.71 3.16a.996.996 0 0 0 0 1.41l1.97 1.97A11.892 11.892 0 0 0 1 11.5C2.73 15.89 7 19 12 19c1.52 0 2.97-.3 4.31-.82l2.72 2.72a.996.996 0 1 0 1.41-1.41L4.13 3.16c-.39-.39-1.03-.39-1.42 0zM12 16.5c-2.76 0-5-2.24-5-5 0-.77.18-1.5.49-2.14l1.57 1.57c-.03.18-.06.37-.06.57 0 1.66 1.34 3 3 3 .2 0 .38-.03.57-.07L14.14 16c-.65.32-1.37.5-2.14.5zm2.97-5.33a2.97 2.97 0 0 0-2.64-2.64l2.64 2.64z\"/>",
    reply: "<path d=\"M10 9V7.41c0-.89-1.08-1.34-1.71-.71L3.7 11.29a.996.996 0 0 0 0 1.41l4.59 4.59c.63.63 1.71.19 1.71-.7V14.9c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z\"/>",
    expand: "<path d=\"M21 8.59V4c0-.55-.45-1-1-1h-4.59c-.89 0-1.34 1.08-.71 1.71l1.59 1.59-10 10-1.59-1.59c-.62-.63-1.7-.19-1.7.7V20c0 .55.45 1 1 1h4.59c.89 0 1.34-1.08.71-1.71L7.71 17.7l10-10 1.59 1.59c.62.63 1.7.19 1.7-.7z\"/>",
    chat: "<path d=\"M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z\"/>",
    users: "<path d=\"M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5s-3 1.34-3 3 1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V18c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-1.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05.02.01.03.03.04.04 1.14.83 1.93 1.94 1.93 3.41V18c0 .35-.07.69-.18 1H22c.55 0 1-.45 1-1v-1.5c0-2.33-4.67-3.5-7-3.5z\"/>",
    user: "<path d=\"M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v1c0 .55.45 1 1 1h14c.55 0 1-.45 1-1v-1c0-2.66-5.33-4-8-4z\"/>",
    userPlus: "<path d=\"M15 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm-9-2V8c0-.55-.45-1-1-1s-1 .45-1 1v2H2c-.55 0-1 .45-1 1s.45 1 1 1h2v2c0 .55.45 1 1 1s1-.45 1-1v-2h2c.55 0 1-.45 1-1s-.45-1-1-1H6zm9 4c-2.67 0-8 1.34-8 4v1c0 .55.45 1 1 1h14c.55 0 1-.45 1-1v-1c0-2.66-5.33-4-8-4z\"/>",
    search: "<path d=\"M15.5 14h-.79l-.28-.27a6.5 6.5 0 0 0 1.48-5.34c-.47-2.78-2.79-5-5.59-5.34a6.505 6.505 0 0 0-7.27 7.27c.34 2.8 2.56 5.12 5.34 5.59a6.5 6.5 0 0 0 5.34-1.48l.27.28v.79l4.25 4.25c.41.41 1.08.41 1.49 0 .41-.41.41-1.08 0-1.49L15.5 14zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z\"/>",
    send: "<path d=\"m3.4 20.4 17.45-7.48a1 1 0 0 0 0-1.84L3.4 3.6a.993.993 0 0 0-1.39.91L2 9.12c0 .5.37.93.87.99L17 12 2.87 13.88c-.5.07-.87.5-.87 1l.01 4.61c0 .71.73 1.2 1.39.91z\"/>",
    plus: "<path d=\"M18 13h-5v5c0 .55-.45 1-1 1s-1-.45-1-1v-5H6c-.55 0-1-.45-1-1s.45-1 1-1h5V6c0-.55.45-1 1-1s1 .45 1 1v5h5c.55 0 1 .45 1 1s-.45 1-1 1z\"/>",
    x: "<path d=\"M18.3 5.71a.996.996 0 0 0-1.41 0L12 10.59 7.11 5.7A.996.996 0 1 0 5.7 7.11L10.59 12 5.7 16.89a.996.996 0 1 0 1.41 1.41L12 13.41l4.89 4.89a.996.996 0 1 0 1.41-1.41L13.41 12l4.89-4.89c.38-.38.38-1.02 0-1.4z\"/>",
    check: "<path d=\"M9 16.17 5.53 12.7a.996.996 0 1 0-1.41 1.41l4.18 4.18c.39.39 1.02.39 1.41 0L20.29 7.71a.996.996 0 1 0-1.41-1.41L9 16.17z\"/>",
    checks: "<path d=\"M17.3 6.3a.996.996 0 0 0-1.41 0l-5.64 5.64 1.41 1.41L17.3 7.7c.38-.38.38-1.02 0-1.4zm4.24-.01-9.88 9.88-3.48-3.47a.996.996 0 1 0-1.41 1.41l4.18 4.18c.39.39 1.02.39 1.41 0L22.95 7.71a.996.996 0 0 0 0-1.41h-.01a.975.975 0 0 0-1.4-.01zM1.12 14.12 5.3 18.3c.39.39 1.02.39 1.41 0l.7-.7-4.88-4.9a.996.996 0 0 0-1.41 0c-.39.39-.39 1.03 0 1.42z\"/>",
    clock: "<path d=\"M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm-.22-13h-.06c-.4 0-.72.32-.72.72v4.72c0 .35.18.68.49.86l4.15 2.49c.34.2.78.1.98-.24a.71.71 0 0 0-.25-.99l-3.87-2.3V7.72c0-.4-.32-.72-.72-.72z\"/>",
    clip: "<path d=\"M16.5 6.75v10.58c0 2.09-1.53 3.95-3.61 4.15A3.993 3.993 0 0 1 8.5 17.5V5.14c0-1.31.94-2.5 2.24-2.63A2.5 2.5 0 0 1 13.5 5v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6.75c0-.41-.34-.75-.75-.75s-.75.34-.75.75v8.61c0 1.31.94 2.5 2.24 2.63A2.5 2.5 0 0 0 15 15.5V5.17c0-2.09-1.53-3.95-3.61-4.15A3.998 3.998 0 0 0 7 5v12.27c0 2.87 2.1 5.44 4.96 5.71 3.29.3 6.04-2.26 6.04-5.48V6.75c0-.41-.34-.75-.75-.75s-.75.34-.75.75z\"/>",
    smile: "<path d=\"M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM8.5 8c.83 0 1.5.67 1.5 1.5S9.33 11 8.5 11 7 10.33 7 9.5 7.67 8 8.5 8zm8.21 6.72C15.8 16.67 14.04 18 12 18s-3.8-1.33-4.71-3.28c-.16-.33.08-.72.45-.72h8.52c.37 0 .61.39.45.72zM15.5 11c-.83 0-1.5-.67-1.5-1.5S14.67 8 15.5 8s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z\"/>",
    back: "<path d=\"M19 11H7.83l4.88-4.88c.39-.39.39-1.03 0-1.42a.996.996 0 0 0-1.41 0l-6.59 6.59a.996.996 0 0 0 0 1.41l6.59 6.59a.996.996 0 1 0 1.41-1.41L7.83 13H19c.55 0 1-.45 1-1s-.45-1-1-1z\"/>",
    more: "<path d=\"M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z\"/>",
    share: "<path d=\"M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z\"/>",
    copy: "<path d=\"M15 20H5V7c0-.55-.45-1-1-1s-1 .45-1 1v13c0 1.1.9 2 2 2h10c.55 0 1-.45 1-1s-.45-1-1-1zm5-4V4c0-1.1-.9-2-2-2H9c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h9c1.1 0 2-.9 2-2zm-2 0H9V4h9v12z\"/>",
    key: "<path d=\"M20.59 10h-7.94a6.004 6.004 0 0 0-6.88-3.88c-2.29.46-4.15 2.3-4.63 4.58A6.002 6.002 0 0 0 7 18a5.99 5.99 0 0 0 5.65-4H13l1.29 1.29c.39.39 1.02.39 1.41 0L17 14l1.29 1.29c.39.39 1.03.39 1.42 0l2.59-2.61a.999.999 0 0 0-.01-1.42l-.99-.97c-.2-.19-.45-.29-.71-.29zM7 15c-1.65 0-3-1.35-3-3s1.35-3 3-3 3 1.35 3 3-1.35 3-3 3z\"/>",
    globe: "<path d=\"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z\"/>",
    bell: "<path d=\"M12 22c1.1 0 2-.9 2-2h-4a2 2 0 0 0 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-1.29 1.29c-.63.63-.19 1.71.7 1.71h13.17c.89 0 1.34-1.08.71-1.71L18 16z\"/>",
    bellOff: "<path d=\"M12 22c1.1 0 2-.9 2-2h-4a2 2 0 0 0 2 2zm6-11c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68c-.24.06-.47.15-.69.23L18 13.1V11zM5.41 3.35 4 4.76l2.81 2.81C6.29 8.57 6 9.73 6 11v5l-1.29 1.29c-.63.63-.19 1.71.7 1.71h12.83l1.74 1.74 1.41-1.41L5.41 3.35z\"/>",
    pin: "<path fill-rule=\"evenodd\" d=\"M19 12.87c0-.47-.34-.85-.8-.98A2.997 2.997 0 0 1 16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.38-.93 2.54-2.2 2.89-.46.13-.8.51-.8.98V13c0 .55.45 1 1 1h4.98l.02 7c0 .55.45 1 1 1s1-.45 1-1l-.02-7H18c.55 0 1-.45 1-1v-.13z\"/>",
    trash: "<path d=\"M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V9c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v10zM18 4h-2.5l-.71-.71c-.18-.18-.44-.29-.7-.29H9.91c-.26 0-.52.11-.7.29L8.5 4H6c-.55 0-1 .45-1 1s.45 1 1 1h12c.55 0 1-.45 1-1s-.45-1-1-1z\"/>",
    shield: "<path d=\"m11.3 2.26-6 2.25C4.52 4.81 4 5.55 4 6.39v4.7c0 4.83 3.13 9.37 7.43 10.75.37.12.77.12 1.14 0 4.3-1.38 7.43-5.91 7.43-10.75v-4.7a2 2 0 0 0-1.3-1.87l-6-2.25c-.45-.18-.95-.18-1.4-.01z\"/>",
    block: "<path d=\"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9A7.902 7.902 0 0 1 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1A7.902 7.902 0 0 1 20 12c0 4.42-3.58 8-8 8z\"/>",
    at: "<path d=\"M12.72 2.03A9.991 9.991 0 0 0 2.03 12.72C2.39 18.01 7.01 22 12.31 22H16c.55 0 1-.45 1-1s-.45-1-1-1h-3.67c-3.73 0-7.15-2.42-8.08-6.03-1.49-5.8 3.91-11.21 9.71-9.71C17.58 5.18 20 8.6 20 12.33v1.1c0 .79-.71 1.57-1.5 1.57s-1.5-.78-1.5-1.57v-1.25c0-2.51-1.78-4.77-4.26-5.12a5.008 5.008 0 0 0-5.66 5.87 4.996 4.996 0 0 0 3.72 3.94c1.84.43 3.59-.16 4.74-1.33.89 1.22 2.67 1.86 4.3 1.21 1.34-.53 2.16-1.9 2.16-3.34v-1.09c0-5.31-3.99-9.93-9.28-10.29zM12 15c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z\"/>",
    qr: "<path d=\"M15 21h-2v-2h2v2zm-2-7h-2v5h2v-5zm8-2h-2v4h2v-4zm-2-2h-2v2h2v-2zM7 12H5v2h2v-2zm-2-2H3v2h2v-2zm7-5h2V3h-2v2zm-7.5-.5v3h3v-3h-3zM8 9H4c-.55 0-1-.45-1-1V4c0-.55.45-1 1-1h4c.55 0 1 .45 1 1v4c0 .55-.45 1-1 1zm-3.5 7.5v3h3v-3h-3zM8 21H4c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h4c.55 0 1 .45 1 1v4c0 .55-.45 1-1 1zm8.5-16.5v3h3v-3h-3zM20 9h-4c-.55 0-1-.45-1-1V4c0-.55.45-1 1-1h4c.55 0 1 .45 1 1v4c0 .55-.45 1-1 1zm-1 10v-3h-4v2h2v3h4v-2h-2zm-2-7h-4v2h4v-2zm-4-2H7v2h2v2h2v-2h2v-2zm1-1V7h-2V5h-2v4h4zM6.75 5.25h-1.5v1.5h1.5v-1.5zm0 12h-1.5v1.5h1.5v-1.5zm12-12h-1.5v1.5h1.5v-1.5z\"/>",
    edit: "<path d=\"M3 17.46v3.04c0 .28.22.5.5.5h3.04c.13 0 .26-.05.35-.15L17.81 9.94l-3.75-3.75L3.15 17.1c-.1.1-.15.22-.15.36zM20.71 7.04a.996.996 0 0 0 0-1.41l-2.34-2.34a.996.996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z\"/>",
    file: "<path d=\"M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8.83c0-.53-.21-1.04-.59-1.41l-4.83-4.83c-.37-.38-.88-.59-1.41-.59H6zm7 6V3.5L18.5 9H14c-.55 0-1-.45-1-1z\"/>",
    download: "<path d=\"M16.59 9H15V4c0-.55-.45-1-1-1h-4c-.55 0-1 .45-1 1v5H7.41c-.89 0-1.34 1.08-.71 1.71l4.59 4.59c.39.39 1.02.39 1.41 0l4.59-4.59c.63-.63.19-1.71-.7-1.71zM5 19c0 .55.45 1 1 1h12c.55 0 1-.45 1-1s-.45-1-1-1H6c-.55 0-1 .45-1 1z\"/>",
    down: "<path d=\"M8.12 9.29 12 13.17l3.88-3.88a.996.996 0 1 1 1.41 1.41l-4.59 4.59a.996.996 0 0 1-1.41 0L6.7 10.7a.996.996 0 0 1 0-1.41c.39-.38 1.03-.39 1.42 0z\"/>",
    right: "<path d=\"M9.29 6.71a.996.996 0 0 0 0 1.41L13.17 12l-3.88 3.88a.996.996 0 1 0 1.41 1.41l4.59-4.59a.996.996 0 0 0 0-1.41L10.7 6.7c-.38-.38-1.02-.38-1.41.01z\"/>",
    pause: "<path d=\"M8 19c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2v10c0 1.1.9 2 2 2zm6-12v10c0 1.1.9 2 2 2s2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2z\"/>",
    phone: "<path d=\"m19.23 15.26-2.54-.29a1.99 1.99 0 0 0-1.64.57l-1.84 1.84a15.045 15.045 0 0 1-6.59-6.59l1.85-1.85c.43-.43.64-1.03.57-1.64l-.29-2.52a2.001 2.001 0 0 0-1.99-1.77H5.03c-1.13 0-2.07.94-2 2.07.53 8.54 7.36 15.36 15.89 15.89 1.13.07 2.07-.87 2.07-2v-1.73c.01-1.01-.75-1.86-1.76-1.98z\"/>",
    video: "<path d=\"M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l2.29 2.29c.63.63 1.71.18 1.71-.71V8.91c0-.89-1.08-1.34-1.71-.71L17 10.5z\"/>",
    videoOff: "<path d=\"M21 14.2V8.91c0-.89-1.08-1.34-1.71-.71L17 10.5V7c0-.55-.45-1-1-1h-5.61l8.91 8.91c.62.63 1.7.18 1.7-.71zM2.71 2.56a.996.996 0 0 0 0 1.41L4.73 6H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.21 0 .39-.08.55-.18l2.48 2.48a.996.996 0 1 0 1.41-1.41L4.12 2.56a.996.996 0 0 0-1.41 0z\"/>",
    mic: "<path d=\"M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.91-3c-.49 0-.9.36-.98.85C16.52 14.2 14.47 16 12 16s-4.52-1.8-4.93-4.15a.998.998 0 0 0-.98-.85c-.61 0-1.09.54-1 1.14.49 3 2.89 5.35 5.91 5.78V20c0 .55.45 1 1 1s1-.45 1-1v-2.08a6.993 6.993 0 0 0 5.91-5.78c.1-.6-.39-1.14-1-1.14z\"/>",
    micOff: "<path d=\"M15 10.6V5c0-1.66-1.34-3-3-3-1.54 0-2.79 1.16-2.96 2.65L15 10.6zm3.08.4c-.41 0-.77.3-.83.71-.05.32-.12.64-.22.93l1.27 1.27c.3-.6.52-1.25.63-1.94a.857.857 0 0 0-.85-.97zM3.71 3.56a.996.996 0 0 0 0 1.41L9 10.27v.43c0 1.19.6 2.32 1.63 2.91.75.43 1.41.44 2.02.31l1.66 1.66c-.71.33-1.5.52-2.31.52-2.54 0-4.88-1.77-5.25-4.39a.839.839 0 0 0-.83-.71c-.52 0-.92.46-.85.97.46 2.96 2.96 5.3 5.93 5.75V20c0 .55.45 1 1 1s1-.45 1-1v-2.28a7.13 7.13 0 0 0 2.55-.9l3.49 3.49a.996.996 0 1 0 1.41-1.41L5.12 3.56a.996.996 0 0 0-1.41 0z\"/>",
    hangup: "<path d=\"m4.51 15.48 2-1.59c.48-.38.76-.96.76-1.57v-2.6c3.02-.98 6.29-.99 9.32 0v2.61c0 .61.28 1.19.76 1.57l1.99 1.58c.8.63 1.94.57 2.66-.15l1.22-1.22c.8-.8.8-2.13-.05-2.88-6.41-5.66-16.07-5.66-22.48 0-.85.75-.85 2.08-.05 2.88l1.22 1.22c.71.72 1.85.78 2.65.15z\"/>",
    flip: "<path d=\"M9 12c0 1.66 1.34 3 3 3s3-1.34 3-3-1.34-3-3-3-3 1.34-3 3z\"/><path d=\"M8 9c0-.55-.45-1-1-1H5.09C6.47 5.61 9.05 4 12 4c3.49 0 6.45 2.24 7.54 5.36.14.39.53.64.94.64.68 0 1.18-.67.96-1.31C20.07 4.79 16.36 2 12 2 8.73 2 5.82 3.58 4 6.01V5c0-.55-.45-1-1-1s-1 .45-1 1v4c0 .55.45 1 1 1h4c.55 0 1-.45 1-1zm8 6c0 .55.45 1 1 1h1.91c-1.38 2.39-3.96 4-6.91 4-3.49 0-6.45-2.24-7.54-5.36a.998.998 0 0 0-.94-.64c-.68 0-1.18.67-.96 1.31C3.93 19.21 7.64 22 12 22c3.27 0 6.18-1.58 8-4.01V19c0 .55.45 1 1 1s1-.45 1-1v-4c0-.55-.45-1-1-1h-4c-.55 0-1 .45-1 1z\"/>",
    info: "<path d=\"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 15c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1s1 .45 1 1v4c0 .55-.45 1-1 1zm1-8h-2V7h2v2z\"/>",
    sliders: "<path d=\"M3 18c0 .55.45 1 1 1h5v-2H4c-.55 0-1 .45-1 1zM3 6c0 .55.45 1 1 1h9V5H4c-.55 0-1 .45-1 1zm10 14v-1h7c.55 0 1-.45 1-1s-.45-1-1-1h-7v-1c0-.55-.45-1-1-1s-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1zM7 10v1H4c-.55 0-1 .45-1 1s.45 1 1 1h3v1c0 .55.45 1 1 1s1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1zm14 2c0-.55-.45-1-1-1h-9v2h9c.55 0 1-.45 1-1zm-5-3c.55 0 1-.45 1-1V7h3c.55 0 1-.45 1-1s-.45-1-1-1h-3V4c0-.55-.45-1-1-1s-1 .45-1 1v4c0 .55.45 1 1 1z\"/>",
    volume: "<path d=\"M3 10v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71V6.41c0-.89-1.08-1.34-1.71-.71L7 9H4c-.55 0-1 .45-1 1zm13.5 2A4.5 4.5 0 0 0 14 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 4.45v.2c0 .38.25.71.6.85C17.18 6.53 19 9.06 19 12s-1.82 5.47-4.4 6.5c-.36.14-.6.47-.6.85v.2c0 .63.63 1.07 1.21.85C18.6 19.11 21 15.84 21 12s-2.4-7.11-5.79-8.4c-.58-.23-1.21.22-1.21.85z\"/>",
    verified: "<path d=\"m23 12-2.44-2.79.34-3.69-3.61-.82-1.89-3.2L12 2.96 8.6 1.5 6.71 4.69 3.1 5.5l.34 3.7L1 12l2.44 2.79-.34 3.7 3.61.82L8.6 22.5l3.4-1.47 3.4 1.46 1.89-3.19 3.61-.82-.34-3.69L23 12zM9.38 16.01 7 13.61a.996.996 0 0 1 0-1.41l.07-.07c.39-.39 1.03-.39 1.42 0l1.61 1.62 5.15-5.16c.39-.39 1.03-.39 1.42 0l.07.07c.39.39.39 1.02 0 1.41l-5.92 5.94c-.41.39-1.04.39-1.44 0z\"/>",
    warn: "<path d=\"M4.47 21h15.06c1.54 0 2.5-1.67 1.73-3L13.73 4.99c-.77-1.33-2.69-1.33-3.46 0L2.74 18c-.77 1.33.19 3 1.73 3zM12 14c-.55 0-1-.45-1-1v-2c0-.55.45-1 1-1s1 .45 1 1v2c0 .55-.45 1-1 1zm1 4h-2v-2h2v2z\"/>",
    lock: "<path d=\"M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM9 8V6c0-1.66 1.34-3 3-3s3 1.34 3 3v2H9z\"/>",
    zap: "<path d=\"M10.67 21c-.35 0-.62-.31-.57-.66L11 14H7.5c-.88 0-.33-.75-.31-.78 1.26-2.23 3.15-5.53 5.65-9.93a.577.577 0 0 1 1.07.37l-.9 6.34h3.51c.4 0 .62.19.4.66-3.29 5.74-5.2 9.09-5.75 10.05-.1.18-.29.29-.5.29z\"/>",
    enter: "<path d=\"M19 8v3H5.83l2.88-2.88A.996.996 0 1 0 7.3 6.71L2.71 11.3a.996.996 0 0 0 0 1.41L7.3 17.3a.996.996 0 1 0 1.41-1.41L5.83 13H20c.55 0 1-.45 1-1V8c0-.55-.45-1-1-1s-1 .45-1 1z\"/>",
    grid: "<path d=\"M5 11h4c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2zm0 10h4c1.1 0 2-.9 2-2v-4c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2zm8-16v4c0 1.1.9 2 2 2h4c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2zm2 16h4c1.1 0 2-.9 2-2v-4c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2z\"/>",
    gear: "<path d=\"M19.5 12c0-.23-.01-.45-.03-.68l1.86-1.41c.4-.3.51-.86.26-1.3l-1.87-3.23a.987.987 0 0 0-1.25-.42l-2.15.91c-.37-.26-.76-.49-1.17-.68l-.29-2.31c-.06-.5-.49-.88-.99-.88h-3.73c-.51 0-.94.38-1 .88l-.29 2.31c-.41.19-.8.42-1.17.68l-2.15-.91c-.46-.2-1-.02-1.25.42L2.41 8.62c-.25.44-.14.99.26 1.3l1.86 1.41a7.343 7.343 0 0 0 0 1.35l-1.86 1.41c-.4.3-.51.86-.26 1.3l1.87 3.23c.25.44.79.62 1.25.42l2.15-.91c.37.26.76.49 1.17.68l.29 2.31c.06.5.49.88.99.88h3.73c.5 0 .93-.38.99-.88l.29-2.31c.41-.19.8-.42 1.17-.68l2.15.91c.46.2 1 .02 1.25-.42l1.87-3.23c.25-.44.14-.99-.26-1.3l-1.86-1.41c.03-.23.04-.45.04-.68zm-7.46 3.5c-1.93 0-3.5-1.57-3.5-3.5s1.57-3.5 3.5-3.5 3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z\"/>",
    home: "<path d=\"M10 19v-5h4v5c0 .55.45 1 1 1h3c.55 0 1-.45 1-1v-7h1.7c.46 0 .68-.57.33-.87L12.67 3.6c-.38-.34-.96-.34-1.34 0l-8.36 7.53c-.34.3-.13.87.33.87H5v7c0 .55.45 1 1 1h3c.55 0 1-.45 1-1z\"/>",
    explore: "<path d=\"M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z\"/>",
  }


  function icon(name, size = 22) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('viewBox', '0 0 24 24')
    svg.setAttribute('width', size)
    svg.setAttribute('height', size)
    if (name === 'logo') {
      // The Meshron "M": one stroke, blue -> teal -> lime (Brand.kt MeshronLogo).
      const id = 'meshron-mark' // same every time, so redrawn logos are identical (no flicker)
      svg.setAttribute('viewBox', '0 0 100 100')
      svg.innerHTML = '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6E7BFF"/><stop offset=".5" stop-color="#22D3C5"/><stop offset="1" stop-color="#B6F36A"/></linearGradient></defs>' +
        '<path d="M8 86 L22 16 L50 62 L78 16 L92 86" fill="none" stroke="url(#' + id + ')" stroke-width="15" stroke-linecap="round" stroke-linejoin="round"/>'
      return svg
    }
    svg.setAttribute('fill', 'currentColor')
    svg.innerHTML = ICONS[name] || ''
    return svg
  }

  // ---- State ---------------------------------------------------------------------

  const st = {
    me: null, contacts: [], groups: [], summaries: [], net: { status: 'stopped', peers: {}, log: [] },
    prefs: { settings: {}, pinned: [], muted: [], drafts: {} }, fileStatus: {}, limits: { MAX_BODY: 4000, MAX_NAME: 32, MAX_FILE: 25 * 1024 * 1024 },
    tab: 'chats', open: null, rows: [], shown: PAGE, query: '', hits: [], firstUnread: null, flash: null,
    seen: new Set(), sending: 0, pendingBelow: 0, typing: {}, reply: null, editing: null, profiles: { me: {}, peers: {} }
  }
  const isIos = M.platform === 'ios'
  document.body.classList.add(M.platform)

  const isGroup = (conv) => conv.startsWith(GROUP)
  const contact = (key) => st.contacts.find((c) => c.keyHex === key)
  const group = (conv) => st.groups.find((g) => g.conv === conv)
  const isOnline = (key) => !!(st.net.peers[key] && st.net.peers[key].state === 'connected')
  const shortKey = (key) => key.slice(0, 6) + '…' + key.slice(-4)

  function nameOf(key) {
    if (st.me && key === st.me.publicKey) return 'You'
    const c = contact(key)
    if (c && c.name) return c.name
    const p = st.net.peers[key]
    if (p && p.announcedName) return p.announcedName
    return c ? c.shortId : shortKey(key)
  }
  const convTitle = (conv) => (isGroup(conv) ? (group(conv) || { name: 'Group' }).name : nameOf(conv))

  // ---- Formatting ----------------------------------------------------------------

  // Same pairs as Android (MeshColors.avatars).
  const AVATARS = [['#5B67FF', '#22D3C5'], ['#14B8A6', '#84CC16'], ['#F97316', '#F43F5E'], ['#F59E0B', '#EF4444'], ['#8B5CF6', '#5B67FF'], ['#06B6D4', '#3B82F6'], ['#22C55E', '#0EA5E9'], ['#EC4899', '#8B5CF6']]
  // Same as Kotlin's String.hashCode, so a person has the same colours on every platform.
  function javaHash(s) {
    let x = 0
    for (let i = 0; i < s.length; i++) x = (Math.imul(31, x) + s.charCodeAt(i)) | 0
    return x & 0x7fffffff
  }
  function initials(name) {
    const words = String(name).trim().split(' ').filter(Boolean)
    if (!words.length) return '?'
    return (words.length === 1 ? [...words[0]][0] : [...words[0]][0] + [...words[1]][0]).toUpperCase()
  }
  function photoOf(seed) {
    const p = st.profiles || { me: {}, peers: {} }
    if (st.me && seed === st.me.publicKey) return p.me && p.me.photo
    return p.peers && p.peers[seed] && p.peers[seed].photo
  }
  const bioOf = (key) => ((st.profiles && st.profiles.peers && st.profiles.peers[key]) || {}).bio || ''
  function avatar(name, seed, size = 48, o = {}) {
    const [a, b] = AVATARS[javaHash(seed) % AVATARS.length]
    const photo = !o.group && photoOf(seed)
    const el = h('div', { class: 'avatar' + (o.group ? ' squircle' : '') + (photo ? ' has-photo' : ''), style: { width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.38) + 'px', background: `linear-gradient(135deg, ${a}, ${b})` } },
      photo ? h('img', { src: photo, alt: '' }) : initials(name))
    if (o.online && !o.ring) el.appendChild(h('span', { class: 'dot' }))
    if (!o.ring) return el
    return h('div', { class: 'ring' }, h('div', { class: 'gap' }, el), h('span', { class: 'dot' }))
  }

  const startOfDay = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime() }
  const daysAgo = (ms) => Math.round((startOfDay(Date.now()) - startOfDay(ms)) / 86400000)
  const clock = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  function friendlyTime(ms) {
    const d = daysAgo(ms)
    if (d === 0) return clock(ms)
    if (d === 1) return 'Yesterday'
    if (d < 7) return new Date(ms).toLocaleDateString([], { weekday: 'short' })
    return new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'numeric', year: '2-digit' })
  }
  function dayLabel(ms) {
    const d = daysAgo(ms)
    if (d === 0) return 'Today'
    if (d === 1) return 'Yesterday'
    return new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'long', year: 'numeric' })
  }
  function fileSize(n) {
    if (n < 1024) return n + ' B'
    if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
    return (n / 1024 / 1024).toFixed(1) + ' MB'
  }
  function parseJson(s) { try { return JSON.parse(s) } catch { return null } }
  function fileInfo(row) {
    const o = parseJson(row.body)
    return o && typeof o.name === 'string' ? { name: o.name, mime: o.mime || 'application/octet-stream', size: Number(o.size) || 0 } : null
  }
  // Replies: "↩ <quote>" line, newline, the reply (same on every platform).
  const REPLY_RE = /^↩ (.*)\n([\s\S]+)$/
  const bodyText = (body) => { const m = REPLY_RE.exec(body); return m ? m[2] : body }
  function quoteOf(r) {
    let text = bodyText(r.body)
    if (r.kind === 'file') { const fi = fileInfo(r); text = fi ? (fi.mime.startsWith('image/') ? '📷 Photo' : '📎 ' + fi.name) : '📎 File' }
    const line = String(text).replace(/\s+/g, ' ').trim()
    return line.length > 90 ? line.slice(0, 89) + '…' : line
  }
  const QUICK = ['❤️', '😂', '😮', '😢', '🙏', '👍']
  const EDIT_WINDOW = 15 * 60 * 1000
  /** The line a call record shows in a chat, from my side (older local notes are plain text). */
  function callText(row) {
    const o = parseJson(row.body)
    if (!o || typeof o !== 'object') return row.body
    const kind = o.v ? 'Video call' : 'Voice call'
    const d = Math.max(0, +o.d || 0)
    if (o.r === 'answered') return (row.outgoing ? '↗ ' : '↙ ') + kind + ' · ' + Math.floor(d / 60) + ':' + String(d % 60).padStart(2, '0')
    if (o.r === 'declined') return row.outgoing ? '↗ ' + kind + ' · Declined' : '↙ ' + kind + ' · You declined'
    return row.outgoing ? '↗ ' + kind + ' · No answer' : '✕ Missed ' + kind.toLowerCase()
  }
  function displayBody(row) {
    if (row.kind === 'call') return callText(row)
    if (row.kind === 'deleted') return row.outgoing ? 'You deleted this message' : 'This message was deleted'
    if (row.kind === 'text' && row.body.startsWith('meshron://ai/')) return '✦ Meshron AI answered'
    if (row.kind === 'text' && row.body.startsWith('meshron://reel/')) return '🎬 Reel'
    if (row.kind === 'text' && row.body.startsWith('meshron://collab/')) return '🤝 Collab invite'
    if (row.kind === 'text') return bodyText(row.body)
    if (row.kind === 'file') {
      const f = fileInfo(row)
      return f ? (f.mime.startsWith('image/') ? 'Photo: ' + f.name : f.mime.startsWith('audio/') ? '🎤 Voice message' : 'File: ' + f.name) : 'File'
    }
    if (row.kind === 'invite') {
      const o = parseJson(row.body)
      const name = (o && o.name) || 'a group'
      return row.outgoing ? `You invited them to “${name}”` : `Added you to the group “${name}”`
    }
    return row.body
  }

  // ---- Toasts, modals, menus -----------------------------------------------------

  function toast(text, ms = 2600) {
    const el = h('div', { class: 'toast' }, text)
    $('#toasts').appendChild(el)
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300) }, ms)
  }
  const oops = (err) => toast((err && err.message) || String(err), 4000)

  const layer = $('#layer')
  const openModals = []

  function modal({ title, body, buttons = [], wide, onClose, back }) {
    const close = () => {
      const i = openModals.indexOf(api)
      if (i >= 0) openModals.splice(i, 1)
      scrim.remove()
      if (onClose) onClose()
    }
    const box = h('div', { class: 'modal' + (wide ? ' wide' : '') },
      h('div', { class: 'head' },
        back ? h('button', { class: 'icon-btn', onClick: back, 'aria-label': 'Back' }, icon('back')) : null,
        h('h2', { class: 't-h2' }, title),
        h('button', { class: 'icon-btn', onClick: close, 'aria-label': 'Close' }, icon('x'))),
      body,
      buttons.length ? h('div', { class: 'buttons' }, buttons.map((b) => h('button', { class: 'btn small ' + (b.kind || 'soft'), disabled: b.disabled, onClick: () => b.onClick(api) }, b.label))) : null)
    const scrim = h('div', { class: 'scrim', onMousedown: (e) => { if (e.target === scrim) close() } }, box)
    const api = { close, box }
    layer.appendChild(scrim)
    openModals.push(api)
    const first = box.querySelector('input, textarea')
    if (first && !isIos) setTimeout(() => first.focus(), 50)
    return api
  }

  function confirmBox(title, text, label, danger) {
    return new Promise((resolve) => {
      let answered = false
      modal({
        title, body: h('p', { class: 'dim' }, text), onClose: () => { if (!answered) resolve(false) },
        buttons: [
          { label: 'Cancel', onClick: (m) => m.close() },
          { label, kind: danger ? 'soft danger' : 'primary', onClick: (m) => { answered = true; m.close(); resolve(true) } }
        ]
      })
    })
  }

  function menu(x, y, items) {
    closeMenu()
    const el = h('div', { class: 'menu' }, items.filter(Boolean).map((it) => h('button', { class: it.danger ? 'danger' : '', onClick: () => { closeMenu(); it.onClick() } }, icon(it.icon, 18), it.label)))
    const catcher = h('div', { class: 'menu-catcher', id: 'menu-catcher', onMousedown: closeMenu, onContextmenu: (e) => { e.preventDefault(); closeMenu() } })
    layer.appendChild(catcher)
    layer.appendChild(el)
    const r = el.getBoundingClientRect()
    el.style.left = Math.max(8, Math.min(x, innerWidth - r.width - 8)) + 'px'
    el.style.top = Math.max(8, Math.min(y, innerHeight - r.height - 8)) + 'px'
    el.id = 'menu'
  }
  function closeMenu() {
    for (const id of ['#menu', '#menu-catcher']) { const el = $(id); if (el) el.remove() }
  }
  // Right-click on desktop, long-press on touch.
  function onContext(el, fn) {
    el.addEventListener('contextmenu', (e) => { e.preventDefault(); fn(e.clientX, e.clientY) })
    let timer = null
    el.addEventListener('touchstart', (e) => {
      const t = e.touches[0]
      timer = setTimeout(() => { timer = null; fn(t.clientX, t.clientY) }, 480)
    }, { passive: true })
    for (const ev of ['touchend', 'touchmove', 'touchcancel']) el.addEventListener(ev, () => clearTimeout(timer), { passive: true })
  }

  async function copy(text, done = 'Copied') {
    if (await M.native.copy(text)) toast(done)
  }

  function shareMe() {
    const text = `Chat with me on Meshron: ${st.me.shareLink}` + (st.me.handle ? `\nOr find me as @${st.me.handle}` : '') + `\nGet the app: ${DOWNLOAD_URL}`
    if (M.native.share) M.native.share({ text }).catch(oops)
    else copy(text, 'Invite copied. Paste it to a friend.')
  }

  // ---- Onboarding ----------------------------------------------------------------

  const ob = $('#onboarding')
  const point = (ic, color, title, body) => h('div', { class: 'ob-point' },
    h('div', { class: 'tile', style: { background: color } }, icon(ic, 20)),
    h('div', {}, h('div', { class: 't-title' }, title), h('div', { class: 't-small dim' }, body)))

  function showWelcome() {
    ob.replaceChildren(h('div', { class: 'ob-card' },
      h('div', { class: 'ob-logo' }, icon('logo', 34)),
      h('h1', { class: 't-display', style: { marginTop: '26px' } }, 'Talk freely.', h('br'), h('span', { class: 'grad-text' }, 'No one in between.')),
      h('p', { class: 'dim', style: { marginTop: '12px', fontSize: '16px' } }, "Meshron sends your messages straight to your friends' devices. Nothing is stored on a server, and there's no account."),
      h('div', { class: 'ob-points' },
        point('user', '#6366F1', 'No number, no sign-up', "Pick a name and you're in."),
        point('lock', '#22A06B', 'Encrypted end to end', 'Only you and your friend can read it.'),
        point('zap', '#F59E0B', 'Works with the phone app', 'Chat with friends on Android, iPhone and Windows.')),
      h('button', { class: 'btn primary wide', onClick: showCreate }, 'Get started'),
      h('button', { class: 'btn soft wide', style: { marginTop: '8px' }, onClick: () => window.MeshronAccount.showSignIn() }, 'Sign in with my Meshron account'),
      h('button', { class: 'btn ghost wide', style: { marginTop: '8px' }, onClick: showRestore }, 'I have a recovery key')))
  }

  function showCreate() {
    let handleEdited = false
    const av = h('div', { class: 'ob-avatar' })
    const name = h('input', { class: 'input', placeholder: 'e.g. Alex', maxlength: 40, autocomplete: 'off' })
    const handle = h('input', { class: 'input has-prefix', placeholder: 'username', maxlength: 24, autocomplete: 'off', autocapitalize: 'none', spellcheck: 'false' })
    const nameHint = h('div', { class: 'hint' })
    const handleHint = h('div', { class: 'hint' }, 'Optional. Friends can find you by it.')
    const go = h('button', { class: 'btn primary wide', style: { marginTop: '18px' }, disabled: true }, 'Continue')
    const problem = (hh) => (hh.length < 3 ? 'At least 3 characters' : hh.length > 20 ? 'At most 20 characters' : !/^[a-z0-9_]+$/.test(hh) ? 'Only letters, numbers and _' : null)
    const refresh = () => {
      const n = name.value.trim().replace(/\s+/g, ' ')
      if (!handleEdited) handle.value = n.toLowerCase().replace(/[^a-z0-9_]/g, '').slice(0, 20)
      const hh = handle.value.trim().replace(/^@/, '').toLowerCase()
      const p = hh ? problem(hh) : null
      av.replaceChildren(avatar(n || '?', 'me', 88))
      nameHint.textContent = n.length > st.limits.MAX_NAME ? `At most ${st.limits.MAX_NAME} characters` : ''
      nameHint.className = 'hint' + (n.length > st.limits.MAX_NAME ? ' error' : '')
      handleHint.textContent = p || 'Optional. Friends can find you by it.'
      handleHint.className = 'hint' + (p ? ' error' : '')
      go.disabled = !(n.length >= 1 && n.length <= st.limits.MAX_NAME && !p)
    }
    name.addEventListener('input', refresh)
    handle.addEventListener('input', () => { handleEdited = true; refresh() })
    const submit = async () => {
      if (go.disabled) return
      go.disabled = true
      go.replaceChildren(h('div', { class: 'spinner' }))
      try {
        await M.call('identity.create', { name: name.value, handle: handle.value })
      } catch (err) {
        oops(err)
        go.replaceChildren('Continue')
        refresh()
      }
    }
    go.addEventListener('click', submit)
    for (const el of [name, handle]) el.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit() })
    ob.replaceChildren(h('div', { class: 'ob-card' },
      h('button', { class: 'icon-btn', style: { marginLeft: '-8px' }, onClick: showWelcome }, icon('back')),
      av,
      h('h1', { class: 't-h1' }, 'What should friends call you?'),
      h('p', { class: 'dim', style: { marginTop: '6px' } }, 'Your contacts see this name. You can change it any time.'),
      h('label', { class: 'field' }, h('span', {}, 'Your name'), name), nameHint,
      h('label', { class: 'field' }, h('span', {}, 'Username'), h('div', { class: 'input-wrap' }, h('span', { class: 'prefix' }, '@'), handle)), handleHint,
      go,
      h('p', { class: 't-small dimmer', style: { marginTop: '14px' } }, 'A private key is made on this device and kept in its secure storage. It never leaves the device.')))
    refresh()
    if (!isIos) name.focus()
  }

  function showRestore() {
    const key = h('textarea', { class: 'input', rows: 3, placeholder: '52 letters and numbers', autocapitalize: 'none', spellcheck: 'false' })
    const name = h('input', { class: 'input', placeholder: 'e.g. Alex', maxlength: 40 })
    const hint = h('div', { class: 'hint' }, '0/52')
    const go = h('button', { class: 'btn primary wide', style: { marginTop: '18px' }, disabled: true }, 'Restore')
    const clean = () => key.value.toLowerCase().replace(/[\s-]/g, '')
    const refresh = () => {
      hint.textContent = clean().length + '/52'
      hint.className = 'hint'
      go.disabled = !(clean().length === 52 && name.value.trim())
    }
    key.addEventListener('input', refresh)
    name.addEventListener('input', refresh)
    go.addEventListener('click', async () => {
      go.disabled = true
      try {
        await M.call('identity.restore', { key: key.value, name: name.value })
      } catch (err) {
        hint.textContent = err.message
        hint.className = 'hint error'
        go.disabled = false
      }
    })
    ob.replaceChildren(h('div', { class: 'ob-card' },
      h('button', { class: 'icon-btn', style: { marginLeft: '-8px' }, onClick: showWelcome }, icon('back')),
      h('h1', { class: 't-h1', style: { marginTop: '8px' } }, 'Restore your account'),
      h('p', { class: 'dim', style: { marginTop: '6px' } }, 'Type the recovery key you wrote down. Your Meshron ID comes back, so friends reach you again. Old chats and contacts stay on the old device.'),
      h('label', { class: 'field' }, h('span', {}, 'Recovery key'), key), hint,
      h('label', { class: 'field' }, h('span', {}, 'Your name'), name),
      go))
  }

  // ---- Shell: rail -----------------------------------------------------------------

  const ALL_TABS = [['chats', 'Chats', 'chat'], ['reels', 'Discover', 'play'], ['contacts', 'Activity', 'bell'], ['me', 'Me', 'user']]
  // Reels are public, so the anonymous identity doesn't have them.
  const TABS = { find: (f) => ALL_TABS.find(f), map: (f) => ALL_TABS.filter((t) => !(st.anon && t[0] === 'reels')).map(f) }
  const unreadTotal = () => st.summaries.reduce((n, s) => n + (st.prefs.muted.includes(s.conv) ? 0 : s.unread), 0)
  const requestCount = () => st.contacts.filter((c) => c.status === 'REQUEST').length

  function netLabel() {
    const n = st.net
    if (n.status === 'failed') return ['Problem', '']
    if (n.status !== 'online') return ['Starting', 'pulse']
    if (n.dht && n.dht.internet === false) return ['Internet off', 'off']
    if (n.dht && n.dht.bootstrapped) return ['Online', 'online']
    return ['Connecting', 'pulse']
  }

  function renderRail() {
    const [label, cls] = netLabel()
    patchKids($('#rail'), [
      h('div', { class: 'logo' }, icon('logo', 24)),
      ...TABS.map(([id, text, ic]) => {
        const n = id === 'chats' ? unreadTotal() : id === 'contacts' ? requestCount() : 0
        return h('button', { class: 'rail-btn' + (st.tab === id ? ' on' : ''), title: text, onClick: () => setTab(id) },
          icon(ic, 24), h('span', { class: 'label' }, text), n && st.tab !== id ? h('span', { class: 'badge' }, n > 99 ? '99+' : n) : null)
      }),
      h('div', { class: 'rail-spacer' }),
      h('button', { class: 'rail-btn rail-net', title: label, onClick: showConnection }, h('span', { class: 'net-dot ' + cls }), h('span', { class: 'label' }, label))])
    // The round create button next to the tabs (phones).
    if (!$('#create-btn')) $('#shell').appendChild(h('button', { id: 'create-btn', title: 'Create', onClick: (e) => showCreate(e) }, h('span', {}, h('span', {}, icon('plus', 30)))))
    const pill = $('#status-pill')
    if (pill) pill.replaceChildren(h('span', { class: 'net-dot ' + cls }), label)
    const total = unreadTotal()
    document.title = (total ? `(${total}) ` : '') + 'Meshron'
    if (M.native.badge) try { M.native.badge(total) } catch {}
  }

  /** Refresh on purpose (Ctrl+R on Windows, pull down on phones): fresh lists, badges and videos. */
  let refreshing = false
  async function manualRefresh() {
    if (refreshing) return
    refreshing = true
    const bar = h('div', { class: 'refresh-bar' }, h('div', { class: 'spinner' }))
    document.body.appendChild(bar)
    try {
      const init = await M.call('init')
      Object.assign(st, { contacts: init.contacts, groups: init.groups, summaries: init.summaries, stories: init.stories || st.stories, profiles: init.profiles || st.profiles, prefs: init.prefs })
      st.activeAt = 0; st.noticeCache = null; st.meCache = {}
      if (window.MeshronMod && window.MeshronMod.refreshBadges) window.MeshronMod.refreshBadges()
      if (st.tab === 'reels' && window.MeshronReels) { window.MeshronReels.hide(); window.MeshronReels.show() } else renderSide()
      renderRail()
    } catch {}
    setTimeout(() => { bar.classList.add('out'); setTimeout(() => bar.remove(), 250) }, 400)
    refreshing = false
  }
  window.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === 'r') { e.preventDefault(); manualRefresh() }
    if (e.key === 'F5') { e.preventDefault(); manualRefresh() }
  })
  // Coming back to the app: badges (newly verified people) refresh quietly.
  window.addEventListener('focus', () => { if (window.MeshronMod && window.MeshronMod.refreshBadges) window.MeshronMod.refreshBadges() })

  /** Create: a video, a moment, a chat or a group (the round + button). */
  function showCreate(e) {
    const r = e && e.currentTarget ? e.currentTarget.getBoundingClientRect() : { left: innerWidth - 40, top: innerHeight - 80 }
    menu(r.left, r.top, [
      st.anon ? null : { icon: 'play', label: 'Post a video', onClick: () => { setTab('reels'); setTimeout(() => window.MeshronReels.openCreate(), 50) } },
      { icon: 'plus', label: 'Share a moment', onClick: () => openComposer() },
      st.anon ? null : { icon: 'video', label: 'Go live', onClick: () => goLive() }
    ].filter(Boolean))
  }

  function setTab(id) {
    if (st.tab === id) return
    const wasReels = st.tab === 'reels'
    st.tab = id
    st.query = ''
    st.hits = []
    renderRail()
    if (id === 'reels') return window.MeshronReels.show()
    if (wasReels) window.MeshronReels.hide()
    buildSide()
  }

  // ---- Side pane -------------------------------------------------------------------

  function buildSide() {
    const side = $('#side')
    const title = TABS.find((t) => t[0] === st.tab)[1]
    const anonChip = st.anon ? h('span', { class: 'anon-chip', title: 'Anonymous identity' }, icon('eyeOff', 13), 'ANONYMOUS') : null
    let head
    if (st.tab === 'chats') {
      head = h('div', { class: 'side-head' },
        h('div', { class: 'brand' }, icon('logo', 40), h('h1', { class: 't-h1' }, 'Meshron'), anonChip),
        h('button', { class: 'status-pill', id: 'status-pill', onClick: showConnection }),
        h('button', { class: 'circ', title: 'Search', onClick: () => { st.searchOpen = !st.searchOpen; if (!st.searchOpen) { st.query = ''; st.hits = [] } buildSide(); if (st.searchOpen) setTimeout(() => { const i = $('#search'); if (i) i.focus() }, 30) } }, icon(st.searchOpen ? 'x' : 'search', 22)))
    } else if (st.tab === 'contacts') {
      head = h('div', { class: 'side-head' }, h('h1', { class: 't-h1', style: { fontSize: '32px' } }, 'Activity'), anonChip,
        h('button', { class: 'status-pill', id: 'status-pill', onClick: showConnection }),
        h('button', { class: 'circ', title: 'Add a friend', onClick: () => showAddContact() }, icon('userPlus', 22)))
    } else {
      head = h('div', { class: 'side-head' }, h('h1', { class: 't-h1', style: { flex: 1 } }, st.me.handle ? '@' + st.me.handle : st.me.username), anonChip,
        h('button', { class: 'circ', title: 'QR code', onClick: showQr }, icon('qr', 22)),
        h('button', { class: 'circ', title: 'Share', onClick: shareMe }, icon('share', 22)),
        h('button', { class: 'circ', title: 'Settings', onClick: showSettings }, icon('gear', 22)))
    }
    const kids = [head]
    if (st.tab === 'chats' && (st.searchOpen || st.query)) {
      const input = h('input', { id: 'search', placeholder: 'Search chats and messages', autocomplete: 'off', spellcheck: 'false', value: st.query || '' })
      const clear = h('button', { class: 'icon-btn clear', hidden: true, onClick: () => { input.value = ''; input.dispatchEvent(new Event('input')); input.focus() } }, icon('x', 18))
      let timer = null
      input.addEventListener('input', () => {
        st.query = input.value
        clear.hidden = !input.value
        renderSide()
        clearTimeout(timer)
        if (st.tab === 'chats') {
          timer = setTimeout(async () => {
            const q = st.query
            const hits = q.trim().length >= 2 ? await M.call('chat.search', { q }) : []
            if (q === st.query) { st.hits = hits; renderSide() }
          }, 220)
        }
      })
      kids.push(h('div', { class: 'search' }, icon('search', 20), input, clear))
    }
    const bodyEl = h('div', { class: 'side-body enter', id: 'side-body' })
    setTimeout(() => bodyEl.classList.remove('enter'), 700)
    kids.push(bodyEl)
    side.replaceChildren(...kids)
    renderRail()
    renderSide()
  }

  function renderSide() {
    // Nothing open on the right (Windows): the welcome panel, never an empty pane.
    if (!st.open && !main.children.length && !$('#shell').hidden) showHero()
    const body = $('#side-body')
    if (!body) return
    const top = body.scrollTop
    if (st.tab === 'chats') renderChats(body)
    else if (st.tab === 'contacts') renderContacts(body)
    else if (st.tab === 'me') renderMe(body)
    body.scrollTop = top
  }

  function tickIcon(status, size = 16) {
    if (status === PENDING) return h('span', { class: 'tick', title: 'Waiting' }, icon('clock', size - 2))
    if (status === SENT) return h('span', { class: 'tick', title: 'Sent' }, icon('check', size))
    if (status === READ) return h('span', { class: 'tick seen', title: 'Seen' }, icon('checks', size))
    return h('span', { class: 'tick done', title: 'Delivered' }, icon('checks', size))
  }

  function chatRows() {
    const byConv = new Map(st.summaries.map((s) => [s.conv, s]))
    const rows = []
    for (const c of st.contacts) {
      if (c.status === 'BLOCKED') continue
      if (c.status !== 'ADDED' && !byConv.has(c.keyHex)) continue
      rows.push({ conv: c.keyHex, title: c.displayName, group: false, online: isOnline(c.keyHex), fallback: c.status === 'REQUEST' ? 'Wants to chat with you' : 'Tap to say hi', s: byConv.get(c.keyHex) })
    }
    for (const g of st.groups) rows.push({ conv: g.conv, title: g.name, group: true, online: false, fallback: (g.adult ? '18+ · ' : '') + g.members.length + ' members', s: byConv.get(g.conv) })
    const pinned = (r) => (st.prefs.pinned.includes(r.conv) ? 1 : 0)
    return rows.sort((a, b) => pinned(b) - pinned(a) || ((b.s ? b.s.last.localTime : 0) - (a.s ? a.s.last.localTime : 0)))
  }

  function chatRow(r) {
    const s = r.s
    const unread = s ? s.unread : 0
    const last = s && s.last
    const muted = st.prefs.muted.includes(r.conv)
    const draft = st.prefs.drafts[r.conv]
    let preview = r.fallback
    if (draft && st.open !== r.conv) preview = null
    else if (last) preview = (r.group && !last.outgoing && last.senderKey ? nameOf(last.senderKey).split(' ')[0] + ': ' : '') + displayBody(last)
    const el = h('div', { class: 'row' + (unread ? ' unread' : '') + (st.open === r.conv ? ' on' : ''), onClick: () => openChat(r.conv) },
      avatar(r.title, r.conv, 54, { group: r.group, online: r.online }),
      h('div', { class: 'grow' },
        h('div', { class: 'top' }, h('span', { class: 'name ellipsis' }, r.title),
          verifiedBadge(r.conv),
          h('span', { class: 'time' }, last ? friendlyTime(last.localTime) : '')),
        h('div', { class: 'sub' },
          preview === null ? [h('span', { style: { color: 'var(--danger)', flex: 'none' } }, 'Draft:'), h('span', { class: 'ellipsis', style: { flex: 1 } }, draft)]
            : [last && last.outgoing ? tickIcon(last.status) : null, h('span', { class: 'ellipsis', style: { flex: 1 } }, preview)],
          st.prefs.pinned.includes(r.conv) ? h('span', { class: 'flag' }, icon('pin', 15)) : null,
          muted ? h('span', { class: 'flag' }, icon('bellOff', 15)) : null,
          unread ? h('span', { class: 'badge' + (muted ? ' quiet' : '') }, unread > 99 ? '99+' : unread) : null)))
    onContext(el, (x, y) => chatMenu(r.conv, x, y))
    return el
  }

  function verifiedBadge(conv) {
    const c = !isGroup(conv) && contact(conv)
    return c && c.verified ? h('span', { title: 'Verified', style: { color: 'var(--accent)', flex: 'none' } }, icon('verified', 15)) : null
  }

  function chatMenu(conv, x, y) {
    const pinned = st.prefs.pinned.includes(conv)
    const muted = st.prefs.muted.includes(conv)
    const s = st.summaries.find((v) => v.conv === conv)
    menu(x, y, [
      { icon: 'pin', label: pinned ? 'Unpin' : 'Pin to top', onClick: () => M.call('prefs.pin', { conv, on: !pinned }) },
      { icon: muted ? 'bell' : 'bellOff', label: muted ? 'Unmute' : 'Mute', onClick: () => M.call('prefs.mute', { conv, on: !muted }) },
      s && s.unread ? { icon: 'check', label: 'Mark as read', onClick: () => M.call('chat.markRead', { conv }) } : null,
      { icon: 'trash', label: 'Delete chat', danger: true, onClick: () => deleteChat(conv) }
    ])
  }

  async function deleteChat(conv) {
    if (!(await confirmBox('Delete this chat?', `All messages with ${convTitle(conv)} are removed from this device only. They keep their copy.`, 'Delete', true))) return
    await M.call('chat.deleteConversation', { conv })
    toast('Chat deleted')
  }

  function renderChats(body) {
    const q = st.query.trim().toLowerCase()
    const f = st.chatFilter || 'all'
    const rows = chatRows().filter((r) => !q || r.title.toLowerCase().includes(q)).filter((r) =>
      f === 'unread' ? r.s && r.s.unread > 0 : f === 'groups' ? r.group : f === 'requests' ? (!r.group && (contact(r.conv) || {}).status === 'REQUEST') : true)
    const added = st.contacts.filter((c) => c.status === 'ADDED')
    const kids = []
    const chips = !q ? h('div', { class: 'chips' }, ...[['all', 'All'], ['unread', 'Unread'], ['groups', 'Groups'], ['requests', 'Requests']].map(([id, label]) =>
      h('button', { class: 'chip' + (f === id ? ' on' : ''), onClick: () => { st.chatFilter = id; renderSide() } }, label))) : null
    if (!q && !st.anon) kids.push(h('button', { class: 'ai-entry', onClick: () => window.MeshronAI.openChat(), style: { display: 'flex', alignItems: 'center', gap: '12px', width: 'calc(100% - 24px)', margin: '4px 12px 10px', padding: '12px 14px', borderRadius: '18px', border: 'none', background: 'rgba(110,130,255,0.14)', color: 'inherit', textAlign: 'left', cursor: 'pointer' } },
      window.MeshronAI.aiAvatar(40), h('div', {}, h('div', { style: { fontWeight: 700 } }, 'Meshron AI'), h('div', { class: 'dim', style: { fontSize: '13px' } }, 'Ask anything, plan, write, translate'))))
    if (!q) kids.push(storiesStrip(added))
    if (chips) kids.push(chips)
    if (!rows.length && !q && f !== 'all') kids.push(h('p', { class: 'dim', style: { padding: '24px', textAlign: 'center' } }, f === 'unread' ? "You're all caught up." : f === 'groups' ? 'No groups yet. Tap + to make one.' : 'No chat requests.'))
    if (!rows.length && !q && f === 'all') {
      kids.push(h('div', { class: 'empty' },
        h('div', { class: 'orb' }, icon('chat', 36)),
        h('div', { class: 't-h2' }, 'No chats yet'),
        h('p', {}, 'Add a friend by their @username or Meshron ID, or send them yours.'),
        h('div', { class: 'actions' },
          h('button', { class: 'btn primary', onClick: () => showAddContact() }, icon('userPlus', 18), 'Add a contact'),
          h('button', { class: 'btn soft', onClick: shareMe }, icon('share', 18), 'Invite a friend'))))
    }
    if (q && rows.length) kids.push(h('div', { class: 'section' }, 'Chats'))
    kids.push(rows.map(chatRow))
    if (q && st.hits.length) {
      kids.push(h('div', { class: 'section' }, 'Messages'))
      kids.push(st.hits.map((m) => h('div', { class: 'row', onClick: () => openChat(m.peerKey, m.id) },
        avatar(convTitle(m.peerKey), m.peerKey, 44, { group: isGroup(m.peerKey) }),
        h('div', { class: 'grow' },
          h('div', { class: 'top' }, h('span', { class: 'name ellipsis', style: { fontSize: '15px' } }, convTitle(m.peerKey)), h('span', { class: 'time' }, friendlyTime(m.localTime))),
          h('div', { class: 'sub' }, h('span', { class: 'ellipsis' }, (m.outgoing ? 'You: ' : '') + m.body))))))
    }
    if (q && !rows.length && !st.hits.length) {
      kids.push(h('div', { class: 'empty' }, h('div', { class: 'orb' }, icon('search', 34)), h('div', { class: 't-h2' }, 'No match'), h('p', {}, `Nothing found for “${st.query.trim()}”.`)))
    }
    patchKids(body, kids.flat(Infinity).filter(Boolean))
  }

  /** Updates a list in place: unchanged rows stay exactly as they are (no flicker on refresh). */
  function patchKids(parent, kids) {
    kids = kids.map((k) => (k && k.nodeType ? k : document.createTextNode(String(k))))
    const old = [...parent.childNodes]
    kids.forEach((n, i) => { const o = old[i]; if (o && o.isEqualNode(n)) return; if (o) parent.replaceChild(n, o); else parent.appendChild(n) })
    for (let i = kids.length; i < old.length; i++) old[i].remove()
  }

  // ---- Contacts ------------------------------------------------------------------

  function contactMenu(c, x, y) {
    menu(x, y, [
      { icon: 'chat', label: 'Message', onClick: () => openChat(c.keyHex) },
      { icon: 'shield', label: 'Verify safety code', onClick: () => showSafety(c.keyHex) },
      { icon: 'block', label: 'Block', onClick: () => blockContact(c.keyHex) },
      { icon: 'trash', label: 'Remove', danger: true, onClick: () => removeContact(c) }
    ])
  }
  async function removeContact(c) {
    if (await confirmBox(`Remove ${c.displayName}?`, "You'll stop connecting to them. Your chat history stays. You can add them again any time.", 'Remove', true)) M.call('contacts.remove', { key: c.keyHex })
  }
  async function blockContact(key) {
    if (await confirmBox(`Block ${nameOf(key)}?`, "They won't be able to message or call you. You can unblock them in Contacts.", 'Block', true)) {
      await M.call('contacts.block', { key })
      if (st.open === key) closeChat()
    }
  }

  function renderContacts(body) {
    const af = st.activityFilter || 'all'
    const show = (what) => af === 'all' || af === what
    const chipRow = h('div', { class: 'chips' }, ...[['all', 'All'], ['requests', 'Requests'], ['suggestions', 'Suggestions'], ['meshron', 'From Meshron'], ['contacts', 'Contacts']].map(([id, label]) =>
      h('button', { class: 'chip' + (af === id ? ' on' : ''), onClick: () => { st.activityFilter = id; renderSide() } }, label)))
    // From Meshron: moderators' and Meshron AI's notices, right here.
    const fromMeshron = h('div', {})
    if (!st.anon && show('meshron') && window.MeshronMod && window.MeshronMod.notices) {
      const paint = (list, banned) => {
        if (!fromMeshron.isConnected && fromMeshron.parentNode) return
        const rows = (list || []).slice(0, af === 'meshron' ? 50 : 5)
        if (!rows.length && !banned) { if (af === 'meshron') fromMeshron.replaceChildren(h('p', { class: 'dim', style: { padding: '24px', textAlign: 'center' } }, 'Updates from Meshron show up here.')); return }
        fromMeshron.replaceChildren(...[h('div', { class: 'section big' }, 'From Meshron'),
          banned ? h('div', { class: 'note-row' }, h('div', { class: 'ic', style: { color: 'var(--danger)' } }, icon('warn', 24)), h('div', { class: 'grow' }, h('div', { class: 't-title' }, 'Your account is suspended'), h('div', { class: 'dim' }, banned.reason || ''))) : null,
          ...rows.map((n) => h('div', { class: 'note-row', onClick: () => { if (n.action === 'hide' || n.action === 'ban') window.MeshronMod.appeal(n) } },
            h('div', { class: 'ic' }, icon('shield', 24)),
            h('div', { style: { flex: 1, minWidth: 0 } }, h('div', { style: { display: 'flex', gap: '8px' } }, h('div', { class: 't-title', style: { flex: 1 } }, n.title), h('span', { class: 'when' }, friendlyTime(n.ts))),
              h('div', { class: 'dim' }, n.text),
              (n.action === 'hide' || n.action === 'ban') ? h('div', { style: { color: 'var(--accent)', fontWeight: 700, marginTop: '6px' } }, 'Appeal') : null)))].filter(Boolean))
      }
      if (st.noticeCache) paint(st.noticeCache.list, st.noticeCache.banned)
      window.MeshronMod.notices().then((r) => { st.noticeCache = r; paint(r.list, r.banned) }).catch(() => {})
    }
    const q = st.query.trim().toLowerCase().replace(/^@/, '')
    const list = st.contacts.filter((c) => !q || c.displayName.toLowerCase().includes(q) || (c.handle || '').includes(q))
    const requests = list.filter((c) => c.status === 'REQUEST')
    const blocked = list.filter((c) => c.status === 'BLOCKED')
    const added = list.filter((c) => c.status === 'ADDED').sort((a, b) => a.displayName.localeCompare(b.displayName))
    const kids = [chipRow]
    if (show('meshron')) kids.push(fromMeshron)
    if (!q && af === 'contacts') {
      kids.push(h('div', { class: 'row action', onClick: () => showAddContact() }, h('div', { class: 'action-circle' }, icon('userPlus')),
        h('div', { class: 'grow' }, h('div', { class: 'name' }, 'Add contact'), h('div', { class: 'sub' }, 'By @username, ID or link'))))
      kids.push(h('div', { class: 'row action', onClick: shareMe }, h('div', { class: 'action-circle' }, icon('share')),
        h('div', { class: 'grow' }, h('div', { class: 'name' }, 'Invite friends'), h('div', { class: 'sub ellipsis' }, st.me.handle ? `Send your link, or tell them @${st.me.handle}` : 'Send them your Meshron link'))))
    }
    // Active on Meshron: people using Meshron lately, to make friends with.
    if (!q && !st.anon && show('suggestions')) {
      const box = h('div', {})
      kids.push(box)
      st.hiddenActive = st.hiddenActive || new Set()
      const paintActive = (users) => {
        const known = new Set(st.contacts.map((c) => c.keyHex))
        const people = (users || []).filter((u) => /^[0-9a-f]{64}$/.test(u.id || '') && !known.has(u.id) && !st.hiddenActive.has(u.id)).slice(0, 20)
        if (!people.length) return
        box.replaceChildren(h('div', { class: 'section big' }, 'Active on Meshron'), ...people.map((u) => {
          const name = u.name || (u.handle ? '@' + u.handle : 'Someone')
          return h('div', { class: 'row', onClick: () => window.MeshronReels && window.MeshronReels.openProfile(u.ch) },
            avatar(name, u.id, 48),
            h('div', { class: 'grow' }, h('div', { class: 'top' }, h('span', { class: 'name ellipsis' }, name), verifiedBadge(u.id)),
              h('div', { class: 'sub ellipsis' }, [u.handle ? '@' + u.handle : null, u.followers ? u.followers + ' followers' : null, 'active recently'].filter(Boolean).join(' · '))),
            h('button', { class: 'btn light small', onClick: async (e) => { e.stopPropagation(); await M.call('contacts.add', { key: u.id, name }).catch(oops); st.hiddenActive.add(u.id); toast('Added ' + name) } }, 'Add'),
            h('button', { class: 'btn dark small', onClick: (e) => { e.stopPropagation(); st.hiddenActive.add(u.id); e.currentTarget.closest('.row').remove() } }, 'Remove'))
        }))
      }
      // Draw what we had (same as last time, so nothing jumps), then refresh quietly now and then.
      if (st.activeCache) paintActive(st.activeCache)
      if (!st.activeAt || Date.now() - st.activeAt > 60000) {
        st.activeAt = Date.now()
        M.call('reels.active', { limit: 40 }).then((users) => {
          const changed = JSON.stringify(users) !== JSON.stringify(st.activeCache)
          st.activeCache = users
          if (changed) renderSide()
        }).catch(() => {})
      }
    }
    if (requests.length && show('requests')) {
      kids.push(h('div', { class: 'section big' }, 'Chat requests'))
      for (const c of requests) {
        kids.push(h('div', { class: 'row', onClick: () => openChat(c.keyHex) },
          avatar(c.displayName, c.keyHex, 48),
          h('div', { class: 'grow' }, h('div', { class: 'name ellipsis' }, c.displayName), h('div', { class: 'sub ellipsis' }, c.handle ? `@${c.handle} · added you` : 'Added you')),
          h('button', { class: 'icon-btn', title: 'Ignore', onClick: (e) => { e.stopPropagation(); M.call('contacts.remove', { key: c.keyHex }) } }, icon('x', 20)),
          h('button', { class: 'btn primary small', onClick: (e) => { e.stopPropagation(); M.call('contacts.add', { key: c.keyHex, name: c.name }) } }, 'Accept')))
      }
    }
    const section = (title, people) => {
      if (!people.length) return
      kids.push(h('div', { class: 'section' }, `${title} · ${people.length}`))
      for (const c of people) {
        const on = isOnline(c.keyHex)
        const el = h('div', { class: 'row', onClick: () => openChat(c.keyHex) },
          avatar(c.displayName, c.keyHex, 48, { online: on }),
          h('div', { class: 'grow' },
            h('div', { class: 'top' }, h('span', { class: 'name ellipsis' }, c.displayName), verifiedBadge(c.keyHex)),
            h('div', { class: 'sub', style: on ? { color: 'var(--online)' } : null }, [on ? 'online' : null, c.handle ? '@' + c.handle : null].filter(Boolean).join(' · ') || 'offline')),
          h('button', { class: 'icon-btn', onClick: (e) => { e.stopPropagation(); const r = e.currentTarget.getBoundingClientRect(); contactMenu(c, r.left, r.bottom) } }, icon('more', 20)))
        onContext(el, (x, y) => contactMenu(c, x, y))
        kids.push(el)
      }
    }
    if (af === 'contacts' || q) {
      section('Online', added.filter((c) => isOnline(c.keyHex)))
      section('Offline', added.filter((c) => !isOnline(c.keyHex)))
    }
    if (af === 'contacts' && !added.length && !requests.length) {
      kids.push(h('div', { class: 'empty' }, h('div', { class: 'orb' }, icon('users', 36)),
        h('div', { class: 't-h2' }, q ? 'No match' : 'No contacts yet'),
        h('p', {}, q ? `Nobody in your contacts matches “${st.query.trim()}”.` : 'Add a friend by their @username or ID, or send them your link.')))
    }
    if (blocked.length && af === 'contacts') {
      kids.push(h('div', { class: 'section' }, 'Blocked'))
      for (const c of blocked) {
        kids.push(h('div', { class: 'row', style: { cursor: 'default' } }, avatar(c.displayName, c.keyHex, 48),
          h('div', { class: 'grow' }, h('div', { class: 'name ellipsis' }, c.displayName), h('div', { class: 'sub' }, "Can't message or call you")),
          h('button', { class: 'btn ghost small', onClick: () => M.call('contacts.unblock', { key: c.keyHex }) }, 'Unblock')))
      }
    }
    patchKids(body, kids.flat(Infinity).filter(Boolean))
  }

  // ---- Add contact, new chat, new group ------------------------------------------------

  function showAddContact(prefill) {
    let m = null
    const open = (opts) => { if (m) m.close(); m = modal(opts) }
    const choose = () => open({
      title: 'Add contact',
      body: h('div', {},
        h('div', { class: 'pick', onClick: byHandle }, h('div', { class: 'action-circle' }, icon('at')), h('div', {}, h('div', { class: 't-title' }, 'By username'), h('div', { class: 't-small dim' }, 'Search for their @username'))),
        h('div', { class: 'pick', onClick: () => byId('') }, h('div', { class: 'action-circle' }, icon('key')), h('div', {}, h('div', { class: 't-title' }, 'By ID or link'), h('div', { class: 't-small dim' }, 'Paste what they sent you'))),
        h('div', { class: 'pick', onClick: () => { m.close(); showQr() } }, h('div', { class: 'action-circle' }, icon('qr')), h('div', {}, h('div', { class: 't-title' }, 'Show my QR code'), h('div', { class: 't-small dim' }, 'Let them scan it with the phone app'))))
    })
    const add = async (key, name) => {
      try {
        await M.call('contacts.add', { key, name: name || '' })
        m.close()
        openChat(key)
        toast('Contact added')
      } catch (err) { oops(err) }
    }
    const byHandle = () => {
      const input = h('input', { class: 'input has-prefix', placeholder: 'username', autocapitalize: 'none', spellcheck: 'false', autocomplete: 'off' })
      const out = h('div', { style: { minHeight: '84px', marginTop: '12px' } }, h('p', { class: 'dim t-small' }, 'Type their exact username.'))
      let timer = null
      input.addEventListener('input', () => {
        clearTimeout(timer)
        const hh = input.value.trim().replace(/^@/, '').toLowerCase()
        if (hh.length < 3) return out.replaceChildren(h('p', { class: 'dim t-small' }, 'Type their exact username.'))
        out.replaceChildren(h('div', { style: { display: 'flex', gap: '10px', alignItems: 'center' }, class: 'dim' }, h('div', { class: 'spinner' }), 'Searching…'))
        timer = setTimeout(async () => {
          const res = await M.call('handle.find', { handle: hh }).catch(() => ({ keys: [], error: 'failed' }))
          if (input.value.trim().replace(/^@/, '').toLowerCase() !== hh) return
          if (res.error === 'offline') return out.replaceChildren(h('p', { class: 'hint error' }, "You're offline. Username search needs the internet."))
          if (!res.keys.length) return out.replaceChildren(h('p', { class: 'dim' }, `Nobody online is using @${hh} right now. They need to have Meshron open.`))
          out.replaceChildren(...res.keys.map((key) => {
            const c = contact(key)
            const known = c && c.status === 'ADDED'
            return h('div', { class: 'pick', style: { cursor: 'default' } }, avatar(c ? c.displayName : '@' + hh, key, 46),
              h('div', { style: { flex: 1, minWidth: 0 } }, h('div', { class: 't-title ellipsis' }, c && c.name ? c.name : '@' + hh), h('div', { class: 't-small dim' }, 'ID ' + shortKey(key))),
              known ? h('button', { class: 'btn soft small', onClick: () => { m.close(); openChat(key) } }, 'Message') : h('button', { class: 'btn primary small', onClick: () => add(key, '') }, 'Add'))
          }))
        }, 450)
      })
      open({ title: 'Find by username', back: choose, body: h('div', {}, h('div', { class: 'input-wrap' }, h('span', { class: 'prefix' }, '@'), input), out) })
    }
    const byId = (text) => {
      const input = h('textarea', { class: 'input', rows: 3, placeholder: 'Paste a Meshron ID or meshron:// link', spellcheck: 'false', autocapitalize: 'none' })
      const hint = h('div', { class: 'hint' })
      const name = h('input', { class: 'input', placeholder: 'Name (optional)', maxlength: 64 })
      let parsed = null
      const btn = h('button', { class: 'btn primary wide', style: { marginTop: '14px' }, disabled: true, onClick: () => parsed && add(parsed.keyHex, name.value || parsed.name) }, 'Add contact')
      const check = async () => {
        parsed = null
        btn.disabled = true
        const v = input.value.trim()
        if (!v) { hint.textContent = ''; return }
        const p = await M.call('contacts.parse', { text: v })
        if (input.value.trim() !== v) return
        if (p.error) { hint.textContent = p.error; hint.className = 'hint error'; return }
        parsed = p
        hint.textContent = 'Looks good' + (p.name ? `: ${p.name}` : '')
        hint.className = 'hint ok'
        if (p.name && !name.value) name.value = p.name
        btn.disabled = false
      }
      input.addEventListener('input', check)
      open({ title: 'Add by ID or link', back: choose, body: h('div', {}, input, hint, h('label', { class: 'field' }, name), btn) })
      if (text) { input.value = text; check() }
    }
    if (prefill) byId(prefill)
    else choose()
  }

  function showNewChat() {
    const added = st.contacts.filter((c) => c.status === 'ADDED').sort((a, b) => a.displayName.localeCompare(b.displayName))
    const m = modal({
      title: 'New chat',
      body: h('div', {},
        h('div', { class: 'pick', onClick: () => { m.close(); showAddContact() } }, h('div', { class: 'action-circle' }, icon('userPlus')), h('div', {}, h('div', { class: 't-title' }, 'Add a contact'), h('div', { class: 't-small dim' }, 'By @username, ID or link'))),
        h('div', { class: 'pick', onClick: () => { m.close(); showNewGroup() } }, h('div', { class: 'action-circle' }, icon('users')), h('div', {}, h('div', { class: 't-title' }, 'New group'), h('div', { class: 't-small dim' }, 'Chat with several contacts at once'))),
        added.length ? h('div', { class: 'section', style: { padding: '14px 8px 4px' } }, 'Your contacts') : null,
        added.map((c) => h('div', { class: 'pick', onClick: () => { m.close(); openChat(c.keyHex) } }, avatar(c.displayName, c.keyHex, 44, { online: isOnline(c.keyHex) }),
          h('div', { style: { minWidth: 0 } }, h('div', { class: 't-title ellipsis' }, c.displayName), h('div', { class: 't-small dim' }, isOnline(c.keyHex) ? 'Online' : "Offline · they'll get messages later")))))
    })
  }

  function showNewGroup() {
    const added = st.contacts.filter((c) => c.status === 'ADDED').sort((a, b) => a.displayName.localeCompare(b.displayName))
    const picked = new Set()
    const name = h('input', { class: 'input', placeholder: 'e.g. Family', maxlength: 48 })
    const list = h('div', { style: { maxHeight: '300px', overflowY: 'auto', marginTop: '8px' } })
    const count = h('div', { class: 'section', style: { padding: '14px 4px 0' } })
    // 18+ or for everyone (only adults can make an 18+ group).
    const adultBox = h('input', { type: 'checkbox', disabled: true })
    const adultHint = h('div', { class: 't-small dim' }, 'For everyone, teens included. Meshron AI keeps the rules strict.')
    adultBox.addEventListener('change', () => { adultHint.textContent = adultBox.checked ? 'Adults only. Looser rules: adult talk and swearing are fine; harassment, threats, scams and anything involving minors are never allowed.' : 'For everyone, teens included. Meshron AI keeps the rules strict.' })
    M.call('age.get').then((a) => { adultBox.disabled = !(a && a.adult); if (!(a && a.adult)) adultHint.textContent = 'Only adults (18 and over) can make 18+ groups.' }).catch(() => {})
    const adultRow = h('label', { class: 'set-row', style: { cursor: 'pointer', alignItems: 'flex-start' } }, adultBox, h('div', { class: 'grow' }, h('div', { class: 't-title' }, '18+ group'), adultHint))
    let m = null
    const refresh = () => {
      count.textContent = `Who's in it? (${picked.size} picked)`
      list.replaceChildren(...added.map((c) => h('div', { class: 'pick', onClick: () => { picked.has(c.keyHex) ? picked.delete(c.keyHex) : picked.add(c.keyHex); refresh() } },
        avatar(c.displayName, c.keyHex, 42), h('div', { class: 't-title ellipsis', style: { flex: 1 } }, c.displayName),
        h('div', { class: 'checkbox' + (picked.has(c.keyHex) ? ' on' : '') }, icon('check', 16)))))
      const btn = m && m.box.querySelector('.btn.primary')
      if (btn) btn.disabled = !(name.value.trim() && picked.size)
    }
    name.addEventListener('input', refresh)
    m = modal({
      title: 'New group',
      body: added.length ? h('div', {}, h('label', { class: 'field', style: { marginTop: 0 } }, h('span', {}, 'Group name'), name), adultRow, count, list)
        : h('p', { class: 'dim' }, "You need contacts first. Only people you've added can join a group."),
      buttons: added.length ? [{ label: 'Cancel', onClick: (x) => x.close() }, {
        label: 'Create', kind: 'primary', disabled: true, onClick: async (x) => {
          try {
            const conv = await M.call('group.create', { name: name.value, members: [...picked], adult: !!(adultBox && adultBox.checked) })
            x.close()
            setTimeout(() => openChat(conv), 80)
          } catch (err) { oops(err) }
        }
      }] : [{ label: 'OK', onClick: (x) => x.close() }]
    })
    refresh()
  }

  // ---- Me ------------------------------------------------------------------------

  const setRow = (ic, color, title, o = {}) => h('div', { class: 'set-row', onClick: o.onClick },
    h('div', { class: 'tile', style: { background: color } }, icon(ic, 19)),
    h('div', { class: 'grow' }, h('div', { class: 't-title', style: { fontWeight: 500 } }, title), o.sub ? h('div', { class: 't-small dim' }, o.sub) : null),
    o.value ? h('span', { class: 'value ellipsis' }, o.value) : null,
    o.checked != null ? h('div', { class: 'switch' + (o.checked ? ' on' : '') }) : h('span', { class: 'dimmer' }, icon('right', 18)))

  function renderMe(body) {
    const kinds = st.anon ? [] : [['user', 'grid', 'Videos'], ['private', 'lock', 'Private'], ['reposts', 'share', 'Reposts'], ['favourites', 'bookmark', 'Saved'], ['liked', 'heart', 'Liked']]
    st.meTab = st.meTab || 'user'
    const grid = h('div', { class: 'me-grid' })
    const tabs = h('div', { class: 'me-tabs' }, ...kinds.map(([id, ic, label]) => h('button', { class: st.meTab === id ? 'on' : '', title: label, onClick: () => { st.meTab = id; renderSide() } }, icon(ic, 24))))
    patchKids(body, [window.MeshronProfile.head(), kinds.length ? tabs : null, grid].filter(Boolean))
    if (!kinds.length) return
    st.meCache = st.meCache || {}
    const tile = (it) => h('div', { class: 'tile-v', onClick: () => { setTab('reels'); setTimeout(() => window.MeshronReels.openReel(it.ch, it.id), 60) } },
      it.thumb ? h('img', { src: window.MeshronReels.imgSrc(it.thumb), alt: '' }) : null,
      h('span', { class: 'n' }, icon('heart', 14), String(it.likes || 0)))
    if (st.meCache[st.meTab] && st.meCache[st.meTab].length) grid.replaceChildren(...st.meCache[st.meTab].map(tile))
    const wanted = st.meTab
    ;(async () => {
      try {
        const s = await M.call('reels.status')
        if (!s || !s.ch) { grid.replaceChildren(h('p', { class: 'dim', style: { gridColumn: '1 / -1', padding: '28px', textAlign: 'center' } }, 'Videos are starting…')); return }
        const r = await M.call('reels.feed', { kind: st.meTab, ch: s.ch, limit: 60 })
        const items = r.items || []
        st.meCache[wanted] = items
        if (st.meTab !== wanted || !grid.isConnected) return
        if (!items.length) { grid.replaceChildren(h('p', { class: 'dim', style: { gridColumn: '1 / -1', padding: '28px', textAlign: 'center' } }, st.meTab === 'user' ? 'Your videos show up here. Tap + to post one.' : 'Nothing here yet.')); return }
        grid.replaceChildren(...items.map(tile))
      } catch (e) { grid.replaceChildren(h('p', { class: 'dim', style: { gridColumn: '1 / -1', padding: '28px', textAlign: 'center' } }, e.message)) }
    })()
  }

  /** Settings (the gear on Me): a full page, like Android. */
  function showSettings() {
    const page = h('div', { class: 'page-full' })
    const pbody = h('div', { class: 'pbody' })
    const close = () => { page.remove(); window.removeEventListener('keydown', esc) }
    const esc = (e) => { if (e.key === 'Escape') close() }
    window.addEventListener('keydown', esc)
    page.append(h('div', { class: 'phead' }, h('button', { class: 'icon-btn', title: 'Back', onClick: close }, icon('back', 24)), h('h1', {}, 'Settings')), pbody)
    document.body.appendChild(page)
    const draw = () => { settingsBody(pbody) }
    st.redrawSettings = () => { if (page.isConnected) draw() }
    draw()
  }
  function settingsBody(body) {
    const me = st.me
    const s = st.prefs.settings
    const toggle = (key) => () => M.call('prefs.set', { key, value: !s[key] }).then(() => st.redrawSettings && st.redrawSettings()).catch(oops)
    const dht = st.net.dht
    body.replaceChildren(
      h('div', { class: 'group', style: { marginTop: '8px' } },
        setRow('copy', '#3D7BE0', 'Copy my Meshron ID', { sub: me.id, onClick: () => copy(me.id, 'ID copied') })),
      h('div', { style: { height: '12px' } }),
      h('button', { class: 'mode-card' + (st.anon ? ' on' : ''), onClick: confirmSwitch },
        h('div', { class: 'orb' }, icon(st.anon ? 'globe' : 'eyeOff', 22)),
        h('div', { class: 'grow' },
          h('div', { class: 't-title' }, st.anon ? "You're anonymous" : 'Go anonymous'),
          h('div', { class: 't-small dim' }, st.anon ? 'Separate identity, nothing links it to you. Tap to go back to public.' : 'A separate identity nobody can link to you, with every privacy feature on.'))),
      h('div', { class: 'section' }, 'Privacy'),
      h('div', { class: 'group' },
        setRow('lock', '#5F55D6', 'App lock', { sub: st.locked ? 'PIN on · locks after a minute away' : 'Ask for a PIN to open Meshron', checked: !!st.locked, onClick: toggleLock }),
        setRow('clock', '#239C8E', 'Disappearing messages', { value: hoursLabel(s.disappearHours || 0), onClick: cycleDisappear }),
        setRow('shield', '#2F9E5B', 'Hide my IP address', { sub: 'Internet links go through relays (slower). Applies after restart.', checked: !!s.hideIp, onClick: toggle('hideIp') }),
        setRow('userPlus', '#3D7BE0', 'Chat requests', { sub: s.allowRequests !== false ? "People who aren't your contacts can ask to chat" : 'Off: only your contacts can reach you', checked: s.allowRequests !== false, onClick: toggle('allowRequests') }),
        st.anon ? null : setRow('chat', '#D9822B', 'Who can comment on my videos', { value: { everyone: 'Everyone', followers: 'Followers', friends: 'Friends', off: 'No one' }[s.cmFrom || 'everyone'], onClick: cycleComments })),
      st.anon ? null : h('div', { class: 'section' }, 'Meshron'),
      st.anon ? null : h('div', { class: 'group' },
        setRow('shield', '#5F55D6', 'From Meshron', { sub: 'Updates on your reports, removals and appeals', value: noticeCount ? String(noticeCount) : '', onClick: () => { noticeCount = 0; window.MeshronMod.showNotices() } }),
        setRow('verified', '#3D7BE0', 'Request verification', { sub: 'For known people, creators and brands', onClick: () => window.MeshronMod.requestVerification() })),
      h('div', { class: 'section' }, 'Account'),
      h('div', { class: 'group' },
        setRow('user', '#3D7BE0', 'Name', { value: me.username, onClick: editName }),
        st.anon ? null : setRow('at', '#5F55D6', 'Username', { value: me.handle ? '@' + me.handle : 'Not set', onClick: editHandle }),
        setRow('key', '#D9822B', 'Recovery key', { sub: 'Restore your ID on a new device', onClick: showRecovery }),
        st.anon ? null : (window.MeshronAccount && window.MeshronAccount.state
          ? setRow('lock', '#2F9E5B', 'Meshron account', { sub: 'Signed in as @' + window.MeshronAccount.state.username + ' · sign in on new devices with your password', onClick: async () => { if (await confirmBox('Sign out of your account here?', 'Your ID and chats stay on this device. You only stop using the account (AI, translation) until you sign in again.', 'Sign out')) M.call('account.forget').then(() => { window.MeshronAccount.refresh(); renderSide() }) } })
          : setRow('lock', '#2F9E5B', 'Create an account', { sub: 'Username, password and email, so you can sign in on new devices', onClick: () => window.MeshronAccount.showSignUp() })),
        st.anon || !(window.MeshronAccount && window.MeshronAccount.state) ? null
          : setRow('at', '#3D7BE0', 'Log out · switch account', { sub: 'Sign in as @' + window.MeshronAccount.state.username + ' or another account', onClick: async () => {
            const u = window.MeshronAccount.state.username
            if (await confirmBox('Log out of @' + u + '?', 'Meshron keeps chats on your device, not on a server. Logging out clears this device: chats, files and settings here are removed. Your account, profile and videos stay: sign in again (or with another account) on the next screen.', 'Log out', true)) M.call('app.logout').catch(oops)
          } })),
      h('div', { class: 'section' }, 'Connections'),
      h('div', { class: 'group' },
        setRow('globe', '#3D7BE0', 'Internet', { sub: 'Reach contacts anywhere', checked: s.useInternet !== false, onClick: toggle('useInternet') }),
        setRow('share', '#2F9E5B', 'Pass messages on', { sub: dht && dht.relaying ? 'Helping others connect right now' : "Carry and relay others' locked messages", checked: s.carry !== false, onClick: toggle('carry') })),
      h('p', { class: 'footnote' }, 'Messages are end-to-end encrypted on every route. Phones nearby can also connect over Wi-Fi or Bluetooth in the Android app.'),
      h('div', { class: 'section' }, 'Notifications'),
      h('div', { class: 'group' },
        setRow('bell', '#C94F72', 'Message alerts', { sub: isIos ? 'While Meshron is open' : 'While Meshron is open or minimised', checked: !!s.notifications, onClick: toggle('notifications') }),
        // Messages travel device to device, so alerts after closing need Meshron to stay connected.
        // Off by default (closing quits, no battery used); the person allows it here.
        isIos ? null : setRow('bellOff', '#3D7BE0', 'Alerts when Meshron is closed', { sub: s.background ? 'On: Meshron stays in the tray and starts with Windows' : 'Off: closing Meshron quits it completely', checked: !!s.background, onClick: async () => {
          if (!s.background && !(await confirmBox('Allow alerts when Meshron is closed?', 'Meshron sends messages straight between devices, so to tell you about new messages it keeps a small connection running in the tray and starts with Windows. This uses a little battery. You can turn it off here any time.', 'Allow'))) return
          toggle('background')()
        } }),
        setRow('volume', '#7A5CC2', 'Sounds', { checked: !!s.sounds, onClick: toggle('sounds') }),
        isIos ? null : setRow('enter', '#239C8E', 'Enter sends', { sub: 'Shift+Enter makes a new line', checked: s.enterSends !== false, onClick: toggle('enterSends') })),
      h('div', { class: 'section' }, 'Legal'),
      h('div', { class: 'group' }, ...['TERMS', 'PRIVACY', 'GUIDELINES'].map((k) => setRow('doc', '#5B5B66', window.MeshronLegal.docs[k].title, { onClick: () => showLegal(window.MeshronLegal.docs[k]) }))),
      h('div', { class: 'section' }, 'About'),
      h('div', { class: 'group' },
        setRow('info', '#5B5B66', 'About Meshron', { sub: 'Version ' + VERSION, onClick: showAbout }),
        setRow('sliders', '#5B5B66', 'Connection details', { sub: 'For troubleshooting', onClick: showConnection })),
      h('div', { class: 'section' }, 'Emergency'),
      h('div', { class: 'group' },
        setRow('warn', '#E8243C', 'Emergency SOS', { sub: 'Send an urgent message with your location to all your contacts', onClick: showSos })),
      h('div', { style: { height: '24px' } }))
  }

  let noticeCount = 0
  async function refreshNoticeCount() {
    if (st.anon || !window.MeshronMod) return
    noticeCount = await window.MeshronMod.unseenNotices()
  }
  /** Everyone → followers → friends → no one. Remembered here, enforced by the Reels worklet. */
  async function cycleComments() {
    const order = ['everyone', 'followers', 'friends', 'off']
    const s = st.prefs.settings
    const next = order[(order.indexOf(s.cmFrom || 'everyone') + 1) % order.length]
    try { await M.call('reels.cmFrom', { v: next }); await M.call('prefs.set', { key: 'cmFrom', value: next }); s.cmFrom = next; renderSide() } catch (e) { oops(e) }
  }

  const hoursLabel = (h) => ({ 0: 'Off', 1: '1 hour', 24: '24 hours', 168: '7 days' })[h] || h + ' h'
  function cycleDisappear() {
    const steps = [0, 1, 24, 168]
    const cur = steps.indexOf(st.prefs.settings.disappearHours || 0)
    M.call('prefs.set', { key: 'disappearHours', value: steps[(cur + 1) % steps.length] }).catch(oops)
  }

  function confirmSwitch() {
    const to = !st.anon
    modal({
      title: to ? 'Go anonymous?' : 'Go public?',
      body: h('p', { class: 'dim' }, to
        ? 'Meshron restarts as a separate anonymous identity: a random name, no @username, photo or bio, connections only through relays (your IP stays hidden), messages that disappear after 24 hours, screenshots blocked and silent notifications. Your public chats stay hidden until you switch back, and messages to them arrive then.'
        : 'Meshron restarts as your public self: your name, @username, photo and public chats. Your anonymous identity and its chats stay hidden until you switch back.'),
      buttons: [{ label: 'Cancel' }, { label: 'Switch', kind: 'primary', onClick: async (m) => { m.close(); restarting(); await M.call('mode.switch', { anon: to }).catch(oops) } }]
    })
  }
  function restarting() {
    if ($('#restarting')) return
    document.body.appendChild(h('div', { class: 'lock-screen', id: 'restarting' }, h('div', { class: 'lock-orb' }, icon('eyeOff', 30)), h('div', { class: 't-h1' }, 'Switching…')))
    // A plain browser (dev server) reloads itself once the host is back.
    if (M.platform === 'web') setTimeout(function again () { fetch(location.href).then(() => location.reload(), () => setTimeout(again, 700)) }, 1500)
  }

  async function toggleLock() {
    if (st.locked) {
      await M.call('lock.set', { pin: '' }).catch(oops)
      st.locked = false
      return renderSideSoon()
    }
    const first = await pinPad({ title: 'Choose a PIN', sub: '6 digits. Needed to open Meshron.', cancel: true })
    if (!first) return
    const again = await pinPad({ title: 'Repeat it', sub: '6 digits. Needed to open Meshron.', cancel: true, check: async (p) => p === first })
    if (!again) return
    await M.call('lock.set', { pin: first }).catch(oops)
    st.locked = true
    toast('App lock on')
    renderSideSoon()
  }

  /** PIN pad over everything. Resolves with the PIN (or null when cancelled). */
  function pinPad({ title, sub, cancel, check }) {
    return new Promise((resolve) => {
      let pin = ''
      let wrong = 0
      let waitUntil = 0
      let busy = false
      const dots = h('div', { class: 'pin-dots' })
      const msg = h('div', { class: 'dim' }, sub)
      const draw = () => dots.replaceChildren(...[0, 1, 2, 3, 4, 5].map((i) => h('span', { class: i < pin.length ? 'on' : '' })))
      const done = (v) => { el.remove(); document.removeEventListener('keydown', onKey, true); resolve(v) }
      const press = async (k) => {
        if (busy || Date.now() < waitUntil) return
        if (k === '<') pin = pin.slice(0, -1)
        else if (pin.length < 6) pin += k
        draw()
        if (pin.length < 6) return
        const p = pin
        busy = true
        const ok = check ? await check(p).catch(() => false) : true
        busy = false
        if (ok) return done(p)
        wrong++
        pin = ''
        draw()
        msg.textContent = 'Wrong PIN'
        msg.classList.add('error')
        dots.classList.remove('shake'); void dots.offsetWidth; dots.classList.add('shake')
        if (wrong % 5 === 0) {
          waitUntil = Date.now() + 30000
          const tick = () => { const left = Math.ceil((waitUntil - Date.now()) / 1000); if (left > 0) { msg.textContent = 'Try again in ' + left + ' s'; setTimeout(tick, 500) } else msg.textContent = 'Enter your PIN' }
          tick()
        }
      }
      const onKey = (e) => {
        if (/^[0-9]$/.test(e.key)) press(e.key)
        else if (e.key === 'Backspace') press('<')
        else if (e.key === 'Escape' && cancel) done(null)
        else return
        e.preventDefault(); e.stopPropagation()
      }
      document.addEventListener('keydown', onKey, true)
      const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', '<']
      const el = h('div', { class: 'lock-screen' },
        h('div', { class: 'lock-orb' }, icon('lock', 30)),
        h('div', { class: 't-h1' }, title), msg, dots,
        h('div', { class: 'pin-keys' }, ...keys.map((k) => k ? h('button', { 'aria-label': k === '<' ? 'Delete' : k, onClick: () => press(k) }, k === '<' ? icon('back', 22) : k) : h('span'))),
        cancel ? h('button', { class: 'pin-cancel', onClick: () => done(null) }, 'Cancel') : null)
      draw()
      document.body.appendChild(el)
    })
  }
  const unlock = () => pinPad({ title: st.anon ? 'Anonymous' : 'Meshron', sub: 'Enter your PIN', check: (p) => M.call('lock.check', { pin: p }) })
  // Locks again after a minute away.
  let hiddenAt = 0
  let unlocking = false
  document.addEventListener('visibilitychange', async () => {
    if (document.hidden) { hiddenAt = Date.now(); return }
    if (st.locked && hiddenAt && Date.now() - hiddenAt > 60000 && !unlocking) { unlocking = true; await unlock(); unlocking = false }
  })

  function qrSvg(text) {
    const qr = qrcode(0, 'M')
    qr.addData(text)
    qr.make()
    const n = qr.getModuleCount()
    let d = ''
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c} ${r}h1v1h-1z`
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('viewBox', `0 0 ${n} ${n}`)
    svg.setAttribute('shape-rendering', 'crispEdges')
    svg.innerHTML = `<path d="${d}" fill="#111"/>`
    return svg
  }

  function showQr() {
    modal({
      title: 'My QR code',
      body: h('div', { style: { textAlign: 'center' } },
        h('div', { class: 'qr-box' }, qrSvg(st.me.shareLink)),
        h('div', { class: 't-title' }, st.me.username),
        h('p', { class: 'dim t-small', style: { marginTop: '4px' } }, 'A friend scans this in the Meshron phone app to add you.')),
      buttons: [{ label: 'Copy link', onClick: () => copy(st.me.shareLink, 'Link copied') }, { label: 'Share', kind: 'primary', onClick: (m) => { m.close(); shareMe() } }]
    })
  }

  /** Profile photo: square-cropped to 256 px JPEG (~15 KB) before it's shared. */
  function pickProfilePhoto() {
    const input = h('input', { type: 'file', accept: 'image/*' })
    input.onchange = async () => {
      const file = input.files[0]
      if (!file) return
      try {
        const bmp = await createImageBitmap(file)
        const side = Math.min(bmp.width, bmp.height)
        const c = document.createElement('canvas')
        c.width = c.height = 256
        c.getContext('2d').drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, 256, 256)
        const url = c.toDataURL('image/jpeg', 0.82)
        st.profiles = await M.call('profile.set', { photo: url.slice(url.indexOf(',') + 1) })
        renderSide()
        toast('Profile photo updated')
      } catch (err) { oops(err) }
    }
    input.click()
  }
  function editBio() {
    const cur = (st.profiles.me || {}).bio || ''
    const input = h('textarea', { class: 'input', rows: 3, maxlength: 150, placeholder: 'A few words about you' })
    input.value = cur
    const count = h('div', { class: 'hint' }, cur.length + '/150 · your contacts see this')
    input.addEventListener('input', () => { count.textContent = input.value.length + '/150 · your contacts see this' })
    const buttons = [
      { label: 'Cancel', onClick: (m) => m.close() },
      { label: 'Save', kind: 'primary', onClick: async (m) => { m.close(); st.profiles = await M.call('profile.set', { bio: input.value }).catch(oops) || st.profiles; renderSide() } }
    ]
    if ((st.profiles.me || {}).photo) buttons.unshift({ label: 'Remove photo', kind: 'soft danger', onClick: async (m) => { m.close(); st.profiles = await M.call('profile.set', { photo: '' }).catch(oops) || st.profiles; renderSide() } })
    modal({ title: 'Your bio', body: h('div', {}, input, count), buttons })
  }

  /** Someone's profile: photo, name, @username, bio, ID and quick actions. */
  function showPerson(key) {
    const c = contact(key)
    if (!c) return showSafety(key)
    const online = isOnline(key)
    const bio = bioOf(key)
    const act = (ic, label, fn) => h('button', { class: 'd-act', onClick: (e) => { m.close(); fn(e) } }, h('span', {}, icon(ic, 20)), label)
    const m = modal({
      title: '',
      body: h('div', { class: 'person' },
        avatar(c.displayName, key, 112, { online }),
        h('div', { class: 'p-name' }, c.displayName, c.verified ? h('span', { class: 'd-ver', title: 'Safety code verified' }, icon('verified', 18)) : null),
        h('div', { class: 'p-sub' + (online ? ' on' : '') }, [c.handle ? '@' + c.handle : null, online ? 'online' : 'offline'].filter(Boolean).join(' · ')),
        bio ? h('p', { class: 'p-bio' }, bio) : null,
        h('div', { class: 'd-actions' },
          c.status === 'ADDED' ? act('phone', 'Call', () => startCall(key, false)) : null,
          c.status === 'ADDED' ? act('video', 'Video', () => startCall(key, true)) : null,
          act('shield', c.verified ? 'Verified' : 'Verify', () => showSafety(key))),
        h('div', { class: 'd-label', style: { marginTop: '14px' } }, 'Meshron ID'),
        h('button', { class: 'd-id', onClick: () => copy(c.id, 'ID copied') }, c.id))
    })
  }

  function editName() {
    const input = h('input', { class: 'input', value: st.me.username, maxlength: 40 })
    const save = async (m) => {
      try { await M.call('identity.rename', { name: input.value }); m.close() } catch (err) { oops(err) }
    }
    const m = modal({ title: 'Your name', body: h('div', {}, input, h('div', { class: 'hint' }, 'Your contacts see this name.')), buttons: [{ label: 'Cancel', onClick: (x) => x.close() }, { label: 'Save', kind: 'primary', onClick: save }] })
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(m) })
  }

  function editHandle() {
    const input = h('input', { class: 'input has-prefix', value: st.me.handle, maxlength: 24, autocapitalize: 'none', spellcheck: 'false' })
    const hint = h('div', { class: 'hint' }, 'Friends can find you by it. Leave empty to have none.')
    let timer = null
    let ok = true
    let m = null
    const setOk = (v) => { ok = v; const b = m && m.box.querySelector('.btn.primary'); if (b) b.disabled = !v }
    input.addEventListener('input', () => {
      clearTimeout(timer)
      const hh = input.value.trim().replace(/^@/, '').toLowerCase()
      if (!hh || hh === st.me.handle) { hint.textContent = ' '; hint.className = 'hint'; return setOk(true) }
      if (!/^[a-z0-9_]{3,24}$/.test(hh)) { hint.textContent = '3–24 letters, numbers or _'; hint.className = 'hint error'; return setOk(false) }
      hint.textContent = 'Checking…'
      hint.className = 'hint'
      setOk(false)
      timer = setTimeout(async () => {
        const res = await M.call('handle.find', { handle: hh }).catch(() => ({ keys: [] }))
        if (input.value.trim().replace(/^@/, '').toLowerCase() !== hh) return
        if (res.keys.length) { hint.textContent = 'Someone online is already using that'; hint.className = 'hint error'; return setOk(false) }
        hint.textContent = res.error === 'offline' ? "Can't check while offline, but you can save it" : 'Nobody online is using it'
        hint.className = 'hint ok'
        setOk(true)
      }, 500)
    })
    m = modal({
      title: 'Username', body: h('div', {}, h('div', { class: 'input-wrap' }, h('span', { class: 'prefix' }, '@'), input), hint),
      buttons: [{ label: 'Cancel', onClick: (x) => x.close() }, { label: 'Save', kind: 'primary', onClick: async (x) => { if (!ok) return; try { await M.call('identity.setHandle', { handle: input.value }); x.close() } catch (err) { oops(err) } } }]
    })
  }

  function showRecovery() {
    const box = h('div', {})
    const reveal = async () => {
      try {
        const key = await M.call('identity.recoveryKey')
        box.replaceChildren(
          h('div', { class: 'key-box selectable' }, key.match(/.{1,4}/g).join(' ')),
          h('div', { style: { display: 'flex', justifyContent: 'center', marginTop: '10px' } }, h('button', { class: 'btn ghost small', onClick: () => copy(key, 'Recovery key copied. Paste it somewhere safe.') }, icon('copy', 16), 'Copy')))
      } catch (err) { oops(err) }
    }
    box.appendChild(h('button', { class: 'btn soft wide', onClick: reveal }, icon('key', 18), 'Show my recovery key'))
    modal({
      title: 'Recovery key',
      body: h('div', {},
        h('p', { class: 'dim' }, 'These 52 characters bring your Meshron ID back on a new device. Write them down and keep them somewhere safe.'),
        h('div', { class: 'warn' }, icon('warn', 20), h('span', {}, 'Anyone who has this key can be you. Never send it to anyone, and never type it into a website.')),
        box)
    })
  }

  function showAbout() {
    modal({
      title: 'About Meshron',
      body: h('div', {},
        h('div', { style: { display: 'flex', gap: '14px', alignItems: 'center', marginBottom: '14px' } },
          h('div', { class: 'ob-logo', style: { width: '54px', height: '54px', borderRadius: '17px' } }, icon('logo', 28)),
          h('div', {}, h('div', { class: 't-h2' }, 'Meshron'), h('div', { class: 't-small dim' }, 'Version ' + VERSION + ' · by xalvxnLab'))),
        h('p', { class: 'dim' }, 'Private messages, device to device. No accounts, nothing stored on servers. Messages are end-to-end encrypted, and everything on this device is stored encrypted.'),
        h('div', { class: 'group', style: { margin: '16px 0 0' } },
          h('div', { class: 'set-row', onClick: () => window.open(PRIVACY_URL, '_blank') }, h('div', { class: 'grow' }, 'Privacy policy'), h('span', { class: 'dimmer' }, icon('right', 18))),
          h('div', { class: 'set-row', onClick: () => window.open(DOWNLOAD_URL, '_blank') }, h('div', { class: 'grow' }, 'Get Meshron for other devices'), h('span', { class: 'dimmer' }, icon('right', 18)))))
    })
  }

  function showConnection() {
    const n = st.net
    const d = n.dht || {}
    const kv = (a, b) => h('div', { class: 'kv' }, h('span', {}, a), h('span', { class: 'selectable' }, b))
    modal({
      title: 'Connection details', wide: true,
      body: h('div', {},
        kv('Network', netLabel()[0]),
        kv('Contacts connected', String(Object.values(n.peers).filter((p) => p.state === 'connected').length)),
        kv('Public address', d.host ? `${d.host}:${d.port}` : '–'),
        kv('Connection type', d.host ? (d.firewalled ? 'Behind a firewall' : 'Open') + (d.randomized ? ', strict NAT' : '') : '–'),
        kv('Relays known', String(d.relays || 0)),
        kv('Relaying for others', d.relaying ? 'Yes' : 'No'),
        kv('Key matches ID', n.keyMatches == null ? '–' : n.keyMatches ? 'Yes' : 'NO'),
        kv('Storage', 'Encrypted on this device'),
        n.lastError ? h('p', { class: 'hint error' }, n.lastError) : null,
        h('div', { class: 'log selectable' }, (n.log || []).slice(-25).join('\n') || '–'))
    })
  }

  function showSafety(key) {
    const c = contact(key)
    M.call('contacts.safetyCode', { key }).then((code) => {
      const m = modal({
        title: 'Safety code',
        body: h('div', {},
          h('p', { class: 'dim' }, `Compare these numbers with ${nameOf(key)}, in person or on a call. If they match on both devices, nobody is listening in.`),
          h('div', { class: 'code-grid selectable' }, code.map((x) => h('span', {}, x))),
          c && c.verified ? h('p', { class: 'hint ok' }, 'You marked this contact as verified.') : null),
        buttons: [c && c.verified
          ? { label: 'Remove verified mark', onClick: (x) => { M.call('contacts.setVerified', { key, verified: false }); x.close() } }
          : { label: 'They match', kind: 'primary', onClick: (x) => { M.call('contacts.setVerified', { key, verified: true }); x.close(); toast('Marked as verified') } }]
      })
      return m
    }).catch(oops)
  }

  // ---- Chat ----------------------------------------------------------------------

  const main = $('#main')
  const blobs = new Map() // "conv|id" -> object URL
  let chatEls = null

  function showHero() {
    main.replaceChildren(h('div', { class: 'hero' }, h('div', { class: 'empty' },
      h('div', { class: 'orb' }, icon('logo', 40)),
      h('div', { class: 't-h1' }, 'Meshron'),
      h('p', {}, 'Pick a chat on the left, or start a new one. Messages go straight to your friends, end-to-end encrypted.'),
      h('div', { class: 'actions' }, h('button', { class: 'btn primary', onClick: showNewChat }, icon('edit', 18), 'New chat'),
        h('button', { class: 'btn soft', onClick: () => showAddContact() }, icon('userPlus', 18), 'Add contact')),
      h('div', { class: 'keys' },
        [['Ctrl', 'K', 'Search'], ['Ctrl', 'N', 'New chat'], ['Alt', '↑ ↓', 'Switch chat'], ['Ctrl', 'I', 'Chat details'], ['Esc', '', 'Close']].map(([a, b, t]) =>
          h('div', {}, h('kbd', {}, a), b ? h('kbd', {}, b) : null, h('span', {}, t)))))))
    main.classList.remove('with-details')
    chatEls = null
  }

  async function openChat(conv, jumpTo) {
    if (st.tab === 'reels') setTab('chats')
    if (st.open === conv && !jumpTo) { $('#shell').classList.add('chat-open'); return }
    saveDraft()
    st.open = conv
    st.shown = PAGE
    st.flash = jumpTo || null
    st.pendingBelow = 0
    st.rows = await M.call('chat.open', { conv })
    if (st.open !== conv) return
    const firstUnread = st.rows.find((r) => !r.outgoing && !r.read)
    st.firstUnread = firstUnread ? firstUnread.id : null
    st.seen = new Set(st.rows.map((r) => r.id))
    if (jumpTo) {
      const i = st.rows.findIndex((r) => r.id === jumpTo)
      if (i >= 0) st.shown = Math.max(PAGE, st.rows.length - i + 20)
    }
    buildChat()
    $('#shell').classList.add('chat-open')
    renderSide()
    markRead()
  }

  function closeChat() {
    saveDraft()
    st.open = null
    $('#shell').classList.remove('chat-open')
    // On phones the chat slides away first; the welcome panel only matters on Windows.
    if (window.innerWidth <= 820) setTimeout(() => { if (!st.open) showHero() }, 460)
    else showHero()
    renderSide()
  }

  function markRead() {
    if (st.open && M.native.focusState() && st.rows.some((r) => !r.outgoing && !r.read)) M.call('chat.markRead', { conv: st.open })
  }

  function saveDraft() {
    if (!st.open || !chatEls) return
    const text = chatEls.input.value.trim()
    if ((st.prefs.drafts[st.open] || '') === text) return
    if (text) st.prefs.drafts[st.open] = text
    else delete st.prefs.drafts[st.open]
    M.call('prefs.draft', { conv: st.open, text })
  }

  function headerStatus(conv) {
    if (isGroup(conv)) {
      const g = group(conv)
      if (!g) return ['', false]
      const others = g.members.filter((k) => k !== st.me.publicKey)
      const on = others.filter(isOnline).length
      return [(g.adult ? "18+ · " : "") + `${g.members.length} members` + (on ? ` · ${on} online` : ''), false]
    }
    const c = contact(conv)
    if (c && c.status === 'REQUEST') return ['Wants to chat with you', false]
    if ((st.typing[conv] || 0) > Date.now()) return [(st.typingWhat && st.typingWhat[conv]) === 'recording' ? 'recording a voice note…' : 'typing…', true]
    if (isOnline(conv)) return ['Online', true]
    return ["Offline · they'll get messages later", false]
  }

  function refreshHeader() {
    if (!chatEls || !st.open) return
    const conv = st.open
    const title = convTitle(conv)
    const [status, on] = headerStatus(conv)
    chatEls.who.replaceChildren(
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, h('span', { class: 't-title ellipsis' }, title), verifiedBadge(conv), !isGroup(conv) && window.MeshronMod ? window.MeshronMod.badgeEl(conv, 14) : null),
      h('div', { class: 'status ellipsis' + (on ? ' on' : '') }, status))
    chatEls.av.replaceChildren(avatar(title, conv, 42, { group: isGroup(conv), online: !isGroup(conv) && isOnline(conv) }))
    const c = !isGroup(conv) && contact(conv)
    chatEls.banner.hidden = !(c && c.status === 'REQUEST')
    if ($('#details')) renderDetailsSoon()
  }

  function buildChat() {
    const conv = st.open
    const av = h('div', {})
    // Windows has the details panel on the right, so the header opens that (no pop-up card).
    const who = h('div', { class: 'who', onClick: () => (window.innerWidth > 820 ? (!$('#details') && toggleDetails()) : isGroup(conv) ? showGroupInfo(conv) : showPerson(conv)) })
    const msgs = h('div', { class: 'msgs', id: 'msgs' })
    const toBottom = h('button', { class: 'to-bottom hide', onClick: () => scrollBottom(true) }, icon('down', 22))
    const input = h('textarea', { rows: 1, placeholder: 'Message', maxlength: st.limits.MAX_BODY })
    const send = h('button', { class: 'send-btn hide', title: 'Send (right-click or hold: send urgently)', onClick: () => sendText() }, icon('send', 20))
    if (!isGroup(conv)) onContext(send, (x, y) => {
      if (st.editing || !input.value.trim()) return
      menu(x - 220, y - 70, [{ icon: 'zap', label: 'Send urgently — every Meshron phone nearby passes it on', onClick: () => sendText(true) }])
    })
    const counter = h('div', { class: 'counter', hidden: true })
    const emojiBtn = isIos ? null : h('button', { class: 'icon-btn', title: 'Emoji', onClick: () => toggleEmoji() }, icon('smile', 24))
    const banner = h('div', { class: 'banner', hidden: true },
      h('span', { class: 'grow' }, "This person isn't in your contacts yet."),
      h('button', { class: 'btn ghost small danger', onClick: () => blockContact(conv) }, 'Block'),
      h('button', { class: 'btn primary small', onClick: () => M.call('contacts.add', { key: conv, name: '' }) }, 'Accept'))
    const replyBar = h('div', { class: 'reply-bar', hidden: true })
    const suggestRow = h('div', { class: 'ai-suggest', hidden: true })
    const aiBtn = st.anon ? null : h('button', { class: 'icon-btn ai-spark', title: 'Reply ideas from Meshron AI', onClick: () => suggestReplies(conv, suggestRow, input, autosize) }, h('span', { style: { fontSize: '20px', lineHeight: 1 } }, '✦'))
    const mic = h('button', { class: 'mic-btn', title: 'Record a voice message' }, icon('mic', 22))
    const recBar = h('div', { class: 'rec-bar', hidden: true })
    mic.onclick = () => toggleVoice(conv, mic, recBar)
    const composer = h('div', { class: 'composer' },
      h('div', { class: 'composer-pill' },
        aiBtn,
        h('button', { class: 'icon-btn', title: 'Send a photo or file', onClick: () => $('#file-input').click() }, icon('plus', 24)),
        input, emojiBtn, mic, send),
      counter)

    const autosize = () => {
      input.style.height = 'auto'
      input.style.height = Math.min(160, input.scrollHeight + 2) + 'px'
      send.classList.toggle('hide', !input.value.trim())
      if (chatEls && chatEls.mic) chatEls.mic.classList.toggle('hide', !!input.value.trim())
      const left = st.limits.MAX_BODY - input.value.length
      counter.hidden = left > 200
      counter.textContent = left + ' left'
    }
    input.addEventListener('input', autosize)
    input.addEventListener('input', () => {
      // "typing…" for the other person, at most every 4 seconds (1:1 chats, live only).
      if (isGroup(conv) || !input.value.trim() || Date.now() - lastTyping < 4000) return
      lastTyping = Date.now()
      M.call('chat.typing', { conv }).catch(() => {})
    })
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && (st.reply || st.editing)) { e.preventDefault(); cancelReply(); return }
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && !isIos && st.prefs.settings.enterSends !== false) {
        e.preventDefault()
        sendText()
      }
    })
    input.addEventListener('paste', (e) => {
      const files = [...(e.clipboardData ? e.clipboardData.files : [])]
      if (files.length) { e.preventDefault(); sendFiles(files) }
    })
    msgs.addEventListener('scroll', () => {
      const away = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight
      toBottom.classList.toggle('hide', away < 240)
      if (away < 60 && st.pendingBelow) { st.pendingBelow = 0; updateToBottom() }
    })

    const head = h('div', { class: 'chat-head' },
      h('button', { class: 'icon-btn back', onClick: closeChat, 'aria-label': 'Back' }, icon('back')),
      av, who,
      isGroup(conv) ? null : h('button', { class: 'icon-btn', title: 'Video call', onClick: () => startCall(conv, true) }, icon('video', 22)),
      isGroup(conv) ? null : h('button', { class: 'icon-btn', title: 'Voice call', onClick: () => startCall(conv, false) }, icon('phone', 21)),
      h('button', { class: 'icon-btn desk-only details-btn', title: 'Details (Ctrl+I)', onClick: toggleDetails }, icon('info', 22)),
      h('button', { class: 'icon-btn', title: 'More', onClick: (e) => { const r = e.currentTarget.getBoundingClientRect(); chatHeadMenu(conv, r.right - 210, r.bottom + 6) } }, icon('more')))

    main.classList.add('enter')
    setTimeout(() => main.classList.remove('enter'), 500)
    main.replaceChildren(head, banner, msgs, toBottom, replyBar, suggestRow, recBar, composer)
    chatEls = { av, who, msgs, input, send, toBottom, banner, composer, autosize, replyBar, mic }
    st.reply = null
    st.editing = null
    input.value = st.prefs.drafts[conv] || ''
    autosize()
    refreshHeader()
    renderMsgs(true)
    if (!isIos && innerWidth > 820) input.focus()
    renderDetails()
  }

  // ---- Details panel (desktop): who this is, shared photos and files, chat settings ----

  const isDesk = () => matchMedia('(min-width: 821px)').matches
  let detailsPref = true
  try { detailsPref = localStorage.getItem('meshron.details') !== '0' } catch {}
  const detailsOpen = () => !!(chatEls && isDesk() && detailsPref && innerWidth >= 1100)
  function toggleDetails() {
    detailsPref = !detailsOpen()
    try { localStorage.setItem('meshron.details', detailsPref ? '1' : '0') } catch {}
    if (detailsPref && innerWidth < 1100) toast('Make the window wider to show details')
    renderDetails()
  }
  let detailsTimer = null
  const renderDetailsSoon = () => { if (!detailsTimer) detailsTimer = setTimeout(() => { detailsTimer = null; renderDetails() }, 200) }
  function renderDetails() {
    const old = $('#details')
    if (!detailsOpen() || !st.open) {
      if (old) old.remove()
      main.classList.remove('with-details')
      return
    }
    const conv = st.open
    const groupChat = isGroup(conv)
    const c = !groupChat && contact(conv)
    const g = groupChat && group(conv)
    const title = convTitle(conv)
    const [status] = headerStatus(conv)
    const muted = st.prefs.muted.includes(conv)
    const pinned = st.prefs.pinned.includes(conv)
    const photos = []
    const files = []
    for (let i = st.rows.length - 1; i >= 0; i--) {
      const r = st.rows[i]
      if (r.kind !== 'file' || !r.localPath) continue
      const fi = fileInfo(r)
      if (!fi) continue
      if (fi.mime.startsWith('image/') && fi.size < 12 * 1024 * 1024) { if (photos.length < 9) photos.push([r, fi]) } else if (files.length < 6) files.push([r, fi])
    }
    const action = (ic, label, onClick, cls = '') => h('button', { class: 'd-act ' + cls, onClick }, h('span', {}, icon(ic, 20)), label)
    const panel = h('aside', { class: 'details' + (old ? '' : ' enter'), id: 'details' },
      h('div', { class: 'd-top' },
        h('button', { class: 'icon-btn d-close', title: 'Close (Ctrl+I)', onClick: toggleDetails }, icon('x', 20)),
        avatar(title, conv, 96, { group: groupChat, online: !groupChat && isOnline(conv) }),
        h('div', { class: 'd-name' }, title, c && c.verified ? h('span', { class: 'd-ver', title: 'Safety code verified' }, icon('verified', 18)) : null),
        h('div', { class: 'd-sub' }, c && c.handle ? '@' + c.handle + ' · ' : '', status),
        c && bioOf(conv) ? h('p', { class: 'd-bio' }, bioOf(conv)) : null),
      h('div', { class: 'd-actions' },
        groupChat ? null : action('phone', 'Call', () => startCall(conv, false)),
        groupChat ? null : action('video', 'Video', () => startCall(conv, true)),
        action(muted ? 'bell' : 'bellOff', muted ? 'Unmute' : 'Mute', () => M.call('prefs.mute', { conv, on: !muted })),
        action('pin', pinned ? 'Unpin' : 'Pin', () => M.call('prefs.pin', { conv, on: !pinned }))),
      c ? h('div', { class: 'd-sec' }, h('div', { class: 'd-label' }, 'Meshron ID'),
        h('button', { class: 'd-id', title: 'Copy', onClick: () => copy(c.id, 'ID copied') }, c.id)) : null,
      g ? h('div', { class: 'd-sec' }, h('div', { class: 'd-label' }, g.members.length + ' members'),
        g.members.slice(0, 12).map((k) => h('div', { class: 'd-member' }, avatar(k === st.me.publicKey ? st.me.username : nameOf(k), k, 30, { online: k !== st.me.publicKey && isOnline(k) }),
          h('span', { class: 'ellipsis' }, k === st.me.publicKey ? 'You' : nameOf(k))))) : null,
      h('div', { class: 'd-sec' }, h('div', { class: 'd-label' }, 'Photos', photos.length ? h('span', {}, photos.length) : null),
        photos.length ? h('div', { class: 'd-grid' }, photos.map(([r, fi]) => {
          const img = h('img', { alt: fi.name })
          loadBlob(r, fi.mime).then((b) => { img.src = b.url; img.onclick = () => lightbox(r, fi, b.url) }).catch(() => {})
          return img
        })) : h('div', { class: 'd-empty' }, 'Photos you share show up here')),
      files.length ? h('div', { class: 'd-sec' }, h('div', { class: 'd-label' }, 'Files'),
        files.map(([r, fi]) => h('button', { class: 'd-file', onClick: () => openFile(r) }, icon('file', 18), h('span', { class: 'ellipsis' }, fi.name), h('small', {}, fileSize(fi.size))))) : null,
      h('div', { class: 'd-sec d-danger' },
        c ? h('button', { onClick: () => showSafety(conv) }, icon('shield', 18), c.verified ? 'Safety code (verified)' : 'Verify safety code') : null,
        h('button', { class: 'danger', onClick: () => deleteChat(conv) }, icon('trash', 18), 'Delete chat'),
        c ? h('button', { class: 'danger', onClick: () => blockContact(conv) }, icon('block', 18), 'Block ' + title) : null))
    if (old) old.replaceWith(panel)
    else main.appendChild(panel)
    main.classList.add('with-details')
  }
  window.addEventListener('resize', () => renderDetailsSoon())

  let lastTyping = 0
  function renderReplyBar() {
    if (!chatEls) return
    const r = st.editing || st.reply
    const bar = chatEls.replyBar
    bar.hidden = !r
    if (!r) return bar.replaceChildren()
    const who = st.editing ? 'Editing message' : 'Replying to ' + (r.outgoing ? 'yourself' : isGroup(r.peerKey) ? nameOf(r.senderKey) : convTitle(r.peerKey))
    bar.replaceChildren(
      h('div', { class: 'rb-line' }),
      h('div', { class: 'rb-text' }, h('div', { class: 'rb-who' }, st.editing ? icon('edit', 14) : icon('reply', 14), who), h('div', { class: 'rb-quote ellipsis' }, quoteOf(r))),
      h('button', { class: 'icon-btn', title: 'Cancel', onClick: cancelReply }, icon('x', 20)))
  }
  function startReply(row) {
    if (!chatEls || row.kind === 'deleted') return
    if (st.editing) { chatEls.input.value = ''; chatEls.autosize() }
    st.editing = null
    st.reply = row
    renderReplyBar()
    chatEls.input.focus()
  }
  function startEdit(row) {
    if (!chatEls) return
    st.reply = null
    st.editing = row
    chatEls.input.value = bodyText(row.body)
    chatEls.autosize()
    renderReplyBar()
    chatEls.input.focus()
  }
  function cancelReply() {
    if (st.editing && chatEls) { chatEls.input.value = st.prefs.drafts[st.open] || ''; chatEls.autosize() }
    st.reply = null
    st.editing = null
    renderReplyBar()
  }
  function react(row, e) {
    const mine = (row.reactions || {})[st.me.publicKey]
    M.call('chat.react', { conv: row.peerKey, id: row.id, e: mine === e ? '' : e }).catch(oops)
  }
  function jumpTo(id) {
    const i = st.rows.findIndex((r) => r.id === id)
    if (i < 0) return
    if (st.rows.length - i > st.shown) st.shown = st.rows.length - i + 10
    st.flash = id
    renderMsgs()
  }
  async function unsend(row) {
    if (!(await confirmBox('Delete for everyone?', "It's removed from this chat on every device that has it, including theirs. Anyone who already read it may remember it.", 'Delete', true))) return
    M.call('chat.unsend', { conv: row.peerKey, id: row.id }).catch(oops)
  }

  const SOS_PREFIX = '🆘 SOS'
  function sosBody(text, pos) {
    let out = SOS_PREFIX + ': ' + (String(text || '').trim() || 'I need help.')
    if (pos) out += '\n📍 https://maps.google.com/?q=' + pos.latitude.toFixed(6) + ',' + pos.longitude.toFixed(6) + ' (±' + Math.round(pos.accuracy) + ' m)'
    return out + '\n🕒 ' + new Date().toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })
  }

  /** Emergency SOS: message + location to every contact as urgent, plus call / SMS / Wi-Fi routes. */
  function showSos() {
    let pos = null
    const msg = h('textarea', { class: 'input', rows: 2, maxlength: 500 })
    msg.value = 'I need help.'
    const where = h('div', { class: 'sos-where' }, 'Finding your location…')
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (p) => { pos = p.coords; where.textContent = '📍 Location added (±' + Math.round(p.coords.accuracy) + ' m)'; where.classList.add('ok') },
        () => { where.textContent = 'Location unavailable — the SOS goes without it' },
        { enableHighAccuracy: true, timeout: 15000, maximumAge: 120000 })
    } else where.textContent = 'Location unavailable — the SOS goes without it'
    const note = h('p', { class: 'sos-note' }, 'Sends your message and location to all your contacts. Any Meshron phone nearby passes it on, even with no signal.')
    const big = h('button', { class: 'sos-big', onClick: async () => {
      big.disabled = true
      try {
        const n = await M.call('sos.send', { text: sosBody(msg.value, pos) })
        note.textContent = n ? `Sent to ${n} ${n === 1 ? 'contact' : 'contacts'}. Every Meshron phone that comes near passes it on, and the first one with internet delivers it.` : 'You have no contacts yet. Use the call or SMS buttons below.'
        big.querySelector('small').textContent = 'Send again'
      } catch (err) { oops(err) }
      big.disabled = false
    } }, h('b', {}, 'SOS'), h('small', {}, 'Tap to send'))
    const route = (ic, title, sub, onClick) => h('button', { class: 'sos-route', onClick }, h('span', { class: 'ic' }, icon(ic, 20)), h('span', {}, h('b', {}, title), h('small', {}, sub)))
    const box = h('div', { class: 'sos' },
      h('div', { class: 'sos-head' }, h('div', {}, h('div', { class: 'sos-title' }, 'Emergency SOS'), h('div', { class: 'sos-sub' }, 'Works without internet')),
        h('button', { class: 'icon-btn sos-x', 'aria-label': 'Close', onClick: () => box.remove() }, icon('x', 24))),
      h('div', { class: 'sos-body' }, msg, where, h('div', { class: 'sos-ring' }, big), note,
        h('div', { class: 'sos-label' }, 'Other ways to get help'),
        route('phone', 'Call emergency services', 'Works on any network, even without credit or a SIM', () => { location.href = 'tel:112' }),
        route('chat', 'Send as a text (SMS)', 'Needs a little phone signal, not data', () => { location.href = 'sms:?&body=' + encodeURIComponent(sosBody(msg.value, pos)) }),
        route('globe', 'Join an open Wi-Fi', isIos ? 'Open Settings → Wi-Fi and pick a network without a lock' : 'Open your Wi-Fi settings and pick an open network', () => {
          if (M.native.openWifi) M.native.openWifi()
          else toast(isIos ? 'Open Settings → Wi-Fi and pick a network without a lock' : 'Open Wi-Fi settings and pick an open network', 5000)
        })))
    layer.appendChild(box)
  }

  function chatHeadMenu(conv, x, y) {
    const muted = st.prefs.muted.includes(conv)
    const pinned = st.prefs.pinned.includes(conv)
    menu(x, y, [
      isGroup(conv) ? { icon: 'users', label: 'Group info', onClick: () => showGroupInfo(conv) } : { icon: 'shield', label: 'Verify safety code', onClick: () => showSafety(conv) },
      isGroup(conv) || st.anon ? null : { icon: 'flag', label: 'Report', onClick: () => window.MeshronMod.report({ kind: 'person', target: conv, owner: conv, name: nameOf(conv), page: 'Chat with ' + nameOf(conv) }) },
      { icon: 'pin', label: pinned ? 'Unpin' : 'Pin to top', onClick: () => M.call('prefs.pin', { conv, on: !pinned }) },
      { icon: muted ? 'bell' : 'bellOff', label: muted ? 'Unmute' : 'Mute', onClick: () => M.call('prefs.mute', { conv, on: !muted }) },
      { icon: 'trash', label: 'Delete chat', danger: true, onClick: () => deleteChat(conv) },
      isGroup(conv) ? null : { icon: 'block', label: 'Block', danger: true, onClick: () => blockContact(conv) }
    ])
  }

  /** Group page: photo, name, bio, members with roles, and the owner's/admins' tools. */
  function showGroupInfo(conv) {
    const g = group(conv)
    if (!g) return
    const me = st.me.publicKey
    const owner = g.ownerKey || g.creatorKey
    const admins = g.admins || []
    const iAmIn = g.members.includes(me)
    const iAdmin = iAmIn && (me === owner || admins.includes(me))
    const iOwner = iAmIn && me === owner
    const id = g.id
    const act = (method, args, done) => M.call(method, { id, ...args }).then(() => { if (done) toast(done); m.close(); setTimeout(() => showGroupInfo(conv), 300) }).catch(oops)
    const order = [...g.members].sort((x, y) => (x === owner ? 0 : admins.includes(x) ? 1 : 2) - (y === owner ? 0 : admins.includes(y) ? 1 : 2))
    const icon64 = g.icon ? h('img', { src: 'data:image/jpeg;base64,' + g.icon, style: { width: '84px', height: '84px', borderRadius: '24px', objectFit: 'cover' } }) : avatar(g.name, conv, 84, { group: true })
    const memberMenu = (k, e) => {
      if (k === me) return
      const isAdm = admins.includes(k)
      menu(e.clientX, e.clientY, [
        iOwner ? { icon: 'shield', label: isAdm ? 'Remove as admin' : 'Make admin', onClick: () => act('group.admin', { who: k, on: !isAdm }, isAdm ? 'No longer an admin' : 'Now an admin') } : null,
        iOwner ? { icon: 'key', label: 'Make owner', onClick: async () => { if (await confirmBox('Make ' + nameOf(k) + ' the owner?', "You'll stay as an admin.", 'Make owner')) act('group.owner', { who: k }) } } : null,
        k !== owner && (iOwner || (iAdmin && !isAdm)) ? { icon: 'trash', label: 'Remove from group', danger: true, onClick: async () => { if (await confirmBox('Remove ' + nameOf(k) + '?', 'They stop getting new messages.', 'Remove', true)) act('group.remove', { who: k }) } } : null,
        st.anon ? null : { icon: 'flag', label: 'Report', onClick: () => window.MeshronMod.report({ kind: 'person', target: k, owner: k, name: nameOf(k), page: 'Member of group "' + g.name + '"' }) },
        contact(k) ? null : { icon: 'userPlus', label: 'Add as contact', onClick: () => M.call('contacts.add', { key: k, name: nameOf(k) === shortKey(k) ? '' : nameOf(k) }).then(() => toast('Contact added')) }
      ].filter(Boolean))
    }
    const editInfo = () => {
      const name = h('input', { class: 'field', value: g.name, placeholder: 'Group name', maxlength: 48 })
      const bio = h('textarea', { class: 'field', rows: 3, placeholder: 'What this group is about', maxlength: 200 }, g.bio || '')
      let photo = g.icon || ''
      const pick = h('input', { type: 'file', accept: 'image/*', hidden: true, onChange: async () => {
        const f = pick.files[0]; if (!f) return
        const img = await createImageBitmap(f); const side = Math.min(img.width, img.height)
        const c = document.createElement('canvas'); c.width = 160; c.height = 160
        c.getContext('2d').drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, 160, 160)
        photo = c.toDataURL('image/jpeg', 0.8).split(',')[1]; toast('Photo ready, press Save')
      } })
      modal({ title: 'Edit group', body: h('div', {}, name, bio, pick, h('button', { class: 'btn small soft', onClick: () => pick.click() }, 'Change photo')),
        buttons: [{ label: 'Cancel', onClick: (x) => x.close() }, { label: 'Save', kind: 'primary', onClick: (x) => { x.close(); act('group.info', { name: name.value, bio: bio.value, icon: photo }, 'Saved') } }] })
    }
    const addMembers = () => {
      const options = st.contacts.filter((c) => c.status === 'ADDED' && !g.members.includes(c.keyHex))
      const picked = new Set()
      modal({ title: 'Add members', body: h('div', {}, options.length ? null : h('p', { class: 'dim' }, 'All your contacts are already here.'),
        ...options.map((c) => { const box = h('input', { type: 'checkbox', onChange: () => box.checked ? picked.add(c.keyHex) : picked.delete(c.keyHex) }); return h('label', { class: 'pick' }, box, avatar(c.name, c.keyHex, 36), h('span', { class: 't-title' }, nameOf(c.keyHex))) })),
      buttons: [{ label: 'Cancel', onClick: (x) => x.close() }, { label: 'Add', kind: 'primary', onClick: (x) => { x.close(); if (picked.size) act('group.add', { members: [...picked] }, 'Added') } }] })
    }
    const leave = async () => {
      if (iOwner && g.members.length > 1) return toast('Make someone else the owner first (tap a member)')
      if (await confirmBox('Leave ' + g.name + '?', "You'll stop getting its messages.", 'Leave', true)) act('group.leave', {}, 'You left the group')
    }
    const m = modal({
      title: g.name,
      body: h('div', {},
        h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px', margin: '4px 0 12px' } },
          icon64, h('div', { class: 't-h2' }, g.name), h('div', { class: 'dim t-small' }, (g.adult ? '18+ group · ' : '') + g.members.length + ' members'), g.bio ? h('p', { style: { textAlign: 'center' } }, g.bio) : null),
        h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', justifyContent: 'center', marginBottom: '8px' } },
          iAdmin ? h('button', { class: 'btn small soft', onClick: editInfo }, 'Edit') : null,
          iAdmin ? h('button', { class: 'btn small soft', onClick: addMembers }, 'Add members') : null,
          st.anon ? null : h('button', { class: 'btn small soft', onClick: () => window.MeshronMod.report({ kind: 'group', target: g.id, owner, name: g.name, page: 'Group "' + g.name + '", ' + g.members.length + ' members. Bio: ' + (g.bio || '') }) }, 'Report'),
          iAmIn ? h('button', { class: 'btn small soft danger', onClick: leave }, 'Leave') : null),
        iAmIn ? null : h('p', { class: 'dim t-small' }, "You're no longer in this group."),
        h('div', { style: { maxHeight: '340px', overflowY: 'auto' } }, order.map((k) => {
          const mine = k === me
          const role = k === owner ? 'Owner' : admins.includes(k) ? 'Admin' : ''
          return h('div', { class: 'pick', style: { cursor: mine ? 'default' : 'pointer' }, onClick: (e) => memberMenu(k, e) },
            avatar(mine ? st.me.username : nameOf(k), k, 42, { online: !mine && isOnline(k) }),
            h('div', { style: { flex: 1, minWidth: 0 } }, h('div', { class: 't-title ellipsis' }, mine ? st.me.username + ' (you)' : nameOf(k))),
            role ? h('span', { class: 'role-pill' }, role) : null)
        })))
    })
  }

  function scrollBottom(smooth) {
    if (!chatEls) return
    chatEls.msgs.scrollTo({ top: chatEls.msgs.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
    st.pendingBelow = 0
    updateToBottom()
  }
  function updateToBottom() {
    if (!chatEls) return
    const old = chatEls.toBottom.querySelector('.badge')
    if (old) old.remove()
    if (st.pendingBelow) chatEls.toBottom.appendChild(h('span', { class: 'badge' }, st.pendingBelow))
  }

  const URL_RE = /(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]])/g
  function richText(text, highlight) {
    const out = []
    for (const part of text.split(URL_RE)) {
      if (!part) continue
      if (/^https?:\/\//.test(part)) out.push(h('a', { href: part, target: '_blank', rel: 'noopener noreferrer' }, part))
      else if (highlight && part.toLowerCase().includes(highlight)) {
        let rest = part
        let i
        while ((i = rest.toLowerCase().indexOf(highlight)) >= 0) {
          if (i) out.push(rest.slice(0, i))
          out.push(h('mark', {}, rest.slice(i, i + highlight.length)))
          rest = rest.slice(i + highlight.length)
        }
        if (rest) out.push(rest)
      } else out.push(part)
    }
    return out
  }
  const BIG_EMOJI = /^(?:\p{Extended_Pictographic}|\p{Emoji_Component}|‍|️|\s)+$/u

  /** A voice message: play/pause, progress and length, like Android. */
  function voicePlayer(row, f) {
    const bar = h('div', { class: 'vp-fill' })
    const time = h('span', { class: 'vp-time' }, '0:00')
    const btn = h('button', { class: 'vp-btn', title: 'Play' }, icon('play', 20))
    let audio = null
    const fmt = (s) => Math.floor(s / 60) + ':' + String(Math.floor(s % 60)).padStart(2, '0')
    btn.onclick = async (e) => {
      e.stopPropagation()
      if (!row.localPath) { M.call('file.retry'); toast('Downloading…'); return }
      if (!audio) {
        const b = await loadBlob(row, f.mime).catch(() => null)
        if (!b) { toast("Couldn't play this voice message"); return }
        audio = new Audio(b.url)
        audio.onloadedmetadata = () => { if (isFinite(audio.duration)) time.textContent = fmt(audio.duration) }
        audio.ontimeupdate = () => { if (audio.duration && isFinite(audio.duration)) { bar.style.width = (audio.currentTime / audio.duration * 100) + '%'; time.textContent = fmt(audio.currentTime) } }
        audio.onended = () => { btn.replaceChildren(icon('play', 20)); bar.style.width = '0%' }
      }
      if (audio.paused) { audio.play().catch(() => {}); btn.replaceChildren(icon('pause', 20)) } else { audio.pause(); btn.replaceChildren(icon('play', 20)) }
    }
    return h('div', { class: 'voice-player' }, btn, h('div', { class: 'vp-track' }, h('div', { class: 'vp-bar' }, bar), time))
  }

  async function loadBlob(row, mime) {
    const k = row.peerKey + '|' + row.id
    if (blobs.has(k)) return blobs.get(k)
    const parts = []
    let offset = 0
    for (;;) {
      const r = await M.call('file.read', { conv: row.peerKey, id: row.id, offset })
      const bin = atob(r.data)
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      parts.push(bytes)
      offset += bytes.length
      if (r.done || !bytes.length) break
    }
    const blob = new Blob(parts, { type: mime })
    const entry = { url: URL.createObjectURL(blob), blob }
    blobs.set(k, entry)
    return entry
  }

  async function openFile(row) {
    const f = fileInfo(row)
    if (!f || !row.localPath) return
    try {
      if (M.native.openFile) return await M.native.openFile({ path: row.localPath })
      const b = await loadBlob(row, f.mime)
      await M.native.saveFile({ blob: b.blob, name: f.name, path: row.localPath })
    } catch (err) { oops(err) }
  }

  function lightbox(row, f, url) {
    const close = () => { box.remove(); document.removeEventListener('keydown', onKey) }
    const onKey = (e) => { if (e.key === 'Escape') close() }
    const box = h('div', { class: 'lightbox', onClick: close },
      h('img', { src: url, alt: f.name }),
      h('div', { class: 'bar', onClick: (e) => e.stopPropagation() },
        h('button', { class: 'icon-btn', title: isIos ? 'Share' : 'Open', onClick: () => openFile(row) }, icon(isIos ? 'share' : 'download')),
        h('button', { class: 'icon-btn', title: 'Close', onClick: close }, icon('x'))))
    document.addEventListener('keydown', onKey)
    layer.appendChild(box)
  }

  /** Group owner/admins can delete others' messages (admins: not the owner's). */
  function canModerate(row) {
    if (row.outgoing || !isGroup(row.peerKey) || (row.kind !== 'text' && row.kind !== 'file')) return false
    const g = group(row.peerKey)
    if (!g || !g.members.includes(st.me.publicKey)) return false
    const owner = g.ownerKey || g.creatorKey
    const me = st.me.publicKey
    return me === owner || ((g.admins || []).includes(me) && row.senderKey !== owner)
  }

  function messageMenu(row, x, y) {
    const f = row.kind === 'file' ? fileInfo(row) : null
    const gone = row.kind === 'deleted'
    const canEdit = row.outgoing && row.kind === 'text' && Date.now() - row.sentAt < EDIT_WINDOW
    menu(x, y, [
      gone ? null : { icon: 'reply', label: 'Reply', onClick: () => startReply(row) },
      canEdit ? { icon: 'edit', label: 'Edit', onClick: () => startEdit(row) } : null,
      row.kind === 'text' ? { icon: 'copy', label: 'Copy', onClick: () => copy(bodyText(row.body)) } : null,
      f && row.localPath ? { icon: isIos ? 'share' : 'download', label: isIos ? 'Share' : 'Open', onClick: () => openFile(row) } : null,
      f && row.localPath && M.native.showFile ? { icon: 'file', label: 'Show in folder', onClick: () => M.native.showFile({ path: row.localPath }) } : null,
      { icon: 'info', label: `${row.outgoing ? 'Sent' : 'Received'} ${dayLabel(row.sentAt)}, ${clock(row.sentAt)}`, onClick: () => {} },
      { icon: 'trash', label: 'Delete for me', danger: true, onClick: () => M.call('chat.deleteMessage', { conv: row.peerKey, id: row.id }) },
      row.outgoing && (row.kind === 'text' || row.kind === 'file') ? { icon: 'trash', label: 'Delete for everyone', danger: true, onClick: () => unsend(row) } : null,
      canModerate(row) ? { icon: 'trash', label: 'Delete for everyone (admin)', danger: true, onClick: () => M.call('group.deleteForEveryone', { conv: row.peerKey, id: row.id }).catch(oops) } : null
    ])
    // Quick reactions across the top of the menu.
    const m = $('#menu')
    if (m && !gone) {
      const mine = (row.reactions || {})[st.me.publicKey]
      m.prepend(h('div', { class: 'react-row' }, QUICK.map((e) => h('button', { class: e === mine ? 'on' : '', onClick: () => { closeMenu(); react(row, e) } }, e))))
      const r = m.getBoundingClientRect()
      if (r.bottom > innerHeight - 8) m.style.top = Math.max(8, innerHeight - r.height - 8) + 'px'
      if (r.right > innerWidth - 8) m.style.left = Math.max(8, innerWidth - r.width - 8) + 'px'
    }
  }

  /** Quick reaction picker on its own (hover button on desktop). */
  function reactPicker(row, x, y) {
    closeMenu()
    const mine = (row.reactions || {})[st.me.publicKey]
    const el = h('div', { class: 'menu react-pop', id: 'menu' }, h('div', { class: 'react-row' }, QUICK.map((e) => h('button', { class: e === mine ? 'on' : '', onClick: () => { closeMenu(); react(row, e) } }, e))))
    layer.appendChild(h('div', { class: 'menu-catcher', id: 'menu-catcher', onMousedown: closeMenu }))
    layer.appendChild(el)
    const r = el.getBoundingClientRect()
    el.style.left = Math.max(8, Math.min(x - r.width / 2, innerWidth - r.width - 8)) + 'px'
    el.style.top = Math.max(8, y - r.height - 8) + 'px'
  }

  /** Swipe a message sideways (either way) on touch screens to reply to it. */
  function swipeToReply(el, row) {
    let x0 = 0
    let y0 = 0
    let dx = 0
    let mode = null
    el.addEventListener('touchstart', (e) => { const t = e.touches[0]; x0 = t.clientX; y0 = t.clientY; dx = 0; mode = null; el.style.transition = 'none' }, { passive: true })
    el.addEventListener('touchmove', (e) => {
      const t = e.touches[0]
      const mx = t.clientX - x0
      const my = t.clientY - y0
      if (!mode && (Math.abs(mx) > 10 || Math.abs(my) > 10)) mode = Math.abs(mx) > Math.abs(my) * 1.3 ? 'x' : 'y'
      if (mode !== 'x') return
      dx = Math.max(-96, Math.min(96, mx * 0.6))
      el.style.transform = 'translateX(' + dx + 'px)'
      el.classList.toggle('armed', Math.abs(dx) >= 60)
      el.dataset.dir = dx > 0 ? 'r' : 'l'
    }, { passive: true })
    const end = () => {
      if (mode === 'x' && Math.abs(dx) >= 60) startReply(row)
      el.style.transition = 'transform .25s cubic-bezier(.3,1.5,.6,1)'
      el.style.transform = ''
      el.classList.remove('armed')
      mode = null
    }
    el.addEventListener('touchend', end, { passive: true })
    el.addEventListener('touchcancel', end, { passive: true })
  }

  function bubbleFor(row, first) {
    const groupChat = isGroup(row.peerKey)
    const kids = []
    let sos = false
    let media = false
    if (groupChat && !row.outgoing && first && row.senderKey) {
      const [a] = AVATARS[javaHash(row.senderKey) % AVATARS.length]
      kids.push(h('div', { class: 'sender', style: { color: a } }, nameOf(row.senderKey)))
    }
    if (row.kind === 'file') {
      const f = fileInfo(row)
      const status = st.fileStatus[row.peerKey + '|' + row.id]
      if (f && f.mime.startsWith('audio/')) {
        kids.push(voicePlayer(row, f))
      } else if (f && f.mime.startsWith('image/') && row.localPath && f.size < 12 * 1024 * 1024) {
        media = true
        const img = h('img', { class: 'photo', alt: f.name })
        loadBlob(row, f.mime).then((b) => { img.src = b.url; img.onclick = () => lightbox(row, f, b.url) }).catch(() => { img.alt = "Couldn't show this photo" })
        kids.push(img)
      } else {
        kids.push(h('div', { class: 'file-card', onClick: () => (row.localPath ? openFile(row) : M.call('file.retry')) },
          h('div', { class: 'ic' }, icon(f && f.mime.startsWith('image/') ? 'download' : 'file', 22)),
          h('div', { style: { minWidth: 0 } }, h('div', { class: 'fname ellipsis' }, f ? f.name : 'File'),
            h('div', { class: 'fsub' }, row.localPath ? (f ? fileSize(f.size) : '') : status || 'Waiting to download…'))))
      }
    } else if (row.kind === 'invite') {
      kids.push(h('div', { class: 'invite-card' }, icon('users', 20), h('span', {}, displayBody(row))))
    } else if (row.kind === 'deleted') {
      kids.push(h('div', { class: 'text deleted' }, icon('block', 14), displayBody(row)))
    } else if (row.kind === 'text' && row.body.startsWith(SOS_PREFIX)) {
      sos = true
      kids.push(h('div', { class: 'text' }, richText(row.body)))
      const link = /https:\/\/maps\.google\.com\/\?q=[-0-9.,]+/.exec(row.body)
      if (link) kids.push(h('a', { class: 'sos-map', href: link[0], target: '_blank', rel: 'noopener noreferrer' }, 'Open map'))
    } else if (REPLY_RE.test(row.body)) {
      const [, q, text] = REPLY_RE.exec(row.body)
      const i = st.rows.findIndex((r) => r.id === row.id)
      let quoted = null
      for (let k = i - 1; k >= 0; k--) if (st.rows[k].kind !== 'deleted' && quoteOf(st.rows[k]) === q) { quoted = st.rows[k]; break }
      const who = quoted ? (quoted.outgoing ? 'You' : isGroup(row.peerKey) ? nameOf(quoted.senderKey) : convTitle(row.peerKey)) : 'Reply'
      kids.push(h('div', { class: 'quote' + (quoted ? ' link' : ''), onClick: () => quoted && jumpTo(quoted.id) }, h('div', { class: 'q-who' }, who), h('div', { class: 'q-text' }, q)))
      const hl = st.flash === row.id && st.query.trim().length >= 2 ? st.query.trim().toLowerCase() : null
      kids.push(h('div', { class: 'text' }, richText(text, hl)))
    } else {
      const big = row.body.length <= 12 && BIG_EMOJI.test(row.body) && !/^[\d\s#*]+$/.test(row.body)
      const hl = st.flash === row.id && st.query.trim().length >= 2 ? st.query.trim().toLowerCase() : null
      const reply = /^↪ Story: (.*)\n([\s\S]+)$/.exec(row.body)
      if (reply) {
        kids.push(h('div', { class: 'story-quote' }, icon('zap', 14), h('span', { class: 'ellipsis' }, (row.outgoing ? 'You replied to their story: ' : 'Replied to your story: ') + reply[1])))
        const rbig = reply[2].length <= 12 && BIG_EMOJI.test(reply[2])
        kids.push(h('div', { class: 'text' + (rbig ? ' big' : '') }, richText(reply[2], hl)))
      } else if (row.body.startsWith('meshron://reel/')) {
        const lines = row.body.split('\n')
        const m = /^meshron:\/\/reel\/([0-9a-f]{64})\/([A-Za-z0-9_-]+)/.exec(lines[0])
        kids.push(h('div', { class: 'reel-card', onClick: () => { if (m) { setTab('reels'); setTimeout(() => window.MeshronReels.openReel(m[1], m[2]), 80) } } },
          h('div', { class: 'rc-tile' }, icon('play', 26)),
          h('div', { style: { minWidth: 0 } }, h('div', { class: 'rc-title' }, 'Reel'), h('div', { class: 'rc-sub' }, lines.slice(1).join(' ').trim() || 'Tap to watch'))))
      } else if (row.body.startsWith('meshron://collab/')) {
        const lines = row.body.split('\n')
        const parts = lines[0].replace('meshron://collab/', '').split('/')
        const key = 'collab:' + parts.join('/')
        let done = false
        try { done = localStorage.getItem(key) === '1' } catch {}
        const accept = !row.outgoing && parts.length === 2 ? h('button', { class: 'pill-btn' + (done ? '' : ' strong'), disabled: done, onClick: async (e) => {
          const b = e.currentTarget; b.textContent = '…'
          try { await M.call('reels.collabAccept', { ch: parts[0], id: parts[1] }); b.textContent = 'Accepted'; b.disabled = true; b.classList.remove('strong'); try { localStorage.setItem(key, '1') } catch {} } catch (err) { b.textContent = 'Try again'; oops(err) }
        } }, done ? 'Accepted' : 'Accept') : null
        kids.push(h('div', { class: 'collab-card' },
          h('div', { class: 'rc-title' }, '🤝 Collab invite'),
          h('div', { class: 'rc-sub' }, row.outgoing ? 'You invited them to collaborate on your reel.' : 'They want you as a collaborator on their reel.'),
          lines[1] ? h('div', { class: 'rc-sub' }, lines[1]) : null,
          h('div', { style: { display: 'flex', gap: '8px', marginTop: '8px' } },
            h('button', { class: 'pill-btn', onClick: () => { if (parts.length === 2) { setTab('reels'); setTimeout(() => window.MeshronReels.openReel(parts[0], parts[1]), 80) } } }, 'View'), accept)))
      } else if (row.body.startsWith('meshron://ai/')) {
        // Meshron AI's answer (shown only when its signature checks out).
        const box = h('div', { class: 'text' }, '…')
        kids.push(h('div', { class: 'ai-head', style: { display: 'flex', alignItems: 'center', gap: '6px', fontWeight: 700, fontSize: '13px', marginBottom: '3px' } }, window.MeshronAI.aiAvatar(18), 'Meshron AI', window.MeshronMod ? window.MeshronMod.badgeEl(window.MeshronAI.AI_ID, 12) : null), box)
        window.MeshronAI.parseGroup(row.peerKey, row.body).then((a) => { box.textContent = a ? window.MeshronAI.clean(a.text) : "A message that couldn't be checked" })
      } else {
        const t = h('div', { class: 'text' + (big ? ' big' : '') }, richText(row.body, hl))
        kids.push(t)
        if (!row.outgoing && !big) window.MeshronAI.translateInto(row.peerKey + ':' + row.id, row.body, t)
      }
    }
    kids.push(h('div', { class: 'meta' }, row.edited && row.kind === 'text' ? h('span', { class: 'edited' }, 'edited ·') : null, clock(row.sentAt), row.outgoing && row.kind !== 'deleted' ? tickIcon(groupChat && row.status === DELIVERED ? SENT : row.status, 15) : null))
    // 'Seen' under my last message they've read (like Instagram / WhatsApp).
    if (row.outgoing && row.status === READ && !groupChat) { const lastMine = [...(st.rows || [])].reverse().find((x) => x.outgoing && x.kind !== 'deleted'); if (lastMine && lastMine.id === row.id) kids.push(h('div', { class: 'seen-note' }, 'Seen')) }
    const b = h('div', { class: 'bubble' + (media ? ' media' : '') + (row.kind === 'deleted' ? ' gone' : '') + (sos ? ' sos-msg' : '') }, kids)
    onContext(b, (x, y) => messageMenu(row, x, y))
    if (row.kind === 'deleted') return b
    // Double-click / double-tap: quick heart.
    b.addEventListener('dblclick', (e) => { e.preventDefault(); window.getSelection && window.getSelection().removeAllRanges(); react(row, '❤️') })
    const out = [b]
    const reactions = Object.entries(row.reactions || {})
    if (reactions.length) {
      const counts = {}
      for (const [, e] of reactions) counts[e] = (counts[e] || 0) + 1
      const mine = (row.reactions || {})[st.me.publicKey]
      const tops = Object.entries(counts).sort((a, c) => c[1] - a[1]).slice(0, 4).map(([e]) => e)
      out.push(h('button', {
        class: 'reacts' + (mine ? ' mine' : ''),
        title: reactions.map(([k, e]) => e + ' ' + (k === st.me.publicKey ? 'You' : nameOf(k))).join('\n'),
        onClick: (e) => { if (mine) react(row, mine); else { const r = e.currentTarget.getBoundingClientRect(); reactPicker(row, r.left + r.width / 2, r.top) } }
      }, tops.join(''), reactions.length > 1 ? h('span', {}, String(reactions.length)) : null))
    }
    // Hover actions on desktop: react and reply.
    out.push(h('div', { class: 'msg-actions' },
      h('button', { title: 'React', onClick: (e) => { const r = e.currentTarget.getBoundingClientRect(); reactPicker(row, r.left + r.width / 2, r.top) } }, icon('smile', 17)),
      h('button', { title: 'Reply', onClick: () => startReply(row) }, icon('reply', 17))))
    return out
  }

  const sameGroup = (a, b) => a.outgoing === b.outgoing && a.senderKey === b.senderKey && Math.abs(b.localTime - a.localTime) < 180000 && a.kind !== 'invite' && b.kind !== 'invite'

  function renderMsgs(initial) {
    if (!chatEls) return
    const box = chatEls.msgs
    const away = box.scrollHeight - box.scrollTop - box.clientHeight
    const stick = initial || away < 90
    const fromBottom = box.scrollHeight - box.scrollTop
    const rows = st.rows.slice(-st.shown)
    const kids = [h('div', { class: 'spacer' })]
    if (st.rows.length > rows.length) {
      kids.push(h('button', { class: 'btn soft small load-earlier', onClick: () => { st.shown += PAGE; renderMsgs(false, true) } }, 'Show earlier messages'))
    } else if (!isGroup(st.open)) {
      kids.push(h('div', { class: 'day', style: { background: 'none', color: 'var(--text3)', display: 'flex', gap: '6px', alignItems: 'center' } }, icon('lock', 13), 'Messages here are end-to-end encrypted'))
    }
    let flashEl = null
    rows.forEach((m, i) => {
      const older = rows[i - 1]
      const newer = rows[i + 1]
      const newDay = !older || startOfDay(older.localTime) !== startOfDay(m.localTime)
      if (newDay) kids.push(h('div', { class: 'day' }, dayLabel(m.localTime)))
      if (m.id === st.firstUnread) kids.push(h('div', { class: 'new-line' }, 'New messages'))
      const first = newDay || !sameGroup(older, m) || m.id === st.firstUnread
      const last = !newer || !sameGroup(m, newer) || startOfDay(newer.localTime) !== startOfDay(m.localTime)
      const isNew = !st.seen.has(m.id)
      st.seen.add(m.id)
      // Calls: a small centred note, like WhatsApp.
      if (m.kind === 'call') { const t = callText(m); kids.push(h('div', { class: 'day call-note' + (/Missed/.test(t) ? ' missed' : '') }, icon(/Video/.test(t) ? 'video' : 'phone', 14), ' ', t, ' · ', clock(m.localTime))); return }
      const el = h('div', { class: 'msg' + (m.outgoing ? ' mine' : '') + (first ? ' first' : '') + (last ? ' last' : '') + (isNew ? ' new' : '') + (st.flash === m.id ? ' flash' : '') + (m.reactions && Object.keys(m.reactions).length && m.kind !== 'deleted' ? ' has-reacts' : '') },
        m.kind === 'deleted' ? null : h('div', { class: 'swipe-hint' }, icon('reply', 16)),
        h('div', { class: 'msg-body' }, bubbleFor(m, first)))
      if (m.kind !== 'deleted') swipeToReply(el.querySelector('.msg-body'), m)
      if (st.flash === m.id) flashEl = el
      kids.push(el)
    })
    box.replaceChildren(...kids)
    if (flashEl) {
      flashEl.scrollIntoView({ block: 'center' })
      st.flash = null
    } else if (initial && st.firstUnread) {
      const line = box.querySelector('.new-line')
      if (line) line.scrollIntoView({ block: 'center' })
      else box.scrollTop = box.scrollHeight
    } else if (stick) box.scrollTop = box.scrollHeight
    else if (arguments[1]) box.scrollTop = box.scrollHeight - fromBottom
  }

  // ---- Sending ---------------------------------------------------------------------

  async function sendText(urgent = false) {
    if (!chatEls || !st.open) return
    const text = chatEls.input.value.trim()
    if (!text) return
    chatEls.input.value = ''
    chatEls.autosize()
    delete st.prefs.drafts[st.open]
    M.call('prefs.draft', { conv: st.open, text: '' })
    st.firstUnread = null
    const edit = st.editing
    const reply = st.reply
    st.editing = null
    st.reply = null
    renderReplyBar()
    lastTyping = 0
    try {
      if (edit) await M.call('chat.edit', { conv: st.open, id: edit.id, text })
      else {
        await M.call('chat.send', { conv: st.open, text, replyTo: reply ? reply.id : null, urgent: !!urgent })
        if (isGroup(st.open) && window.MeshronAI.tagged(text)) {
          const conv = st.open
          const ctx = (st.rows || []).filter((r) => r.kind === 'text').slice(-12).map((r) => (r.outgoing ? 'Me' : 'Member') + ': ' + displayBody(r))
          window.MeshronAI.ask(conv, Math.random().toString(36).slice(2, 14), text, '', '', ctx)
            .then((a) => M.call('chat.send', { conv, text: window.MeshronAI.PREFIX + JSON.stringify(a) }))
            .catch((e) => toast(e.message || "Meshron AI couldn't answer"))
        }
        if (urgent) toast('Sent urgently: every Meshron phone nearby will pass it on')
        scrollBottom(false)
      }
    } catch (err) { oops(err) }
    if (!isIos) chatEls.input.focus()
  }

  function toBase64(bytes) {
    let bin = ''
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000))
    return btoa(bin)
  }

  /** Shows picked / pasted / dropped files first: photo previews, names and sizes, a caption. */
  /** Three short replies from Meshron AI (the sparkle in the message bar). */
  async function suggestReplies(conv, row, input, autosize) {
    if (!row.hidden) { row.hidden = true; return }
    if (!window.MeshronAccount || !window.MeshronAccount.state) { toast('Create a Meshron account (Me → Settings) to use Meshron AI'); return }
    row.hidden = false
    row.replaceChildren(h('span', { class: 'dim t-small' }, 'Thinking…'))
    const history = (st.rows || []).filter((r) => r.kind === 'text').slice(-8).map((r) => ({ mine: r.outgoing, text: displayBody(r) }))
    try {
      const r = await window.MeshronAccount.authed('/v1/ai', { task: 'replies', history })
      row.replaceChildren(...(r.replies || []).map((t) => h('button', { class: 'chip', onClick: () => { input.value = t; autosize(); row.hidden = true; input.focus() } }, t)))
    } catch (e) { row.replaceChildren(h('span', { class: 'dim t-small' }, e.message)) }
  }

  /** Voice messages: tap the mic to record, tap again to send (or ✕ to cancel). */
  let rec = null
  async function toggleVoice(conv, mic, bar) {
    if (rec) { rec.stop(true); return }
    let stream
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }) } catch { toast('Allow the microphone to record voice messages'); return }
    const type = ['audio/mp4', 'audio/webm;codecs=opus', 'audio/webm'].find((t) => window.MediaRecorder && MediaRecorder.isTypeSupported(t)) || ''
    let mr
    try { mr = new MediaRecorder(stream, type ? { mimeType: type } : undefined) } catch { stream.getTracks().forEach((t) => t.stop()); toast("Voice messages aren't supported here"); return }
    const chunks = []
    let send = false
    const started = Date.now()
    const clock = h('span', {}, '0:00')
    const tick = setInterval(() => { const s = Math.floor((Date.now() - started) / 1000); clock.textContent = Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); if (s % 4 === 0) M.call('chat.typing', { conv, what: 'recording' }).catch(() => {}); if (s >= 300) rec && rec.stop(true) }, 1000)
    bar.hidden = false
    bar.replaceChildren(h('span', { class: 'rec-dot' }), h('span', { style: { color: 'var(--danger)', fontWeight: 700 } }, 'Recording '), clock, h('span', { class: 'grow' }),
      h('button', { class: 'btn dark small', onClick: () => rec && rec.stop(false) }, 'Cancel'), h('button', { class: 'btn light small', onClick: () => rec && rec.stop(true) }, 'Send'))
    mic.classList.add('on')
    mr.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data) }
    mr.onstop = async () => {
      clearInterval(tick); stream.getTracks().forEach((t) => t.stop()); bar.hidden = true; mic.classList.remove('on'); rec = null
      if (!send || Date.now() - started < 700) return
      const mime = (mr.mimeType || type || 'audio/webm').split(';')[0]
      const ext = mime.includes('mp4') ? 'm4a' : 'webm'
      const d = new Date()
      const name = 'Voice note ' + d.toISOString().slice(0, 10) + ' ' + d.toTimeString().slice(0, 8).replace(/:/g, '.') + '.' + ext
      await uploadFiles([new File(chunks, name, { type: mime })], conv)
    }
    rec = { stop: (s) => { send = s; try { mr.stop() } catch {} } }
    mr.start(250)
  }

  function sendFiles(list) {
    const conv = st.open
    if (!conv) return
    const files = list.filter((x) => x.size > 0)
    if (!files.length) return
    const urls = []
    const caption = h('textarea', { class: 'input', rows: 2, placeholder: 'Add a caption…', maxlength: 1000 })
    const items = files.map((file) => {
      const big = file.size > st.limits.MAX_FILE
      if (/^image\//.test(file.type) && !big) {
        const url = URL.createObjectURL(file)
        urls.push(url)
        return h('div', { class: 'fp-item photo' }, h('img', { src: url, alt: file.name }), h('div', { class: 'fp-name ellipsis' }, fileSize(file.size)))
      }
      return h('div', { class: 'fp-item' + (big ? ' big' : '') }, h('div', { class: 'fp-ic' }, icon('file', 28)),
        h('div', { class: 'fp-name ellipsis' }, file.name || 'File'), h('div', { class: 'fp-size' }, big ? 'Over 25 MB, can\'t send' : fileSize(file.size)))
    })
    const ok = files.filter((x) => x.size <= st.limits.MAX_FILE)
    modal({
      title: files.length === 1 ? 'Send ' + (/^image\//.test(files[0].type) ? 'photo' : 'file') : `Send ${files.length} files`,
      wide: true,
      body: h('div', { class: 'fp' }, h('div', { class: 'fp-grid' + (files.length === 1 ? ' one' : '') }, items), h('div', { class: 'dim t-small', style: { margin: '10px 4px 6px' } }, 'To ' + convTitle(conv)), caption),
      onClose: () => urls.forEach((u) => URL.revokeObjectURL(u)),
      buttons: [
        { label: 'Cancel', onClick: (m) => m.close() },
        { label: ok.length > 1 ? `Send ${ok.length}` : 'Send', kind: 'primary', disabled: !ok.length, onClick: async (m) => {
          const text = caption.value.trim()
          m.close()
          await uploadFiles(ok, conv)
          if (text) M.call('chat.send', { conv, text }).catch(oops)
        } }
      ]
    })
  }

  async function uploadFiles(files, conv) {
    if (!conv) return
    for (const file of files) {
      if (file.size > st.limits.MAX_FILE) { toast(`“${file.name}” is over 25 MB`); continue }
      if (!file.size) continue
      st.sending++
      const note = h('div', { class: 'toast' }, h('span', { style: { display: 'inline-flex', gap: '10px', alignItems: 'center' } }, h('div', { class: 'spinner' }), `Sending ${file.name}…`))
      $('#toasts').appendChild(note)
      try {
        const id = await M.call('file.begin', { conv, name: file.name || 'photo.png', mime: file.type || 'application/octet-stream', size: file.size })
        const bytes = new Uint8Array(await file.arrayBuffer())
        const step = 240 * 1024
        for (let o = 0; o < bytes.length; o += step) await M.call('file.chunk', { id, data: toBase64(bytes.subarray(o, o + step)) })
        await M.call('file.end', { id })
        if (st.open === conv) scrollBottom(false)
      } catch (err) { oops(err) }
      note.remove()
      st.sending--
    }
  }
  $('#file-input').addEventListener('change', (e) => {
    sendFiles([...e.target.files])
    e.target.value = ''
  })

  // Drag files onto the chat (desktop).
  let dragDepth = 0
  main.addEventListener('dragenter', (e) => {
    if (!st.open || !e.dataTransfer || ![...e.dataTransfer.types].includes('Files')) return
    e.preventDefault()
    if (dragDepth++ === 0) main.appendChild(h('div', { class: 'drop', id: 'drop' }, h('div', { class: 'empty' }, h('div', { class: 'orb' }, icon('clip', 34)), h('div', { class: 't-h2' }, 'Drop to send'))))
  })
  main.addEventListener('dragover', (e) => { if (st.open) e.preventDefault() })
  const endDrag = () => { dragDepth = 0; const d = $('#drop'); if (d) d.remove() }
  main.addEventListener('dragleave', () => { if (--dragDepth <= 0) endDrag() })
  main.addEventListener('drop', (e) => {
    e.preventDefault()
    endDrag()
    if (st.open && e.dataTransfer) sendFiles([...e.dataTransfer.files])
  })

  const EMOJI = [
    ['Smileys', '😀 😁 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😜 🤪 🤗 🤔 🤨 😐 😏 😴 😌 😔 😢 😭 😤 😡 🤯 😱 🥳 😎 🤓 🥺 😬 🙄 🤐 🤒'],
    ['Gestures', '👍 👎 👌 ✌️ 🤞 🤟 🤙 👋 👏 🙌 🙏 💪 🤝 👀 🫶 🫡'],
    ['Hearts', '❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💖 ✨ 🔥 💯 🎉 🎊'],
    ['Things', '☕ 🍕 🍔 🍺 🎂 ⚽ 🎮 🎵 📷 💻 📱 🚗 ✈️ 🏠 🌙 ☀️ 🌧️ ⏰ 💤 ✅ ❌ ❓ ❗ 💡']
  ]
  function toggleEmoji() {
    const old = $('#emoji')
    if (old) return old.remove()
    const pop = h('div', { class: 'emoji-pop', id: 'emoji' }, EMOJI.map(([cat, list]) => [h('div', { class: 'cat' }, cat),
      h('div', { class: 'grid' }, list.split(' ').map((e) => h('button', {
        onClick: () => {
          const i = chatEls.input
          const at = i.selectionStart
          i.value = i.value.slice(0, at) + e + i.value.slice(i.selectionEnd)
          i.selectionStart = i.selectionEnd = at + e.length
          chatEls.autosize()
          i.focus()
        }
      }, e)))]))
    chatEls.composer.appendChild(pop)
    const away = (e) => { if (!pop.contains(e.target) && !e.target.closest('.icon-btn')) { pop.remove(); document.removeEventListener('mousedown', away) } }
    setTimeout(() => document.addEventListener('mousedown', away), 0)
  }

  // ---- Alerts ------------------------------------------------------------------------

  let audio = null
  function ping() {
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)()
      const t = audio.currentTime
      for (const [f, at] of [[880, 0], [1174.7, 0.09]]) {
        const o = audio.createOscillator()
        const g = audio.createGain()
        o.type = 'sine'
        o.frequency.value = f
        g.gain.setValueAtTime(0.0001, t + at)
        g.gain.exponentialRampToValueAtTime(0.12, t + at + 0.02)
        g.gain.exponentialRampToValueAtTime(0.0001, t + at + 0.22)
        o.connect(g).connect(audio.destination)
        o.start(t + at)
        o.stop(t + at + 0.25)
      }
    } catch {}
  }

  function onIncoming({ conv, row, title }) {
    const focused = M.native.focusState()
    const viewing = st.open === conv && focused
    if (viewing) return
    const s = st.prefs.settings
    if (s.sounds) ping()
    const who = isGroup(conv) && row.senderKey ? `${nameOf(row.senderKey)} in ${title}` : title
    const body = displayBody(row)
    if (!focused && M.native.alertsInBackground) return // the desktop app shows its own alert
    if (!focused && s.notifications && M.native.notify) return M.native.notify({ title: who, body, conv })
    if (!s.notifications) return
    const note = h('div', { class: 'toast note', onClick: () => { note.remove(); openChat(conv) } },
      avatar(title, conv, 38, { group: isGroup(conv) }),
      h('div', { style: { minWidth: 0 } }, h('div', { class: 'ellipsis', style: { fontWeight: 800 } }, who), h('div', { class: 'ellipsis dim', style: { fontWeight: 500, maxWidth: '280px' } }, body)))
    document.body.appendChild(note)
    setTimeout(() => note.remove(), 4500)
  }

  // ---- Stories ---------------------------------------------------------------------
  //
  // Instagram-style 24-hour posts. The strip at the top of Chats shows your story first, then
  // friends with new stories (gradient ring), friends whose stories you've seen (grey ring),
  // then friends who are online right now (green dot).

  const STORY_BGS = [
    'linear-gradient(160deg, #8b5cf6, #d946ef 55%, #ff7a59)',
    'linear-gradient(160deg, #0ea5e9, #6366f1)',
    'linear-gradient(160deg, #f43f5e, #f59e0b)',
    'linear-gradient(160deg, #10b981, #0ea5e9)',
    'linear-gradient(160deg, #111827, #4b5563)',
    'linear-gradient(160deg, #ec4899, #8b5cf6)',
    'linear-gradient(160deg, #f97316, #ef4444 60%, #7c3aed)',
    'radial-gradient(circle at 30% 20%, #22d3ee, #6366f1 45%, #0a0a0f)'
  ]
  const REACTIONS = ['❤️', '🔥', '😂', '😮', '😢', '👏']
  const STORY_MS = 5500

  function timeAgo(ms) {
    const m = Math.max(1, Math.round((Date.now() - ms) / 60000))
    if (m < 60) return m + 'm'
    return Math.round(m / 60) + 'h'
  }

  function storyBubble({ label, ring, onClick, inner, badge, dot }) {
    return h('button', { class: 'story', onClick },
      h('div', { class: 'sring ' + ring }, h('div', { class: 'gap' }, inner), badge || null, dot ? h('span', { class: 'sdot' }) : null),
      h('span', { class: 'ellipsis' }, label))
  }

  function storiesStrip(added) {
    const stories = st.stories || { mine: [], friends: [] }
    const withStories = new Set(stories.friends.map((f) => f.key))
    const online = added.filter((c) => isOnline(c.keyHex) && !withStories.has(c.keyHex))
    const items = []
    const me = st.me
    const mineAv = avatar(me.username, me.publicKey, 58)
    items.push(storyBubble({
      label: 'You',
      ring: stories.mine.length ? 'mine' : 'none',
      onClick: () => (stories.mine.length ? openViewer('me', 0) : openComposer()),
      inner: mineAv,
      badge: h('span', { class: 'splus', onClick: (e) => { e.stopPropagation(); openComposer() } }, icon('plus', 14))
    }))
    for (const f of stories.friends) {
      const c = contact(f.key)
      const name = c ? c.displayName : nameOf(f.key)
      items.push(storyBubble({ label: name.split(' ')[0], ring: f.allSeen ? 'seen' : 'new', onClick: () => openViewer(f.key, 0), inner: avatar(name, f.key, 58), dot: isOnline(f.key) }))
    }
    for (const c of online) items.push(storyBubble({ label: c.displayName.split(' ')[0], ring: 'none', onClick: () => openChat(c.keyHex), inner: avatar(c.displayName, c.keyHex, 58), dot: true }))
    if (!added.length) items.push(h('button', { class: 'story', onClick: () => showAddContact() }, h('div', { class: 'add' }, icon('userPlus', 24)), h('span', {}, 'Add friend')))
    const live = online.length + stories.friends.filter((f) => isOnline(f.key)).length
    return [
      h('div', { class: 'section big' }, 'Moments'),
      h('div', { class: 'stories' }, items)
    ]
  }

  // -- Viewer --

  let viewer = null // { owner, index, el, timer, paused, started, elapsed, urls }

  function storyList(owner) {
    if (owner === 'me') return st.stories.mine
    const f = st.stories.friends.find((x) => x.key === owner)
    return f ? f.items : []
  }
  function ownerOrder() {
    return st.stories.friends.map((f) => f.key)
  }

  async function photoUrl(item) {
    const k = item.conv + '|' + item.id
    if (blobs.has(k)) return blobs.get(k).url
    const r = await M.call('stories.photo', { conv: item.conv, id: item.id })
    const bin = atob(r.data)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    const blob = new Blob([bytes], { type: item.mime || 'image/jpeg' })
    const entry = { url: URL.createObjectURL(blob), blob }
    blobs.set(k, entry)
    return entry.url
  }

  function openViewer(owner, index) {
    closeViewer()
    const root = h('div', { class: 'sv', tabindex: '-1' })
    layer.appendChild(root)
    viewer = { owner, index, el: root, timer: null, paused: false, started: 0, elapsed: 0 }
    root.addEventListener('keydown', (e) => {
      if (e.target.tagName === 'INPUT') return
      if (e.key === 'ArrowRight') step(1)
      if (e.key === 'ArrowLeft') step(-1)
      if (e.key === 'Escape') closeViewer()
      if (e.key === ' ') { e.preventDefault(); setPaused(!viewer.paused) }
    })
    showItem()
    root.focus()
  }

  function closeViewer() {
    if (!viewer) return
    clearTimeout(viewer.timer)
    const el = viewer.el
    viewer = null
    el.classList.add('out')
    setTimeout(() => el.remove(), 220)
  }

  function refreshViewer() {
    if (!viewer) return
    const list = storyList(viewer.owner)
    if (!list.length) return closeViewer()
    if (viewer.index >= list.length) viewer.index = list.length - 1
    // Re-render only things that may have changed (photo arrived, viewers).
    const item = list[viewer.index]
    const v = viewer.el.querySelector('.sv-viewers')
    if (v && item.viewers) v.lastChild.textContent = item.viewers.length ? `Seen by ${item.viewers.length}` : 'No views yet'
    if (item.hasPhoto && item.photoReady && viewer.el.querySelector('.sv-wait')) showItem(true)
  }

  function setPaused(p) {
    if (!viewer) return
    if (p === viewer.paused) return
    viewer.paused = p
    viewer.el.classList.toggle('paused', p)
    if (p) {
      clearTimeout(viewer.timer)
      viewer.elapsed += Date.now() - viewer.started
    } else {
      viewer.started = Date.now()
      viewer.timer = setTimeout(() => step(1), Math.max(200, viewer.duration - viewer.elapsed))
    }
  }

  function step(dir) {
    if (!viewer) return
    const list = storyList(viewer.owner)
    const next = viewer.index + dir
    if (next >= 0 && next < list.length) {
      viewer.index = next
      return showItem()
    }
    if (viewer.owner === 'me') return dir > 0 ? closeViewer() : showItem()
    const order = ownerOrder()
    const at = order.indexOf(viewer.owner)
    const other = order[at + dir]
    if (!other) return dir > 0 ? closeViewer() : showItem()
    viewer.owner = other
    viewer.index = dir > 0 ? 0 : storyList(other).length - 1
    viewer.el.classList.remove('swap')
    void viewer.el.offsetWidth
    viewer.el.classList.add('swap')
    showItem()
  }

  function showItem(keepTime) {
    if (!viewer) return
    const { owner } = viewer
    const list = storyList(owner)
    const item = list[viewer.index]
    if (!item) return closeViewer()
    clearTimeout(viewer.timer)
    const mine = owner === 'me'
    const name = mine ? 'Your story' : nameOf(owner)
    const seed = mine ? st.me.publicKey : owner
    const bars = h('div', { class: 'sv-bars' }, list.map((_, i) => h('div', { class: 'sv-bar' + (i < viewer.index ? ' done' : '') }, h('i', i === viewer.index ? { class: 'run' } : {}))))
    const head = h('div', { class: 'sv-head' },
      avatar(mine ? st.me.username : name, seed, 36),
      h('div', { class: 'sv-who' }, h('b', {}, name), h('span', {}, timeAgo(item.ts))),
      h('button', { class: 'icon-btn sv-x', title: 'Pause', onClick: () => setPaused(!viewer.paused) }, icon(viewer.paused ? 'right' : 'pause', 20)),
      mine ? h('button', { class: 'icon-btn sv-x', title: 'Delete', onClick: async () => {
        setPaused(true)
        if (await confirmBox('Delete this story?', 'It disappears for everyone who has the new version of Meshron.', 'Delete', true)) {
          await M.call('stories.delete', { sid: item.sid })
          toast('Story deleted')
        } else setPaused(false)
      } }, icon('trash', 20)) : null,
      h('button', { class: 'icon-btn sv-x', title: 'Close', onClick: closeViewer }, icon('x', 22)))

    let body
    if (item.type === 'photo') {
      if (!item.photoReady) {
        body = h('div', { class: 'sv-media sv-wait', style: { background: STORY_BGS[4] } }, h('div', { class: 'spinner' }), h('p', {}, 'Downloading photo…'))
      } else {
        const img = h('img', { class: 'sv-photo', alt: '' })
        const back = h('div', { class: 'sv-blur' })
        photoUrl(item).then((url) => { img.src = url; back.style.backgroundImage = `url("${url}")` }).catch(() => {})
        const P = window.MeshronStoryEditor ? window.MeshronStoryEditor.parse(item.text) : { shown: item.text, reel: null, card: null, by: null }
        const media = h('div', { class: 'sv-media' }, back, img, P.shown ? h('div', { class: 'sv-caption', style: P.reel ? { bottom: '150px' } : {} }, P.shown) : null)
        if (P.reel && P.card && window.MeshronReels && window.MeshronReels.videoUrl) {
          const vid = h('video', { playsinline: true, loop: true, autoplay: true, style: { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' } })
          const box = h('div', { style: { position: 'absolute', overflow: 'hidden', borderRadius: '16px', pointerEvents: 'none' } }, vid)
          media.appendChild(box)
          const place = () => {
            const mb = media.getBoundingClientRect(); const iw0 = img.naturalWidth; const ih0 = img.naturalHeight
            if (!iw0 || !mb.width) return
            const s = Math.min(mb.width / iw0, mb.height / ih0); const iw = iw0 * s; const ih = ih0 * s
            const ix = (mb.width - iw) / 2; const iy = (mb.height - ih) / 2; const cw = iw * 0.62; const chh = cw * 16 / 9
            Object.assign(box.style, { left: (ix + P.card[0] * iw - cw / 2) + 'px', top: (iy + P.card[1] * ih - chh / 2) + 'px', width: cw + 'px', height: chh + 'px', transform: 'rotate(' + P.card[3] + 'deg) scale(' + P.card[2] + ')' })
          }
          img.addEventListener('load', place)
          window.MeshronReels.videoUrl(P.reel).then((u) => { vid.src = u; vid.play().catch(() => { vid.muted = true; vid.play().catch(() => {}) }); place() }).catch(() => box.remove())
        }
        if (P.reel) media.appendChild(h('button', { class: 'sv-original', onClick: (e) => { e.stopPropagation(); closeViewer(); window.MeshronReels.openReel(P.reel.ch, P.reel.id) }, style: { position: 'absolute', left: '16px', bottom: '96px', zIndex: 6, display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 14px 8px 8px', borderRadius: '14px', border: 'none', background: 'rgba(0,0,0,0.55)', color: '#fff', cursor: 'pointer', textAlign: 'left' } },
          h('span', { style: { width: '28px', height: '28px', borderRadius: '8px', background: 'linear-gradient(135deg,#4F7BFF,#8B5CFF)', display: 'grid', placeItems: 'center', fontSize: '12px' } }, '▶'),
          h('span', {}, h('b', { style: { display: 'block', fontSize: '13px' } }, 'Original video'), h('span', { style: { fontSize: '12px', opacity: 0.75 } }, P.by || 'Meshron')), h('b', {}, '›')))
        body = media
      }
    } else {
      const len = item.text.length
      body = h('div', { class: 'sv-media sv-text', style: { background: STORY_BGS[item.bg % STORY_BGS.length] } },
        h('div', { class: 'sv-words', style: { fontSize: (len < 40 ? 34 : len < 120 ? 26 : 20) + 'px' } }, item.text))
    }

    let foot
    if (mine) {
      foot = h('div', { class: 'sv-foot' },
        h('button', { class: 'sv-viewers', onClick: () => showViewers(item) }, icon('users', 18), h('span', {}, item.viewers && item.viewers.length ? `Seen by ${item.viewers.length}` : 'No views yet')),
        h('button', { class: 'btn primary small', onClick: () => { closeViewer(); openComposer() } }, icon('plus', 16), 'Add'))
    } else {
      const input = h('input', { class: 'sv-reply', placeholder: `Reply to ${name.split(' ')[0]}…`, maxlength: 1000 })
      input.addEventListener('focus', () => setPaused(true))
      input.addEventListener('blur', () => { if (!input.value) setPaused(false) })
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && input.value.trim()) { replyToStory(owner, item, input.value.trim()); input.value = ''; input.blur() } })
      foot = h('div', { class: 'sv-foot' }, input,
        h('div', { class: 'sv-reacts' }, REACTIONS.map((r) => h('button', { onClick: (e) => { burst(e.currentTarget, r); replyToStory(owner, item, r) } }, r))))
    }

    const tapPrev = h('div', { class: 'sv-tap left' })
    const tapNext = h('div', { class: 'sv-tap right' })
    const card = h('div', { class: 'sv-card' }, body, h('div', { class: 'sv-shade' }), bars, head, tapPrev, tapNext, foot)
    // Tap left/right to move, hold anywhere to pause, swipe down to close.
    for (const [el, dir] of [[tapPrev, -1], [tapNext, 1]]) {
      let downAt = 0
      let y0 = 0
      el.addEventListener('pointerdown', (e) => { downAt = Date.now(); y0 = e.clientY; setTimeout(() => { if (downAt) setPaused(true) }, 220) })
      el.addEventListener('pointerup', (e) => {
        const held = Date.now() - downAt
        downAt = 0
        if (e.clientY - y0 > 90) return closeViewer()
        if (held < 220) step(dir)
        else setPaused(false)
      })
      el.addEventListener('pointerleave', () => { if (downAt) { downAt = 0; setPaused(false) } })
    }
    viewer.el.replaceChildren(h('div', { class: 'sv-back', onClick: closeViewer }), card)
    viewer.el.classList.toggle('paused', viewer.paused)
    if (!keepTime) viewer.elapsed = 0
    viewer.duration = item.type === 'text' ? Math.min(12000, STORY_MS + item.text.length * 35) : STORY_MS
    const run = bars.querySelector('.run')
    if (run) {
      run.style.animationDuration = viewer.duration + 'ms'
      run.style.animationDelay = -viewer.elapsed + 'ms'
    }
    if (item.type === 'photo' && !item.photoReady) {
      if (run) run.style.animationPlayState = 'paused'
      return
    }
    if (!mine && !item.seen) {
      item.seen = true
      M.call('stories.seen', { key: owner, sid: item.sid }).catch(() => {})
    }
    if (!viewer.paused) {
      viewer.started = Date.now()
      viewer.timer = setTimeout(() => step(1), viewer.duration - viewer.elapsed)
    }
  }

  function burst(el, emoji) {
    const r = el.getBoundingClientRect()
    for (let i = 0; i < 7; i++) {
      const p = h('span', { class: 'burst', style: { left: r.left + r.width / 2 + 'px', top: r.top + 'px', '--dx': (Math.random() * 160 - 80) + 'px', '--d': (600 + Math.random() * 500) + 'ms' } }, emoji)
      document.body.appendChild(p)
      setTimeout(() => p.remove(), 1200)
    }
  }

  async function replyToStory(owner, item, text) {
    const quote = item.type === 'photo' ? (item.text ? `📷 ${item.text}` : '📷 Photo') : item.text
    const body = `↪ Story: ${quote.replace(/\s+/g, ' ').slice(0, 60)}${quote.length > 60 ? '…' : ''}\n${text}`
    try {
      await M.call('chat.send', { conv: owner, text: body })
      toast(text.length <= 4 ? `Sent ${text}` : 'Reply sent')
    } catch (err) { oops(err) }
  }

  function showViewers(item) {
    setPaused(true)
    modal({
      title: 'Seen by',
      onClose: () => setPaused(false),
      body: item.viewers && item.viewers.length
        ? h('div', {}, item.viewers.map((k) => h('div', { class: 'pick', style: { cursor: 'default' } }, avatar(nameOf(k), k, 40), h('div', { class: 't-title' }, nameOf(k)))))
        : h('p', { class: 'dim' }, "Nobody has seen it yet. Friends see it when they're online with a new version of Meshron.")
    })
  }

  // -- Composer --

  function openComposer() {
    closeViewer()
    let bg = Math.floor(Math.random() * STORY_BGS.length)
    let photo = null // { blob, url }
    const text = h('textarea', { class: 'sc-text', placeholder: 'Type something…', maxlength: 700, rows: 3 })
    const caption = h('input', { class: 'sv-reply', placeholder: 'Add a caption…', maxlength: 300 })
    const stage = h('div', { class: 'sc-stage' })
    const swatches = h('div', { class: 'sc-swatches' })
    const photoBtn = h('button', { class: 'sc-photo', onClick: () => picker.click() }, icon('clip', 18), 'Photo')
    const share = h('button', { class: 'btn primary', onClick: post }, 'Share to story')
    const picker = h('input', { type: 'file', accept: 'image/*', hidden: true })
    const root = h('div', { class: 'sv sc' })
    const close = () => { root.classList.add('out'); setTimeout(() => root.remove(), 220); if (photo) URL.revokeObjectURL(photo.url) }

    const draw = () => {
      photoBtn.hidden = !!photo
      if (photo) {
        stage.style.background = '#000'
        stage.replaceChildren(h('div', { class: 'sv-blur', style: { backgroundImage: `url("${photo.url}")` } }), h('img', { class: 'sv-photo', src: photo.url }),
          h('button', { class: 'icon-btn sc-remove', title: 'Remove photo', onClick: () => { URL.revokeObjectURL(photo.url); photo = null; draw() } }, icon('x', 20)))
        swatches.replaceChildren(caption)
      } else {
        stage.style.background = STORY_BGS[bg]
        stage.replaceChildren(text)
        swatches.replaceChildren(...STORY_BGS.map((b, i) => h('button', { class: 'sc-swatch' + (i === bg ? ' on' : ''), style: { background: b }, onClick: () => { bg = i; draw() } })))
        const len = text.value.length
        text.style.fontSize = (len < 40 ? 32 : len < 120 ? 24 : 19) + 'px'
        setTimeout(() => text.focus(), 30)
      }
    }
    text.addEventListener('input', () => { const len = text.value.length; text.style.fontSize = (len < 40 ? 32 : len < 120 ? 24 : 19) + 'px' })
    picker.addEventListener('change', async () => {
      const file = picker.files[0]
      picker.value = ''
      if (!file) return
      try {
        photo = await shrinkImage(file)
        if (window.MeshronStoryEditor) { const p = photo; photo = null; close(); window.MeshronStoryEditor.open({ photo: p.blob }); return }
        draw()
      } catch { toast("Couldn't open that picture") }
    })

    async function post() {
      share.disabled = true
      share.replaceChildren(h('div', { class: 'spinner' }))
      try {
        if (photo) {
          const id = await M.call('file.begin', { conv: 'story', name: 'story.jpg', mime: 'image/jpeg', size: photo.blob.size })
          const bytes = new Uint8Array(await photo.blob.arrayBuffer())
          const stepSize = 240 * 1024
          for (let o = 0; o < bytes.length; o += stepSize) await M.call('file.chunk', { id, data: toBase64(bytes.subarray(o, o + stepSize)) })
          await M.call('stories.post', { type: 'photo', text: caption.value, upload: id })
        } else {
          await M.call('stories.post', { type: 'text', text: text.value, bg })
        }
        close()
        toast('Shared to your story')
      } catch (err) {
        oops(err)
        share.disabled = false
        share.replaceChildren('Share to story')
      }
    }

    root.append(h('div', { class: 'sv-back', onClick: close }),
      h('div', { class: 'sv-card' }, stage,
        h('div', { class: 'sv-head sc-head' }, h('div', { class: 'sv-who' }, h('b', {}, 'New story'), h('span', {}, 'Your contacts see it for 24 hours')), photoBtn, h('button', { class: 'icon-btn sv-x', onClick: close }, icon('x', 22))),
        h('div', { class: 'sc-foot' }, swatches, share)),
      picker)
    layer.appendChild(root)
    draw()
  }

  /** Photos are shrunk to 1440 px on the long side (JPEG) so stories stay quick to send. */
  function shrinkImage(file) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file)
      const img = new Image()
      img.onload = () => {
        const scale = Math.min(1, 1440 / Math.max(img.width, img.height))
        const c = document.createElement('canvas')
        c.width = Math.round(img.width * scale)
        c.height = Math.round(img.height * scale)
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height)
        URL.revokeObjectURL(url)
        c.toBlob((blob) => (blob ? resolve({ blob, url: URL.createObjectURL(blob) }) : reject(new Error('encode'))), 'image/jpeg', 0.85)
      }
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('decode')) }
      img.src = url
    })
  }


  // ---- Calls -------------------------------------------------------------------------
  //
  // Same protocol and media as the Android app (calls/CallManager.kt, VoiceEngine.kt, VideoEngine.kt):
  //   signalling  { t: offer | answer | reject | end | video | vack | keyreq | unreachable, id, ... }
  //   audio       [seq u16][count u8] + count × ([len u16][Opus frame])   16 kHz mono, 20 ms frames, 2 per packet
  //   video       [seq u32][flags u8: 1 = key][rotation/90 u8][width u16][height u16] + H.264 Annex B
  // Encoding and decoding use WebCodecs; capture and playback use the microphone/camera and an AudioWorklet.

  const RING_MS = 45000
  const AUDIO_RATE = 16000
  const callSupported = () => typeof AudioEncoder !== 'undefined' && typeof AudioDecoder !== 'undefined' && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
  const videoSupported = () => typeof VideoEncoder !== 'undefined' && typeof VideoDecoder !== 'undefined'

  let call = null // { phase, peer, id, video, remoteVideo, muted, startedAt, ... }

  const b64 = (bytes) => toBase64(bytes)
  const unb64 = (s) => { const bin = atob(s); const out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out }
  const newCallId = () => { const b = new Uint8Array(16); crypto.getRandomValues(b); return [...b].map((x) => x.toString(16).padStart(2, '0')).join('') }
  const signal = (peer, msg) => M.call('call.send', { key: peer, msg }).catch(() => {})

  // ---- Tones (ring for incoming, ringback for outgoing) ----
  let toneCtx = null
  let toneTimer = null
  function playTone(kind) {
    stopTone()
    try {
      toneCtx = new (window.AudioContext || window.webkitAudioContext)()
      const beep = () => {
        const t = toneCtx.currentTime
        const pairs = kind === 'ring' ? [[880, 0], [1100, 0.18], [880, 0.5], [1100, 0.68]] : [[440, 0], [480, 0]]
        for (const [f, at] of pairs) {
          const o = toneCtx.createOscillator()
          const g = toneCtx.createGain()
          o.frequency.value = f
          g.gain.setValueAtTime(0.0001, t + at)
          g.gain.exponentialRampToValueAtTime(kind === 'ring' ? 0.14 : 0.06, t + at + 0.03)
          g.gain.exponentialRampToValueAtTime(0.0001, t + at + (kind === 'ring' ? 0.16 : 1.2))
          o.connect(g).connect(toneCtx.destination)
          o.start(t + at)
          o.stop(t + at + 1.3)
        }
      }
      beep()
      toneTimer = setInterval(beep, kind === 'ring' ? 2200 : 3000)
    } catch {}
  }
  function stopTone() {
    clearInterval(toneTimer)
    toneTimer = null
    if (toneCtx) { try { toneCtx.close() } catch {} toneCtx = null }
  }

  // ---- Audio engine ----

  async function startAudio(c) {
    const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 })
    c.ctx = ctx
    await ctx.audioWorklet.addModule('audio-worklet.js')
    c.mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } })
    const src = ctx.createMediaStreamSource(c.mic)
    const micNode = new AudioWorkletNode(ctx, 'mesh-mic')
    const speaker = new AudioWorkletNode(ctx, 'mesh-speaker', { outputChannelCount: [2] })
    speaker.connect(ctx.destination)
    src.connect(micNode)
    c.speaker = speaker

    // Encoder: 48 kHz capture -> 16 kHz mono -> Opus 20 ms frames, two per packet.
    let seq = 0
    let frames = []
    const enc = new AudioEncoder({
      output: (chunk) => {
        const f = new Uint8Array(chunk.byteLength)
        chunk.copyTo(f)
        frames.push(f)
        if (frames.length === 2) {
          const size = 3 + frames.reduce((n, x) => n + 2 + x.length, 0)
          const p = new Uint8Array(size)
          const dv = new DataView(p.buffer)
          dv.setUint16(0, seq++ & 0xffff)
          p[2] = frames.length
          let o = 3
          for (const x of frames) { dv.setUint16(o, x.length); p.set(x, o + 2); o += 2 + x.length }
          frames = []
          M.call('call.audio', { key: c.peer, data: b64(p) }).catch(() => {})
        }
      },
      error: (e) => console.warn('audio encoder', e)
    })
    enc.configure({ codec: 'opus', sampleRate: AUDIO_RATE, numberOfChannels: 1, bitrate: 24000, opus: { frameDuration: 20000, complexity: 5 } })
    c.enc = enc
    let pending = new Float32Array(0)
    let ts = 0
    const ratio = ctx.sampleRate / AUDIO_RATE
    const FRAME = AUDIO_RATE / 50 // 320 samples = 20 ms
    micNode.port.onmessage = (e) => {
      if (c.muted || enc.state !== 'configured') return
      const input = e.data
      // Simple averaging downsampler (48k -> 16k).
      const n = Math.floor(input.length / ratio)
      const down = new Float32Array(n)
      for (let i = 0; i < n; i++) {
        let s = 0
        for (let k = 0; k < ratio; k++) s += input[Math.floor(i * ratio + k)] || 0
        down[i] = s / ratio
      }
      const merged = new Float32Array(pending.length + down.length)
      merged.set(pending)
      merged.set(down, pending.length)
      let o = 0
      while (merged.length - o >= FRAME) {
        const data = merged.slice(o, o + FRAME)
        o += FRAME
        enc.encode(new AudioData({ format: 'f32', sampleRate: AUDIO_RATE, numberOfFrames: FRAME, numberOfChannels: 1, timestamp: ts, data }))
        ts += 20000
      }
      pending = merged.slice(o)
    }

    // Decoder: Opus -> PCM -> speaker worklet (which plays at the context rate).
    const dec = new AudioDecoder({
      output: (ad) => {
        const n = ad.numberOfFrames
        const pcm = new Float32Array(n)
        ad.copyTo(pcm, { planeIndex: 0, format: 'f32-planar' })
        const rate = ad.sampleRate
        ad.close()
        // Resample to the context rate.
        const outLen = Math.round(n * ctx.sampleRate / rate)
        const out = new Float32Array(outLen)
        for (let i = 0; i < outLen; i++) out[i] = pcm[Math.min(n - 1, Math.floor(i * rate / ctx.sampleRate))]
        speaker.port.postMessage(out)
      },
      error: (e) => console.warn('audio decoder', e)
    })
    dec.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 1 })
    c.dec = dec
    c.decTs = 0
  }

  function onAudioPacket(c, data) {
    if (!c.dec || c.dec.state !== 'configured') return
    const p = unb64(data)
    if (p.length < 3) return
    const dv = new DataView(p.buffer)
    const count = p[2]
    let o = 3
    for (let i = 0; i < count; i++) {
      if (o + 2 > p.length) return
      const len = dv.getUint16(o)
      if (!len || o + 2 + len > p.length) return
      c.dec.decode(new EncodedAudioChunk({ type: 'key', timestamp: c.decTs, data: p.subarray(o + 2, o + 2 + len) }))
      c.decTs += 20000
      o += 2 + len
    }
  }

  // ---- Video engine ----
  async function startCamera(c) {
    if (!videoSupported()) { toast("Video isn't supported on this device yet"); c.video = false; return }
    try {
      c.cam = await navigator.mediaDevices.getUserMedia({ video: { facingMode: c.front === false ? 'environment' : 'user', width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 20 } } })
    } catch {
      toast("Couldn't open the camera")
      c.video = false
      return
    }
    const vid = document.createElement('video')
    vid.muted = true
    vid.playsInline = true
    vid.srcObject = c.cam
    await vid.play().catch(() => {})
    c.localVideo = vid
    renderCall()
    const canvas = document.createElement('canvas')
    const g = canvas.getContext('2d')
    let seq = 0
    let width = 0
    let height = 0
    c.bitrate = 450000
    c.inflight = new Map() // seq -> sent at
    c.forceKey = true
    const makeEncoder = () => {
      if (c.venc) try { c.venc.close() } catch {}
      c.venc = new VideoEncoder({
        output: (chunk) => {
          const body = new Uint8Array(chunk.byteLength)
          chunk.copyTo(body)
          const p = new Uint8Array(10 + body.length)
          const dv = new DataView(p.buffer)
          const s = seq++
          dv.setUint32(0, s)
          p[4] = chunk.type === 'key' ? 1 : 0
          p[5] = 0
          dv.setUint16(6, width)
          dv.setUint16(8, height)
          p.set(body, 10)
          c.inflight.set(s, Date.now())
          M.call('call.video', { key: c.peer, data: b64(p) }).catch(() => {})
        },
        error: (e) => console.warn('video encoder', e)
      })
      c.venc.configure({ codec: 'avc1.42E01F', width, height, bitrate: c.bitrate, framerate: 20, latencyMode: 'realtime', avc: { format: 'annexb' } })
    }
    c.camTimer = setInterval(() => {
      if (!c.video || vid.readyState < 2 || !vid.videoWidth) return
      // Congestion control like Android: at most 6 frames waiting for an ack.
      const now = Date.now()
      for (const [s, t] of c.inflight) if (now - t > 3000) c.inflight.delete(s)
      if (c.inflight.size >= 6) return
      const w = vid.videoWidth & ~1
      const hh = vid.videoHeight & ~1
      if (w !== width || hh !== height || !c.venc) {
        width = w
        height = hh
        canvas.width = w
        canvas.height = hh
        makeEncoder()
        c.forceKey = true
      }
      g.drawImage(vid, 0, 0, w, hh)
      const frame = new VideoFrame(canvas, { timestamp: now * 1000 })
      c.venc.encode(frame, { keyFrame: c.forceKey })
      c.forceKey = false
      frame.close()
    }, 50)
    signal(c.peer, { t: 'video', id: c.id, on: true })
  }

  function stopCamera(c, tell = true) {
    clearInterval(c.camTimer)
    c.camTimer = null
    if (c.venc) { try { c.venc.close() } catch {} c.venc = null }
    if (c.cam) for (const t of c.cam.getTracks()) t.stop()
    c.cam = null
    c.localVideo = null
    if (tell) signal(c.peer, { t: 'video', id: c.id, on: false })
  }

  function onVideoFrame(c, data) {
    const p = unb64(data)
    if (p.length < 11) return
    const dv = new DataView(p.buffer)
    const s = dv.getUint32(0)
    const key = p[4] === 1
    const rot = p[5]
    signal(c.peer, { t: 'vack', id: c.id, seq: s })
    if (!c.vdec || c.vdec.state === 'closed') {
      if (!key) return
      c.vdec = new VideoDecoder({
        output: (frame) => {
          const cv = c.remoteCanvas
          if (cv) {
            const swap = rot % 2 === 1
            const w = swap ? frame.displayHeight : frame.displayWidth
            const hgt = swap ? frame.displayWidth : frame.displayHeight
            if (cv.width !== w || cv.height !== hgt) { cv.width = w; cv.height = hgt }
            const g = cv.getContext('2d')
            g.save()
            g.translate(w / 2, hgt / 2)
            g.rotate((rot * Math.PI) / 2)
            g.drawImage(frame, -frame.displayWidth / 2, -frame.displayHeight / 2)
            g.restore()
            c.lastFrameAt = Date.now()
          }
          frame.close()
        },
        error: () => { c.vdec = null; signal(c.peer, { t: 'keyreq', id: c.id }) }
      })
      c.vdec.configure({ codec: 'avc1.42E01F', optimizeForLatency: true })
    }
    if (c.vdec.decodeQueueSize > 4 && !key) { signal(c.peer, { t: 'keyreq', id: c.id }); return }
    try {
      c.vdec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: s * 50000, data: p.subarray(10) }))
    } catch {
      c.vdec = null
      signal(c.peer, { t: 'keyreq', id: c.id })
    }
  }

  // ---- Watching a live (same video and audio as calls; from the Android app's lives) ----
  let liveView = null // { host, el, canvas, chat, count, vdec, ctx, speaker, adec, adecTs, lastFrameAt, timer }
  async function watchLive(item) {
    if (liveView) closeLive()
    const host = item.author.id
    const canvas = h('canvas', { class: 'lv-canvas' })
    const chat = h('div', { class: 'lv-chat' })
    const count = h('span', { class: 'lv-count' }, icon('play', 12), '0')
    const wait = h('div', { class: 'lv-wait' }, h('div', { class: 'spinner' }), h('div', {}, 'Joining ' + (item.author.handle ? '@' + item.author.handle : item.author.name) + '…'))
    const input = h('input', { class: 'lv-input', placeholder: 'Say something…', maxlength: 200 })
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && input.value.trim()) { M.call('live.chat', { host, text: input.value.trim() }).catch(() => {}); addLiveChat('You', input.value.trim()); input.value = '' } })
    const el = h('div', { class: 'live-view' }, canvas, wait,
      h('div', { class: 'lv-head' },
        avatar(item.author.name || '?', item.author.id, 40),
        h('div', { style: { minWidth: 0 } }, h('div', { class: 'lv-name ellipsis' }, item.author.handle ? '@' + item.author.handle : item.author.name), h('div', { class: 'lv-sub ellipsis' }, item.title || 'Live now')),
        h('span', { class: 'lv-badge' }, 'LIVE'), count, h('span', { style: { flex: 1 } }),
        h('button', { class: 'icon-btn', style: { color: '#fff' }, title: 'Close', onClick: closeLive }, icon('x', 24))),
      chat,
      h('div', { class: 'lv-foot' }, input, h('button', { class: 'lv-heart', title: 'Send a heart', onClick: () => { M.call('live.heart', { host }).catch(() => {}); burstHeart() } }, '❤')))
    document.body.appendChild(el)
    liveView = { host, el, canvas, chat, count, wait, lastFrameAt: 0, adecTs: 0 }
    // Sound: the live's Opus audio, played through the call speaker.
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 })
      await ctx.audioWorklet.addModule('audio-worklet.js')
      const speaker = new AudioWorkletNode(ctx, 'mesh-speaker', { outputChannelCount: [2] })
      speaker.connect(ctx.destination)
      const adec = new AudioDecoder({
        output: (ad) => {
          const n = ad.numberOfFrames; const pcm = new Float32Array(n)
          ad.copyTo(pcm, { planeIndex: 0, format: 'f32-planar' }); const rate = ad.sampleRate; ad.close()
          const outLen = Math.round(n * ctx.sampleRate / rate); const out = new Float32Array(outLen)
          for (let i = 0; i < outLen; i++) out[i] = pcm[Math.min(n - 1, Math.floor(i * rate / ctx.sampleRate))]
          speaker.port.postMessage(out)
        },
        error: () => {}
      })
      adec.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 1 })
      Object.assign(liveView, { ctx, speaker, adec })
    } catch {}
    M.call('live.watch', { host, id: item.lid }).catch(oops)
    // No picture for 20 seconds: the host has gone.
    liveView.timer = setInterval(() => {
      if (!liveView) return
      if (Date.now() - (liveView.lastFrameAt || liveView.startedAt) > 20000) liveEnded(false)
    }, 2000)
    liveView.startedAt = Date.now()
  }
  function addLiveChat(name, text) {
    if (!liveView) return
    liveView.chat.appendChild(h('div', { class: 'lv-msg' }, h('b', {}, name + ' '), text))
    while (liveView.chat.children.length > 40) liveView.chat.firstChild.remove()
    liveView.chat.scrollTop = liveView.chat.scrollHeight
  }
  function burstHeart() {
    if (!liveView) return
    const hrt = h('div', { class: 'lv-float' }, '❤')
    hrt.style.left = (70 + Math.random() * 20) + '%'
    liveView.el.appendChild(hrt)
    setTimeout(() => hrt.remove(), 1600)
  }
  function liveEnded(full) {
    if (!liveView || liveView.ended) return
    liveView.ended = true
    clearInterval(liveView.timer)
    liveView.wait.replaceChildren(h('div', { class: 't-h2' }, full ? 'This live is full' : 'This live has ended'), h('button', { class: 'btn light', style: { marginTop: '14px' }, onClick: closeLive }, 'Close'))
    liveView.wait.hidden = false
  }
  function closeLive() {
    if (!liveView) return
    const v = liveView
    liveView = null
    clearInterval(v.timer)
    M.call('live.leave', { host: v.host }).catch(() => {})
    try { v.vdec && v.vdec.close() } catch {}
    try { v.adec && v.adec.close() } catch {}
    try { v.ctx && v.ctx.close() } catch {}
    v.el.remove()
  }
  function onLiveVideo(data) {
    const v = liveView
    if (!v || v.ended) return
    const p = unb64(data)
    if (p.length < 11) return
    const dv = new DataView(p.buffer)
    const s = dv.getUint32(0)
    const key = p[4] === 1
    const rot = p[5]
    if (!v.vdec || v.vdec.state === 'closed') {
      if (!key) { if (!v.askedKey || Date.now() - v.askedKey > 2000) { v.askedKey = Date.now(); M.call('live.keyreq', { host: v.host }).catch(() => {}) } return }
      v.vdec = new VideoDecoder({
        output: (frame) => {
          const cv = v.canvas
          const swap = rot % 2 === 1
          const w = swap ? frame.displayHeight : frame.displayWidth
          const hgt = swap ? frame.displayWidth : frame.displayHeight
          if (cv.width !== w || cv.height !== hgt) { cv.width = w; cv.height = hgt }
          const g = cv.getContext('2d')
          g.save(); g.translate(w / 2, hgt / 2); g.rotate((rot * Math.PI) / 2); g.drawImage(frame, -frame.displayWidth / 2, -frame.displayHeight / 2); g.restore()
          frame.close()
          v.lastFrameAt = Date.now()
          v.wait.hidden = true
        },
        error: () => { v.vdec = null; M.call('live.keyreq', { host: v.host }).catch(() => {}) }
      })
      v.vdec.configure({ codec: 'avc1.42E01F', optimizeForLatency: true })
    }
    if (v.vdec.decodeQueueSize > 6 && !key) return
    try { v.vdec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: s * 33000, data: p.subarray(10) })) } catch { v.vdec = null }
  }
  function onLiveAudio(data) {
    const v = liveView
    if (!v || !v.adec || v.adec.state !== 'configured') return
    const p = unb64(data)
    if (p.length < 3) return
    const dv = new DataView(p.buffer)
    let o = 3
    for (let i = 0; i < p[2]; i++) {
      if (o + 2 > p.length) return
      const len = dv.getUint16(o)
      if (!len || o + 2 + len > p.length) return
      try { v.adec.decode(new EncodedAudioChunk({ type: 'key', timestamp: v.adecTs, data: p.subarray(o + 2, o + 2 + len) })) } catch {}
      v.adecTs += 20000
      o += 2 + len
    }
  }
  M.on('live-video', (e) => { if (liveView && e.host === liveView.host) onLiveVideo(e.data) })
  M.on('live-audio', (e) => { if (liveView && e.host === liveView.host) onLiveAudio(e.data) })
  M.on('live-chat', (e) => { if (liveView && e.host === liveView.host && e.text) addLiveChat(e.name || 'Someone', e.text) })
  M.on('live-hearts', (e) => { if (liveView && e.host === liveView.host) for (let i = 0; i < Math.min(8, e.n || 1); i++) setTimeout(burstHeart, i * 120) })
  M.on('live-count', (e) => { if (liveView && e.host === liveView.host) liveView.count.replaceChildren(icon('play', 12), String(e.viewers || 0)) })
  M.on('live-welcome', (e) => { if (liveView && e.host === liveView.host) liveView.count.replaceChildren(icon('play', 12), String(e.viewers || 0)) })
  M.on('live-ended', (e) => { if (liveView && e.host === liveView.host) liveEnded(!!e.full) })

  // ---- Going live: the studio ----
  // Sources on the left (camera, screens, apps), the picture in the middle, settings and chat on
  // the right. Everything is drawn onto one canvas (the main source plus an optional camera
  // bubble) and that canvas is what viewers get, so what you see is exactly what goes out.
  let hostLive = null
  const QUALITY = { 480: [854, 480, 1_000_000], 720: [1280, 720, 2_200_000], 1080: [1920, 1080, 4_000_000] }
  const SPOTS = ['br', 'bl', 'tl', 'tr']
  const SPOT_NAMES = { br: 'Bottom right', bl: 'Bottom left', tl: 'Top left', tr: 'Top right' }

  async function goLive() {
    if (hostLive) return
    if (!videoSupported() || typeof AudioEncoder === 'undefined') { toast("Going live isn't supported on this device yet"); return }
    const native = M.native || {}
    const desk = typeof native.captureSources === 'function'
    const L = hostLive = {
      cam: null, screen: null, mic: null, mainKind: 'camera', mainId: null,
      bubble: true, spot: 'br', orient: 'landscape', quality: 720,
      micId: '', camId: '', micOn: true, sound: true,
      viewers: 0, peak: 0, bytes: 0, frames: 0, id: null
    }
    const vid = () => { const v = h('video', { muted: true, playsinline: true, autoplay: true }); v.muted = true; return v }
    const mainVid = vid(); const camVid = vid()
    const stage = h('canvas', { class: 'sd-canvas' })
    const g = stage.getContext('2d')
    const waiting = h('div', { class: 'sd-wait' }, h('div', { class: 'spinner' }))

    // Top bar
    const status = h('span', { class: 'sd-live', hidden: true }, 'LIVE')
    const clockEl = h('span', { class: 'sd-chip', hidden: true }, '0:00')
    const count = h('span', { class: 'sd-chip' }, icon('users', 14), '0')
    const stats = h('span', { class: 'sd-chip sd-stats', hidden: true }, '')
    const goBtn = h('button', { class: 'sd-go' }, h('span', { class: 'st-dot' }), 'Go live')
    const closeBtn = h('button', { class: 'icon-btn', title: 'Close', onClick: () => endLive() }, icon('x', 22))
    const top = h('div', { class: 'sd-top' }, closeBtn, h('div', { class: 'sd-title' }, 'Live studio'), status, clockEl, count, stats, h('span', { style: { flex: 1 } }), goBtn)

    // Sources
    const srcList = h('div', { class: 'sd-srcs' })
    const sources = h('div', { class: 'sd-left' },
      h('div', { class: 'sd-sec' }, h('span', {}, 'Sources'), desk ? h('button', { class: 'sd-refresh', onClick: () => loadSources() }, 'Refresh') : null),
      srcList)

    // Settings
    const title = h('input', { class: 'st-input', placeholder: 'What is your live about?', maxlength: 100 })
    const seg = (opts, get, set) => {
      const el = h('div', { class: 'st-seg' })
      const draw = () => el.replaceChildren(...opts.map(([v, label]) => h('button', { class: get() === v ? 'on' : '', onClick: () => { set(v); draw() } }, label)))
      draw()
      return el
    }
    const sw = (get, set) => {
      const el = h('button', { class: 'switch' + (get() ? ' on' : ''), role: 'switch' })
      el.onclick = () => { set(!get()); el.classList.toggle('on', get()) }
      return el
    }
    const row = (label, ctl, sub) => h('div', { class: 'sd-row' }, h('div', { class: 'sd-row-t' }, h('div', {}, label), sub ? h('div', { class: 'sd-row-s' }, sub) : null), ctl)
    const micSel = h('select', { class: 'sd-select', onChange: (e) => { L.micId = e.target.value; openMic() } })
    const camSel = h('select', { class: 'sd-select', onChange: (e) => { L.camId = e.target.value; openCam() } })
    const meter = h('div', { class: 'sd-meter' }, h('i'))
    const micBtn = h('button', { class: 'sd-round on', title: 'Microphone', onClick: () => setMicOn(!L.micOn) }, icon('mic', 20))
    const spotBtn = h('button', { class: 'sd-pill', onClick: () => { L.spot = SPOTS[(SPOTS.indexOf(L.spot) + 1) % SPOTS.length]; spotBtn.textContent = SPOT_NAMES[L.spot] } }, SPOT_NAMES[L.spot])
    const bubbleRow = row('Camera bubble', h('div', { class: 'sd-inline' }, spotBtn, sw(() => L.bubble, (v) => { L.bubble = v })), 'Your face over the screen or app')
    const soundRow = row('PC sound', sw(() => L.sound, (v) => { L.sound = v; if (L.sysGain) L.sysGain.gain.value = v ? 1 : 0 }), 'What the screen or app plays')
    const settings = h('div', { class: 'sd-pane' },
      h('label', { class: 'st-label' }, 'Title'), title,
      h('label', { class: 'st-label' }, 'Layout'),
      seg([['landscape', 'Landscape'], ['portrait', 'Portrait']], () => L.orient, (v) => { L.orient = v }),
      h('label', { class: 'st-label' }, 'Quality'),
      seg([[480, '480p'], [720, '720p'], [1080, '1080p']], () => L.quality, (v) => { L.quality = v }),
      h('label', { class: 'st-label' }, 'Microphone'),
      h('div', { class: 'sd-inline' }, micBtn, micSel), meter,
      h('label', { class: 'st-label' }, 'Camera'), camSel,
      bubbleRow, soundRow)
    const chat = h('div', { class: 'lv-chat sd-chat' }, h('div', { class: 'sd-empty' }, 'Comments from viewers show here.'))
    const chatPane = h('div', { class: 'sd-pane sd-chatpane' }, chat)
    let tab = 'settings'
    const tabs = h('div', { class: 'st-seg sd-tabs' })
    const panel = h('div', { class: 'sd-right' }, tabs, settings, chatPane)
    const drawTabs = () => {
      tabs.replaceChildren(...[['settings', 'Settings'], ['chat', 'Chat']].map(([v, label]) => h('button', { class: tab === v ? 'on' : '', onClick: () => { tab = v; drawTabs() } }, label)))
      settings.hidden = tab !== 'settings'; chatPane.hidden = tab !== 'chat'
    }
    drawTabs()

    const el = h('div', { class: 'sd' }, top, h('div', { class: 'sd-body' }, sources, h('div', { class: 'sd-mid' }, h('div', { class: 'sd-stage' }, stage, waiting)), panel))
    document.body.appendChild(el)
    Object.assign(L, { el, chat, count, status, clockEl, stats, goBtn, mainVid, camVid, stage })

    // ---- Picture ----
    const outSize = () => { const [w, hh] = QUALITY[L.quality]; return L.orient === 'portrait' ? [hh, w] : [w, hh] }
    function fit (v, x, y, w, hh, cover) {
      const vw = v.videoWidth; const vh = v.videoHeight
      if (!vw || !vh) return
      const k = cover ? Math.max(w / vw, hh / vh) : Math.min(w / vw, hh / vh)
      const dw = vw * k; const dh = vh * k
      g.drawImage(v, x + (w - dw) / 2, y + (hh - dh) / 2, dw, dh)
    }
    function draw () {
      const [W, H] = outSize()
      if (stage.width !== W || stage.height !== H) { stage.width = W; stage.height = H; stage.style.aspectRatio = W + ' / ' + H }
      g.fillStyle = '#000'; g.fillRect(0, 0, W, H)
      const ready = mainVid.readyState >= 2 && mainVid.videoWidth
      waiting.hidden = !!ready
      if (!ready) return false
      fit(mainVid, 0, 0, W, H, L.mainKind === 'camera')
      if (L.mainKind === 'screen' && L.bubble && L.cam && camVid.readyState >= 2) {
        const d = Math.round(Math.min(W, H) * 0.26); const m = Math.round(Math.min(W, H) * 0.04)
        const x = L.spot[1] === 'r' ? W - d - m : m; const y = L.spot[0] === 'b' ? H - d - m : m
        g.save(); g.beginPath(); g.arc(x + d / 2, y + d / 2, d / 2, 0, Math.PI * 2); g.clip()
        fit(camVid, x, y, d, d, true); g.restore()
        g.lineWidth = Math.max(3, d * 0.025); g.strokeStyle = '#fff'
        g.beginPath(); g.arc(x + d / 2, y + d / 2, d / 2, 0, Math.PI * 2); g.stroke()
      }
      return true
    }

    // ---- Sources ----
    function tile (key, label, pic, ic, onPick) {
      const on = (key === 'camera' && L.mainKind === 'camera') || key === L.mainId
      return h('button', { class: 'sd-src' + (on ? ' on' : ''), onClick: onPick },
        h('div', { class: 'sd-thumb' }, pic ? h('img', { src: pic, alt: '' }) : icon(ic, 28)),
        h('div', { class: 'sd-src-name' }, label))
    }
    let found = []
    function drawSources () {
      const kids = [tile('camera', 'Camera', null, 'video', () => setMain('camera'))]
      const screens = found.filter((s) => s.kind === 'screen'); const apps = found.filter((s) => s.kind === 'window')
      if (desk) {
        if (screens.length) kids.push(h('div', { class: 'sd-sub' }, 'Screens'), ...screens.map((s, i) => tile(s.id, screens.length > 1 ? 'Screen ' + (i + 1) : 'Whole screen', s.thumb, 'expand', () => setMain('screen', s.id))))
        if (apps.length) kids.push(h('div', { class: 'sd-sub' }, 'Apps'), ...apps.map((s) => tile(s.id, s.name, s.thumb, 'grid', () => setMain('screen', s.id))))
      } else if (navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) {
        kids.push(tile('screen', 'Screen', null, 'expand', () => setMain('screen', 'screen')))
      }
      srcList.replaceChildren(...kids)
    }
    async function loadSources () {
      if (desk) found = await native.captureSources().catch(() => [])
      drawSources()
    }

    async function openCam () {
      if (L.cam) L.cam.getTracks().forEach((t) => t.stop())
      L.cam = await navigator.mediaDevices.getUserMedia({ video: { deviceId: L.camId ? { exact: L.camId } : undefined, width: { ideal: 1920 }, height: { ideal: 1080 }, frameRate: { ideal: 30 } } }).catch(() => null)
      camVid.srcObject = L.cam
      if (L.mainKind === 'camera') mainVid.srcObject = L.cam
      if (!L.cam) toast("Couldn't open the camera")
      camVid.play().catch(() => {}); mainVid.play().catch(() => {})
    }
    async function setMain (kind, id) {
      if (kind === 'camera') {
        if (L.screen) L.screen.getTracks().forEach((t) => t.stop())
        L.screen = null; L.mainKind = 'camera'; L.mainId = null
        if (native.hideFromCapture) native.hideFromCapture(false)
        if (!L.cam) await openCam()
        mainVid.srcObject = L.cam
        mainVid.play().catch(() => {})
        connectSys()
      } else {
        if (desk) await native.pickSource(id, true)
        // A screen shows Meshron itself: leave this window out, or the live repeats inside itself forever.
        if (native.hideFromCapture) await native.hideFromCapture(true)
        const s = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true }).catch(() => null)
        if (!s) { toast("Couldn't share that"); if (L.mainKind === 'camera' && native.hideFromCapture) native.hideFromCapture(false); return }
        if (L.screen) L.screen.getTracks().forEach((t) => t.stop())
        L.screen = s; L.mainKind = 'screen'; L.mainId = id
        mainVid.srcObject = s
        mainVid.play().catch(() => {})
        // The app was closed (or sharing stopped): back to the camera.
        const vt = s.getVideoTracks()[0]
        if (vt) vt.onended = () => { if (hostLive === L && L.screen === s) { toast('That app or screen stopped sharing'); setMain('camera') } }
        connectSys()
        if (!L.cam && L.bubble) openCam()
      }
      drawSources()
    }

    // ---- Sound: microphone + the screen's sound, mixed, Opus like calls ----
    const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 })
    L.ctx = ctx
    try {
      await ctx.audioWorklet.addModule('audio-worklet.js')
      L.mix = new AudioWorkletNode(ctx, 'mesh-mic')
      L.micGain = ctx.createGain(); L.sysGain = ctx.createGain()
      L.micGain.connect(L.mix); L.sysGain.connect(L.mix)
      L.mix.port.onmessage = (e) => onAudio(e.data)
    } catch {}
    let level = 0
    async function openMic () {
      if (L.micSrc) try { L.micSrc.disconnect() } catch {}
      if (L.mic) L.mic.getTracks().forEach((t) => t.stop())
      L.mic = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: L.micId ? { exact: L.micId } : undefined, echoCancellation: true, noiseSuppression: true, channelCount: 1 } }).catch(() => null)
      if (L.mic && L.micGain) { L.micSrc = ctx.createMediaStreamSource(L.mic); L.micSrc.connect(L.micGain) }
    }
    function connectSys () {
      if (L.sysSrc) try { L.sysSrc.disconnect() } catch {}
      L.sysSrc = null
      const tracks = L.screen ? L.screen.getAudioTracks() : []
      if (tracks.length && L.sysGain) { L.sysSrc = ctx.createMediaStreamSource(new MediaStream(tracks)); L.sysSrc.connect(L.sysGain) }
      L.sysGain && (L.sysGain.gain.value = L.sound ? 1 : 0)
    }
    function setMicOn (on) {
      L.micOn = on
      if (L.micGain) L.micGain.gain.value = on ? 1 : 0
      micBtn.classList.toggle('on', on)
      micBtn.replaceChildren(icon(on ? 'mic' : 'micOff', 20))
    }
    const RATE = 48000; const FRAME = RATE / 50
    let pending = new Float32Array(0); let ts = 0
    function onAudio (data) {
      let sum = 0
      for (let i = 0; i < data.length; i += 8) sum += data[i] * data[i]
      level = Math.max(level * 0.85, Math.min(1, Math.sqrt(sum / (data.length / 8)) * 4))
      if (!L.aenc || L.aenc.state !== 'configured') return
      const merged = new Float32Array(pending.length + data.length); merged.set(pending); merged.set(data, pending.length)
      let o = 0
      while (merged.length - o >= FRAME) { L.aenc.encode(new AudioData({ format: 'f32', sampleRate: RATE, numberOfFrames: FRAME, numberOfChannels: 1, timestamp: ts, data: merged.slice(o, o + FRAME) })); ts += 20000; o += FRAME }
      pending = merged.slice(o)
    }

    async function listDevices () {
      const all = await navigator.mediaDevices.enumerateDevices().catch(() => [])
      const opts = (kind, sel, cur) => {
        const list = all.filter((d) => d.kind === kind)
        sel.replaceChildren(...list.map((d, i) => h('option', { value: d.deviceId, selected: d.deviceId === cur }, d.label || (kind === 'audioinput' ? 'Microphone ' : 'Camera ') + (i + 1))))
        sel.disabled = !list.length
      }
      opts('audioinput', micSel, L.micId); opts('videoinput', camSel, L.camId)
    }

    // ---- The loop: draw, meter, and (when live) encode ----
    L.loop = setInterval(() => {
      const ok = draw()
      meter.firstChild.style.width = Math.round((L.micOn ? level : 0) * 100) + '%'
      level *= 0.9
      if (ok && L.venc) encodeFrame()
    }, 33)
    L.statTimer = setInterval(() => {
      if (!L.id) return
      stats.textContent = Math.round(L.bytes * 8 / 1000) + ' kbps · ' + L.frames + ' fps'
      L.bytes = 0; L.frames = 0
    }, 1000)

    let seq = 0; let encW = 0; let encH = 0; let lastKey = 0
    function makeEncoder () {
      if (L.venc) try { L.venc.close() } catch {}
      encW = stage.width; encH = stage.height
      L.venc = new VideoEncoder({
        output: (chunk) => {
          const body = new Uint8Array(chunk.byteLength); chunk.copyTo(body)
          const p = new Uint8Array(10 + body.length); const dv = new DataView(p.buffer)
          dv.setUint32(0, seq++); p[4] = chunk.type === 'key' ? 1 : 0; p[5] = 0; dv.setUint16(6, encW); dv.setUint16(8, encH); p.set(body, 10)
          L.bytes += p.length; L.frames++
          M.call('live.frame', { kind: 'v', data: b64(p) }).catch(() => {})
        },
        error: () => {}
      })
      // Baseline H.264 like calls (Android viewers decode it in hardware); level 4 for 1080p.
      const big = encW * encH > 1280 * 720
      L.venc.configure({ codec: big ? 'avc1.420028' : 'avc1.42E01F', width: encW, height: encH, bitrate: QUALITY[L.quality][2], framerate: 30, latencyMode: 'realtime', avc: { format: 'annexb' } })
      L.forceKey = true
    }
    function encodeFrame () {
      if (stage.width !== encW || stage.height !== encH) makeEncoder()
      if (L.venc.encodeQueueSize > 4) return // the PC can't keep up: drop a frame rather than lag
      const now = Date.now()
      if (now - lastKey > 2000) L.forceKey = true
      const frame = new VideoFrame(stage, { timestamp: now * 1000 })
      try { L.venc.encode(frame, { keyFrame: L.forceKey }) } catch {}
      if (L.forceKey) lastKey = now
      L.forceKey = false
      frame.close()
    }

    L.start = async () => {
      const s = await M.call('reels.status').catch(() => null)
      if (!s || !s.ready) throw new Error("Meshron is still connecting. Try again in a moment (check the internet if it doesn't).")
      const id = Math.random().toString(36).slice(2, 12)
      await M.call('live.start', { id, title: title.value.trim() })
      L.id = id // only once the live really started (ending asks first only then)
      makeEncoder()
      try {
        L.aenc = new AudioEncoder({
          output: (chunk) => {
            const f = new Uint8Array(chunk.byteLength); chunk.copyTo(f); L.aframes.push(f)
            if (L.aframes.length === 2) {
              const size = 3 + L.aframes.reduce((n, x) => n + 2 + x.length, 0)
              const p = new Uint8Array(size); const dv = new DataView(p.buffer)
              dv.setUint16(0, L.aseq++ & 0xffff); p[2] = L.aframes.length
              let o = 3
              for (const x of L.aframes) { dv.setUint16(o, x.length); p.set(x, o + 2); o += 2 + x.length }
              L.aframes = []
              L.bytes += p.length
              M.call('live.frame', { kind: 'a', data: b64(p) }).catch(() => {})
            }
          },
          error: () => {}
        })
        L.aseq = 0; L.aframes = []
        L.aenc.configure({ codec: 'opus', sampleRate: RATE, numberOfChannels: 1, bitrate: 64000, opus: { frameDuration: 20000 } })
      } catch {}
      el.classList.add('on-air')
      status.hidden = false; clockEl.hidden = false; stats.hidden = false
      goBtn.className = 'sd-end'; goBtn.replaceChildren('End live'); goBtn.disabled = false
      goBtn.onclick = () => endLive()
      tab = 'chat'; drawTabs()
      const began = Date.now()
      L.clock = setInterval(() => { const t = Math.floor((Date.now() - began) / 1000); clockEl.textContent = (t >= 3600 ? Math.floor(t / 3600) + ':' + String(Math.floor(t / 60) % 60).padStart(2, '0') : Math.floor(t / 60)) + ':' + String(t % 60).padStart(2, '0') }, 1000)
    }
    goBtn.onclick = async () => {
      if (!mainVid.srcObject) return toast('Pick a source first')
      goBtn.disabled = true
      try { await L.start() } catch (e) { goBtn.disabled = false; oops(e) }
    }

    // Open: camera + microphone, then the lists (device names appear once allowed).
    await Promise.all([openCam(), openMic()])
    setMicOn(true)
    await listDevices()
    await loadSources()
  }

  async function endLive() {
    const L = hostLive
    if (!L) return
    if (L.id && !(await confirmBox('End your live?', 'Everyone watching will see that it ended.', 'End live', true))) return
    if (hostLive !== L) return
    hostLive = null
    clearInterval(L.loop); clearInterval(L.clock); clearInterval(L.statTimer)
    if (L.id) M.call('live.stop', { id: L.id }).catch(() => {})
    try { L.venc && L.venc.close() } catch {}
    try { L.aenc && L.aenc.close() } catch {}
    try { L.ctx && L.ctx.close() } catch {}
    for (const s of [L.cam, L.screen, L.mic]) if (s) s.getTracks().forEach((t) => t.stop())
    if (M.native && M.native.hideFromCapture) M.native.hideFromCapture(false)
    L.el.remove()
    if (L.id) toast('Your live ended' + (L.peak ? ' · ' + L.peak + ' watched at most' : ''))
  }
  M.on('live-key', () => { if (hostLive) hostLive.forceKey = true })
  M.on('live-viewers', (e) => {
    if (!hostLive) return
    const n = e.n || 0
    hostLive.peak = Math.max(hostLive.peak, n)
    hostLive.count.replaceChildren(icon('users', 14), String(n))
  })
  M.on('live-chat', (e) => {
    if (!hostLive || e.host) return
    const chat = hostLive.chat
    const empty = chat.querySelector('.sd-empty')
    if (empty) empty.remove()
    chat.appendChild(h('div', { class: 'lv-msg' + (e.joined ? ' joined' : '') }, h('b', {}, (e.name || 'Someone') + ' '), e.joined ? 'joined' : e.text))
    while (chat.children.length > 200) chat.firstChild.remove()
    chat.scrollTop = chat.scrollHeight
  })

  // ---- Call flow ----
  let wakeLock = null
  async function keepAwake(on) {
    try {
      if (M.native.keepAwake) return await M.native.keepAwake(on)
      if (on && !wakeLock && navigator.wakeLock) wakeLock = await navigator.wakeLock.request('screen')
      else if (!on && wakeLock) { await wakeLock.release(); wakeLock = null }
    } catch {}
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && call && !M.native.keepAwake) { wakeLock = null; keepAwake(true) } })

  async function startCall(peer, video) {
    if (call) return toast("You're already in a call")
    if (!callSupported()) return toast(isIos ? 'Calls need a newer version of iOS. Update your iPhone in Settings.' : "Calls aren't supported on this device")
    if (!isOnline(peer)) return toast(`${nameOf(peer)} isn't connected. Calls need you both online or nearby.`)
    call = { phase: 'outgoing', peer, id: newCallId(), video: video && videoSupported(), remoteVideo: false, muted: false, front: true }
    signal(peer, { t: 'offer', id: call.id, video: call.video })
    keepAwake(true)
    playTone('ringback')
    renderCall()
    const id = call.id
    call.timeout = setTimeout(() => { if (call && call.id === id && call.phase === 'outgoing') { signal(peer, { t: 'end', id }); endCall('No answer') } }, RING_MS)
  }

  async function begin() {
    const c = call
    clearTimeout(c.timeout)
    stopTone()
    c.phase = 'active'
    c.startedAt = Date.now()
    keepAwake(true)
    renderCall()
    try {
      await startAudio(c)
    } catch (err) {
      signal(c.peer, { t: 'end', id: c.id })
      return endCall("Couldn't use the microphone")
    }
    if (c.video) await startCamera(c)
    c.ticker = setInterval(() => {
      const el = $('#call-time')
      if (el && call) el.textContent = durationText(Date.now() - call.startedAt)
      // Ends the call if the connection is gone for too long.
      if (call && !isOnline(call.peer)) { call.lostSince = call.lostSince || Date.now(); if (Date.now() - call.lostSince > 20000) { endCall('Connection lost') } }
      else if (call) call.lostSince = 0
    }, 1000)
  }

  function endCall(message) {
    const c = call
    if (!c) return
    call = null
    keepAwake(false)
    clearTimeout(c.timeout)
    clearInterval(c.ticker)
    stopTone()
    stopCamera(c, false)
    if (c.mic) for (const t of c.mic.getTracks()) t.stop()
    for (const k of ['enc', 'dec', 'vdec']) if (c[k]) try { c[k].close() } catch {}
    if (c.ctx) try { c.ctx.close() } catch {}
    // A note in the chat, like WhatsApp. The caller sends it as a message, so the other person sees
    // it (as a missed call if they never picked up) even if they were offline when called.
    const out = c.phase !== 'incoming' && c.incoming !== true
    if (out) {
      const result = c.startedAt ? 'answered' : message === 'Declined' ? 'declined' : 'noanswer'
      M.call('chat.call', { conv: c.peer, video: !!(c.videoCall || c.video), result, seconds: c.startedAt ? (Date.now() - c.startedAt) / 1000 : 0 }).catch(() => {})
    }
    const root = $('#call')
    if (root) {
      root.querySelector('.call-status').textContent = message
      root.classList.add('ending')
      setTimeout(() => root.remove(), 1400)
    }
  }

  const durationText = (ms) => { const s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') }

  function onCallSignal({ key, msg }) {
    if (!msg) return
    const c = call
    const { t, id } = msg
    if (t === 'vack') { if (c && c.id === id && c.inflight) c.inflight.delete(msg.seq); return }
    if (t === 'keyreq') { if (c && c.id === id) c.forceKey = true; return }
    if (t === 'offer') {
      if (c) { if (c.id !== id) signal(key, { t: 'reject', id, reason: 'busy' }); return }
      const ct = contact(key)
      if (!ct || ct.status !== 'ADDED') return
      call = { phase: 'incoming', peer: key, id, video: false, videoCall: !!msg.video, remoteVideo: !!msg.video, muted: false, front: true }
      playTone('ring')
      renderCall()
      if (!M.native.focusState() && M.native.notify) M.native.notify({ title: nameOf(key), body: msg.video ? 'Incoming video call' : 'Incoming call', conv: key })
      call.timeout = setTimeout(() => { if (call && call.id === id && call.phase === 'incoming') endCall('Missed call') }, RING_MS)
      return
    }
    if (!c || c.id !== id || c.peer !== key) return
    if (t === 'answer' && c.phase === 'outgoing') begin()
    else if (t === 'reject' && c.phase === 'outgoing') endCall(msg.reason === 'busy' ? `${nameOf(key)} is on another call` : 'Declined')
    else if (t === 'end') endCall(c.phase === 'incoming' ? 'Missed call' : 'Call ended')
    else if (t === 'unreachable') endCall(`Couldn't reach ${nameOf(key)}`)
    else if (t === 'video') { c.remoteVideo = !!msg.on; if (!msg.on) c.vdec = null; renderCall() }
  }

  function accept(video) {
    const c = call
    if (!c || c.phase !== 'incoming') return
    if (!callSupported()) { signal(c.peer, { t: 'reject', id: c.id, reason: 'declined' }); return endCall(isIos ? 'Calls need a newer version of iOS' : "Calls aren't supported here") }
    c.video = video && videoSupported()
    signal(c.peer, { t: 'answer', id: c.id })
    begin()
  }
  function hangUp() {
    const c = call
    if (!c) return
    if (c.phase === 'incoming') { signal(c.peer, { t: 'reject', id: c.id, reason: 'declined' }); return endCall('Declined') }
    signal(c.peer, { t: 'end', id: c.id })
    endCall('Call ended')
  }

  function callButton(ic, label, onClick, cls = '') {
    return h('div', { class: 'cbtn ' + cls }, h('button', { onClick, 'aria-label': label }, icon(ic, 26)), h('span', {}, label))
  }

  function minimizeCall(min) {
    if (!call) return
    call.min = min
    renderCall()
  }

  /** Drag the minimised call anywhere. */
  function makeDraggable(el) {
    if (el.dataset.drag) return
    el.dataset.drag = '1'
    let sx = 0, sy = 0, ox = 0, oy = 0, moved = false, down = false
    el.addEventListener('pointerdown', (e) => {
      if (!el.classList.contains('mini') || e.target.closest('button')) return
      down = true; moved = false
      const r = el.getBoundingClientRect()
      sx = e.clientX; sy = e.clientY; ox = r.left; oy = r.top
      el.setPointerCapture(e.pointerId)
    })
    el.addEventListener('pointermove', (e) => {
      if (!down) return
      const dx = e.clientX - sx, dy = e.clientY - sy
      if (Math.abs(dx) + Math.abs(dy) > 4) moved = true
      const r = el.getBoundingClientRect()
      el.style.left = Math.max(6, Math.min(innerWidth - r.width - 6, ox + dx)) + 'px'
      el.style.top = Math.max(6, Math.min(innerHeight - r.height - 6, oy + dy)) + 'px'
      el.style.right = 'auto'; el.style.bottom = 'auto'
    })
    el.addEventListener('pointerup', () => { if (down && !moved && el.classList.contains('mini')) minimizeCall(false); down = false })
  }

  function renderCall() {
    const c = call
    let root = $('#call')
    if (!c) { if (root) root.remove(); return }
    if (!root) { root = h('div', { id: 'call', class: 'call' }); layer.appendChild(root) }
    const name = nameOf(c.peer)
    if (c.min && c.phase !== 'incoming') {
      if (!c.remoteCanvas) c.remoteCanvas = h('canvas', { class: 'call-remote' })
      root.className = 'call mini'
      makeDraggable(root)
      const video = c.phase === 'active' && c.remoteVideo
      root.replaceChildren(...[
        video ? c.remoteCanvas : null,
        h('div', { class: 'mini-top' },
          video ? null : avatar(name, c.peer, 56),
          h('div', { class: 'call-name' }, name),
          h('div', { class: 'call-status' }, c.phase === 'active' ? h('span', { id: 'call-time' }, durationText(Date.now() - (c.startedAt || Date.now()))) : 'Calling…')),
        h('div', { class: 'mini-bar' },
          h('button', { class: c.muted ? 'on' : '', title: c.muted ? 'Unmute' : 'Mute', onClick: () => { c.muted = !c.muted; renderCall() } }, icon(c.muted ? 'micOff' : 'mic', 18)),
          h('button', { title: 'Back to the call', onClick: () => minimizeCall(false) }, icon('expand', 18)),
          h('button', { class: 'red', title: 'Hang up', onClick: hangUp }, icon('hangup', 18)))].filter(Boolean))
      return
    }
    root.className = 'call'
    root.style.left = root.style.top = root.style.right = root.style.bottom = ''
    const status = c.phase === 'incoming' ? (c.videoCall ? 'Incoming video call' : 'Incoming call') : c.phase === 'outgoing' ? 'Calling…' : ''
    const showRemote = c.phase === 'active' && c.remoteVideo
    if (!c.remoteCanvas) c.remoteCanvas = h('canvas', { class: 'call-remote' })
    const kids = [h('div', { class: 'call-bg', style: { background: `radial-gradient(circle at 50% 30%, ${AVATARS[javaHash(c.peer) % AVATARS.length][0]}66, #0a0a0f 70%)` } })]
    if (showRemote) kids.push(c.remoteCanvas)
    kids.push(h('div', { class: 'call-top' + (showRemote ? ' over' : '') },
      showRemote ? null : h('div', { class: 'call-av' + (c.phase !== 'active' ? ' pulse' : '') }, avatar(name, c.peer, 132)),
      h('div', { class: 'call-name' }, name),
      h('div', { class: 'call-status' }, c.phase === 'active' ? h('span', { id: 'call-time' }, durationText(Date.now() - (c.startedAt || Date.now()))) : status)))
    if (c.localVideo && c.video) {
      c.localVideo.className = 'call-self'
      kids.push(c.localVideo)
    }
    let buttons
    if (c.phase === 'incoming') {
      buttons = [callButton('hangup', 'Decline', hangUp, 'red'), c.videoCall ? callButton('video', 'Video', () => accept(true), 'green') : null, callButton('phone', 'Accept', () => accept(false), 'green')]
    } else {
      buttons = [
        callButton(c.muted ? 'micOff' : 'mic', c.muted ? 'Unmute' : 'Mute', () => { c.muted = !c.muted; renderCall() }, c.muted ? 'on' : ''),
        callButton(c.video ? 'video' : 'videoOff', 'Camera', async () => { c.video = !c.video; if (c.phase === 'active') { if (c.video) await startCamera(c); else stopCamera(c) } renderCall() }, c.video ? 'on' : ''),
        c.video ? callButton('flip', 'Flip', async () => { c.front = !c.front; stopCamera(c, false); await startCamera(c) }) : null,
        callButton('chat', 'Chat', () => { const p = c.peer; minimizeCall(true); openChat(p) }),
        callButton('hangup', 'End', hangUp, 'red')
      ]
      // Minimise: keep talking while using the rest of the app.
      kids.push(h('button', { class: 'call-min-btn', title: 'Minimise', onClick: () => minimizeCall(true) }, icon('down', 26)))
    }
    kids.push(h('div', { class: 'call-buttons' }, buttons))
    root.replaceChildren(...kids)
  }

  // ---- Host events -------------------------------------------------------------------

  M.on('identity', (me) => {
    const first = !st.me
    st.me = me
    if (first && me) enterApp()
    else if (st.tab === 'me') renderSide()
  })
  M.on('account', () => { if (window.MeshronAccount) window.MeshronAccount.refresh().then(() => { if (st.tab === 'me') renderSide() }) })
  M.on('contacts', (list) => { st.contacts = list; renderRail(); renderSide(); refreshHeader() })
  M.on('groups', (list) => { st.groups = list; renderSide(); refreshHeader() })
  M.on('summaries', (list) => { st.summaries = list; renderRail(); if (st.tab === 'chats') renderSide() })
  M.on('prefs', (p) => { st.prefs = p; renderRail(); renderSide(); if (st.redrawSettings) st.redrawSettings(); if ($('#details')) renderDetailsSoon() })
  M.on('net', (n) => { st.net = n; renderRail(); if (st.tab !== 'me' || true) renderSideSoon(); refreshHeader() })
  M.on('file-status', ({ key, text }) => {
    if (text == null) delete st.fileStatus[key]
    else st.fileStatus[key] = text
    if (st.open && key.startsWith(st.open + '|')) renderMsgs()
  })
  M.on('msgs', ({ conv, rows }) => {
    if (conv !== st.open) return
    let added = 0
    let mine = false
    for (const r of rows) {
      const i = st.rows.findIndex((x) => x.id === r.id)
      if (i >= 0) st.rows[i] = r
      else { st.rows.push(r); added++; if (r.outgoing) mine = true; else if (chatEls && chatEls.msgs.scrollHeight - chatEls.msgs.scrollTop - chatEls.msgs.clientHeight > 240) st.pendingBelow++ }
    }
    if (added) st.rows.sort((a, b) => a.localTime - b.localTime)
    renderMsgs()
    if ($('#details') && rows.some((r) => r.kind === 'file')) renderDetailsSoon()
    if (mine) scrollBottom(false)
    updateToBottom()
    if (added) markRead()
  })
  M.on('msgs-removed', ({ conv, ids, all }) => {
    if (conv !== st.open) return
    st.rows = all ? [] : st.rows.filter((r) => !ids.includes(r.id))
    renderMsgs()
  })
  M.on('incoming', onIncoming)
  M.on('profiles', (p) => { st.profiles = p; renderSideSoon(); refreshHeader() })
  M.on('typing', ({ key, what }) => {
    st.typing[key] = Date.now() + 6000
    st.typingWhat = st.typingWhat || {}
    st.typingWhat[key] = what === 'recording' ? 'recording' : 'typing'
    if (st.open === key) { refreshHeader(); setTimeout(refreshHeader, 6100) }
  })
  M.on('stories', (v) => { st.stories = v; if (st.tab === 'chats') renderSideSoon(); refreshViewer() })
  M.on('request', ({ key }) => toast(`${nameOf(key)} wants to chat with you`))
  M.on('open-chat', ({ conv }) => openChat(conv))
  M.on('restart', restarting)
  M.on('open-link', ({ text }) => { if (st.me) showAddContact(text) })
  M.on('call-msg', onCallSignal)
  M.on('call-audio', ({ key, data }) => { if (call && call.phase === 'active' && call.peer === key) onAudioPacket(call, data) })
  M.on('call-video', ({ key, data }) => { if (call && call.phase === 'active' && call.peer === key && call.remoteVideo) onVideoFrame(call, data) })

  let sideTimer = null
  function renderSideSoon() {
    if (sideTimer) return
    sideTimer = setTimeout(() => { sideTimer = null; renderSide() }, 250)
  }

  // ---- Keyboard and focus ------------------------------------------------------------

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if ($('#menu')) return closeMenu()
      if ($('#emoji')) return $('#emoji').remove()
      if (openModals.length) return openModals[openModals.length - 1].close()
      if ($('.sos')) return $('.sos').remove()
      if (st.open) return closeChat()
    }
    const mod = e.ctrlKey || e.metaKey
    if (mod && e.key.toLowerCase() === 'k') { e.preventDefault(); if (st.tab === 'me') setTab('chats'); const s = $('#search'); if (s) s.focus() }
    if (mod && e.key.toLowerCase() === 'n') { e.preventDefault(); if (st.me) showNewChat() }
    if (mod && e.key.toLowerCase() === 'i' && st.open) { e.preventDefault(); toggleDetails() }
    // Alt+↑/↓: previous / next chat in the list.
    if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown') && st.me) {
      e.preventDefault()
      const list = chatRows()
      if (!list.length) return
      const i = list.findIndex((r) => r.conv === st.open)
      const next = e.key === 'ArrowDown' ? (i < 0 ? 0 : Math.min(list.length - 1, i + 1)) : Math.max(0, i - 1)
      if (st.tab !== 'chats') setTab('chats')
      openChat(list[next].conv)
    }
  })
  window.addEventListener('focus', markRead)
  document.addEventListener('visibilitychange', () => { if (!document.hidden) markRead() })
  window.addEventListener('beforeunload', saveDraft)
  window.addEventListener('online', () => M.call('net.changed').catch(() => {}))

  // ---- Start -------------------------------------------------------------------------

  // ---- Legal: Terms, Privacy Policy, Community Guidelines, and the one-time agreement ----
  function showLegal(d) {
    modal({ title: d.title, wide: true, body: h('div', { class: 'legal' },
      h('p', { class: 'dim t-small' }, 'Last updated ' + d.updated), h('p', {}, d.intro),
      ...d.sections.map(([t, b]) => h('div', {}, h('h3', { class: 't-title', style: { marginTop: '16px' } }, t), h('p', { class: 'dim' }, b)))) })
  }
  function legalGate() {
    const L = window.MeshronLegal
    return new Promise((resolve) => {
      const age = true
      let agree = false
      const go = h('button', { class: 'btn primary', disabled: true, onClick: async () => { await M.call('prefs.set', { key: 'legalVersion', value: L.version }).catch(() => {}); resolve() } }, 'Continue')
      const tick = (label, set) => {
        const box = h('input', { type: 'checkbox', onChange: () => { set(box.checked); go.disabled = !(age && agree) } })
        return h('label', { class: 'set-row', style: { cursor: 'pointer' } }, box, h('span', { class: 'grow' }, label))
      }
      ob.hidden = false
      ob.replaceChildren(h('div', { class: 'ob-card' },
        h('h1', { class: 't-h1' }, 'Welcome to Meshron'),
        h('p', { class: 'dim' }, 'Private chats, moments and videos, device to device. Before you start:'),
        tick("I agree to the Terms of Service and Community Guidelines, and I've read the Privacy Policy", (v) => { agree = v }),
        h('p', {}, ...['TERMS', 'PRIVACY', 'GUIDELINES'].map((k) => h('a', { href: '#', style: { marginRight: '14px' }, onClick: (e) => { e.preventDefault(); showLegal(L.docs[k]) } }, L.docs[k].title))),
        go))
    })
  }

  /** Asked once of people who started before birthdays were asked (18+ groups need it). */
  async function askBirthday() {
    const a = await M.call('age.get').catch(() => null)
    if (!a || a.birth || st.anon) return
    const num = (ph, max) => h('input', { class: 'input', inputmode: 'numeric', placeholder: ph, maxlength: max, style: { flex: max === 4 ? 1.4 : 1, minWidth: 0, textAlign: 'center' } })
    const d = num('Day', 2); const mo = num('Month', 2); const y = num('Year', 4)
    const err = h('div', { class: 'hint bad' })
    const go = h('button', { class: 'btn primary wide', disabled: true, style: { marginTop: '24px' } }, 'Continue')
    const value = () => (y.value.length === 4 && mo.value && d.value) ? y.value + '-' + mo.value.padStart(2, '0') + '-' + d.value.padStart(2, '0') : ''
    for (const x of [d, mo, y]) x.addEventListener('input', () => { x.value = x.value.replace(/\D/g, ''); err.textContent = ''; go.disabled = !value() })
    const page = h('div', { class: 'birthday-page', style: { position: 'fixed', inset: 0, zIndex: 400, background: 'var(--bg, #0b0d14)', display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '24px' } },
      h('div', { style: { maxWidth: '420px', width: '100%', margin: '0 auto' } },
        h('h1', { class: 't-h1' }, "When's your birthday?"),
        h('div', { style: { display: 'flex', gap: '10px', marginTop: '24px' } }, d, mo, y), err, go))
    go.onclick = async () => {
      const v = value(); const dt = new Date(v)
      if (!v || isNaN(dt) || dt.getUTCDate() !== Number(d.value)) { err.textContent = 'Check the date'; return }
      try { await M.call('age.set', { birth: v }); page.remove() } catch (e) { err.textContent = e.message }
    }
    document.body.appendChild(page)
    setTimeout(() => d.focus(), 50)
  }

  function enterApp() {
    askBirthday()
    ob.hidden = true
    $('#shell').hidden = false
    showHero()
    try { buildSide() } catch (e) { console.warn('side', e) }
    refreshNoticeCount().then(() => { if (noticeCount && st.tab === 'me') renderSide() })
  }

  // Shared with reels.js.
  window.MeshronUI = { h, $, icon, avatar, modal, toast, oops, confirmBox, toBase64, showAddContact, setTab, M, st, menu, pickProfilePhoto, watchLive, goLive, closeChat, manualRefresh }

  async function start() {
    try {
      const init = await M.call('init')
      st.anon = !!init.anon
      st.locked = !!init.locked
      document.documentElement.classList.toggle('anon', st.anon)
      if (M.native.privacy) M.native.privacy(st.anon)
      if (st.locked) { $('#boot').classList.add('gone'); await unlock() }
      // Anonymous makes its own identity: a random nickname, nothing about you.
      if (st.anon && !init.identity) {
        await M.call('identity.create', { name: 'Ghost ' + (1000 + Math.floor(Math.random() * 9000)) })
        init.identity = (await M.call('init')).identity
      }
      Object.assign(st, { stories: init.stories || { mine: [], friends: [] }, me: init.identity, contacts: init.contacts, groups: init.groups, summaries: init.summaries, net: init.net, prefs: init.prefs, fileStatus: init.fileStatus || {}, limits: init.limits, profiles: init.profiles || { me: {}, peers: {} } })
      if (window.MeshronLegal && ((st.prefs.settings || {}).legalVersion || 0) < window.MeshronLegal.version) {
        $('#boot').classList.add('gone')
        await legalGate()
        st.prefs.settings.legalVersion = window.MeshronLegal.version
      }
      if (st.me) enterApp()
      else { ob.hidden = false; showWelcome() }
    } catch (err) {
      ob.hidden = false
      ob.replaceChildren(h('div', { class: 'ob-card' }, h('h1', { class: 't-h1' }, "Meshron couldn't start"), h('p', { class: 'dim selectable', style: { marginTop: '8px' } }, (err && err.message) || String(err))))
    }
    $('#boot').classList.add('gone')
    setTimeout(() => $('#boot').remove(), 400)
  }
  start()
})()
