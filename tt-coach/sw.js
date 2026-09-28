// 場邊教練 service worker: app shell + the MediaPipe runtime and model, so the app starts offline after the first visit.
const VERSION = 'tt-v0.1.0';
const SHELL = ['./', './index.html', './app.js', './engine.js', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== VERSION && k !== 'tt-runtime').map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  const heavy = u.hostname === 'cdn.jsdelivr.net' || u.hostname === 'storage.googleapis.com';
  if (heavy) { // cache-first: versioned URLs, large files
    e.respondWith(caches.open('tt-runtime').then(async c => {
      const hit = await c.match(e.request); if (hit) return hit;
      const r = await fetch(e.request); if (r.ok) c.put(e.request, r.clone()); return r;
    }));
  } else if (u.origin === location.origin) { // network-first so updates show up, cache as fallback
    e.respondWith(fetch(e.request).then(r => { const cp = r.clone(); caches.open(VERSION).then(c => c.put(e.request, cp)); return r; }).catch(() => caches.match(e.request)));
  }
});
