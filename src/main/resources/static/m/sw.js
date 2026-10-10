/*
 * iWarehouse mobile POS service worker (MPOS-01, NFR-03): keeps the app shell so the POS opens without network.
 * The shell comes from the network when there is one (a release reaches the phones at once) and from the cache when
 * there is none or it is too slow to answer (3 s); every good answer refreshes the cache. The API (/api/...) always goes
 * to the network: the app keeps its data in IndexedDB and queues its sales there when offline (SYNC-02).
 */
const VERSION = 'iw-pos-v2';
const SHELL = ['/m/', '/m/index.html', '/m/app.css', '/m/app.js', '/m/manifest.webmanifest',
    '/m/icon-192.png', '/m/icon-512.png', '/m/icon-maskable-512.png'];
const NETWORK_WAIT = 3000;

self.addEventListener('install', (event) => {
    event.waitUntil(caches.open(VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
    event.waitUntil(caches.keys()
        .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
        .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET' || url.origin !== self.location.origin || !url.pathname.startsWith('/m/')) {
        return;                                         // the API and everything else: straight to the network
    }
    const key = url.pathname === '/m/' ? '/m/index.html' : url.pathname;
    event.respondWith(caches.open(VERSION).then(async (cache) => {
        const network = fetch(event.request).then((response) => {
            if (response.ok) cache.put(key, response.clone());
            return response;
        });
        network.catch(() => null);                      // answered from the cache: a late failure is no error
        const slow = new Promise((resolve) => setTimeout(resolve, NETWORK_WAIT, null));
        try {
            const first = await Promise.race([network, slow]);
            if (first) return first;
        } catch (e) {
            // offline: the cached shell
        }
        return (await cache.match(key)) || network;
    }));
});
