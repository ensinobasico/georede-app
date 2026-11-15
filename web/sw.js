// GEOREDE — service worker
// 1) Notificações no telemóvel (Web Push).
// 2) App guardada no telemóvel, para abrir sem internet (Kit Emergência, caminhadas sem rede).
//
// O que fica guardado: só o "esqueleto" da app (index.html), os ícones, o manifesto e a biblioteca do mapa.
// Nunca guarda dados: a API (/api/*) e o login (/auth/*) vão sempre à rede.
// Com internet, a app vem sempre fresca do servidor (passando pela verificação de sessão do Caddy);
// a cópia guardada só é usada quando a rede falha.
"use strict";

const VERSAO = "v4";
const CAIXA = "georede-app-" + VERSAO;
const ESQUELETO = "/";                                  // a mesma página serve /mapa, /zonas, /alertas, ...
const FICHEIROS = [
  "/manifest.webmanifest",
  "/icones/icone-192.png",
  "/icones/icone-512.png",
  "/icones/icone-180.png",
  "/icones/icone-notificacao-96.png",
  "/vendor/maplibre-gl.js",
  "/vendor/maplibre-gl.css",
  "/vendor/basemaps.js",
  "https://unpkg.com/maplibre-gl@5/dist/maplibre-gl.js",            // recurso, se o servidor ainda não tiver /vendor
  "https://unpkg.com/maplibre-gl@5/dist/maplibre-gl.css"
];
const CAIXA_MAPA = "georede-mapa";                     // a zona guardada pela pessoa (Mais → Mapa sem internet)
const ESPERA_REDE_MS = 6000;                            // rede muito lenta: ao fim disto usa a cópia guardada

// Uma resposta só se guarda se for mesmo o ficheiro (e não a página de login, depois de a sessão acabar)
function guardavel(r) {
  return !!r && r.ok && !r.redirected && r.type === "basic" && !(r.headers.get("Content-Type") || "").includes("text/html");
}
// Guarda o esqueleto da app só se veio mesmo a app (sessão válida), e não a página de login
async function guardarEsqueleto(cache, resposta) {
  if (!resposta || !resposta.ok || resposta.redirected || resposta.type !== "basic") return;
  if (!(resposta.headers.get("Content-Type") || "").includes("text/html")) return;
  await cache.put(ESQUELETO, resposta);
}

self.addEventListener("install", e => {
  e.waitUntil((async () => {
    const cache = await caches.open(CAIXA);
    // Um a um: se um falhar (ex.: unpkg em baixo), os outros ficam guardados na mesma
    await Promise.all(FICHEIROS.map(async u => {
      try {
        const r = await fetch(u, u.startsWith("/") ? { credentials: "same-origin" } : {});
        if (guardavel(r) || (u.startsWith("http") && r.ok)) await cache.put(u, r);
      } catch (err) { /* fica para quando a app abrir com rede */ }
    }));
    try { await guardarEsqueleto(cache, await fetch(ESQUELETO, { credentials: "same-origin", cache: "no-store" })); }
    catch (err) { /* sem rede ou sem sessão: fica para a próxima abertura com internet */ }
    await self.skipWaiting();
  })());
});

// A app pede para ser guardada sempre que abre com sessão válida (assim fica guardada mesmo
// que o telemóvel tenha entrado por um caminho que o service worker não viu, ex. depois do login)
async function guardarTudo() {
  const cache = await caches.open(CAIXA);
  for (const u of FICHEIROS) {
    if (await cache.match(u, { ignoreVary: true })) continue;
    try {
      const r = await fetch(u, u.startsWith("/") ? { credentials: "same-origin" } : {});
      if (guardavel(r) || (u.startsWith("http") && r.ok)) await cache.put(u, r);
    } catch (err) { /* sem rede */ }
  }
  try { await guardarEsqueleto(cache, await fetch(ESQUELETO, { credentials: "same-origin", cache: "no-store" })); }
  catch (err) { /* sem rede: fica a cópia anterior */ }
}
self.addEventListener("message", e => {
  if (e.data && e.data.tipo === "guardar-app") e.waitUntil(guardarTudo());
});

self.addEventListener("activate", e => {
  e.waitUntil((async () => {
    for (const nome of await caches.keys()) {
      if (nome.startsWith("georede-app-") && nome !== CAIXA) await caches.delete(nome);
    }
    await self.clients.claim();
  })());
});

function comTempo(promessa, ms) {
  return new Promise((ok, falha) => {
    const t = setTimeout(() => falha(new Error("rede lenta")), ms);
    promessa.then(r => { clearTimeout(t); ok(r); }, e => { clearTimeout(t); falha(e); });
  });
}

const PAGINA_SEM_REDE = `<!doctype html><html lang="pt"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>GEOREDE</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0f1720;color:#e8eef5;
font-family:system-ui,sans-serif;text-align:center;padding:24px}p{max-width:30em;line-height:1.5;color:#b8c4d0}
button{margin-top:12px;padding:10px 18px;border:0;border-radius:10px;background:#1E90FF;color:#fff;font-size:1rem}</style>
</head><body><main><h1>Sem internet</h1>
<p>A app GEOREDE ainda não ficou guardada neste telemóvel. Abre-a uma vez com internet e, a partir daí, abre também sem rede.</p>
<button onclick="location.reload()">Tentar outra vez</button></main></body></html>`;

self.addEventListener("fetch", e => {
  const pedido = e.request;
  const url = new URL(pedido.url);

  // Sair da conta NÃO apaga a cópia da app: só tem o esqueleto, sem dados de ninguém, e o Geo tem de
  // continuar a funcionar sem internet mesmo depois de alguém sair e voltar a entrar.
  // Mas apaga a zona do mapa guardada: mostra onde fica a casa.
  if (pedido.method === "POST" && url.origin === self.location.origin && url.pathname === "/auth/logout") {
    e.waitUntil(caches.delete(CAIXA_MAPA));
    return;
  }
  if (pedido.method !== "GET") return;

  // Mosaicos do mapa: os da zona guardada vêm do telemóvel (sem rede e mais depressa); os outros, da rede
  if (url.origin === self.location.origin && url.pathname.startsWith("/mapa/tiles/")) {
    e.respondWith((async () => {
      const c = await caches.open(CAIXA_MAPA).catch(() => null);
      let guardado = c ? await c.match(url.pathname) : null;
      // mapas guardados antes de 04/11/2026 (só Portugal) têm os endereços antigos: …/tiles/portugal/…
      if (!guardado && c && url.pathname.startsWith("/mapa/tiles/georede/"))
        guardado = await c.match(url.pathname.replace("/mapa/tiles/georede/", "/mapa/tiles/portugal/"));
      return guardado || fetch(pedido);
    })());
    return;
  }

  // Páginas da app: primeiro a rede (com verificação de sessão); sem rede, a cópia guardada
  if (pedido.mode === "navigate" && url.origin === self.location.origin
      && !url.pathname.startsWith("/api/") && !url.pathname.startsWith("/auth/")) {
    e.respondWith((async () => {
      const cache = await caches.open(CAIXA);
      try {
        const r = await comTempo(fetch(pedido), ESPERA_REDE_MS);
        // r.type "opaqueredirect" = sem sessão, o Caddy manda para o login: entregar tal e qual
        if (r.type === "basic") e.waitUntil(guardarEsqueleto(cache, r.clone()));
        return r;
      } catch (err) {
        const guardada = await cache.match(ESQUELETO);
        return guardada || new Response(PAGINA_SEM_REDE, { headers: { "Content-Type": "text/html; charset=utf-8" } });
      }
    })());
    return;
  }

  // Ícones, manifesto, bibliotecas, letras e ícones do mapa: a cópia guardada primeiro, e atualiza em segundo plano
  // (letras e ícones: primeiro os da zona guardada, que vão com ela)
  const proprio = url.origin === self.location.origin;
  const ficheiro = (proprio && (url.pathname.startsWith("/icones/") || url.pathname === "/manifest.webmanifest"
                               || url.pathname.startsWith("/vendor/") || url.pathname.startsWith("/mapa/fontes/")
                               || url.pathname.startsWith("/mapa/sprites/")))
    || (url.hostname === "unpkg.com" && url.pathname.startsWith("/maplibre-gl@"));
  if (ficheiro) {
    e.respondWith((async () => {
      const cache = await caches.open(CAIXA);
      const guardada = (await cache.match(pedido, { ignoreVary: true }))
        || (proprio && url.pathname.startsWith("/mapa/") ? await caches.open(CAIXA_MAPA).then(c => c.match(url.pathname)).catch(() => null) : null);
      const daRede = fetch(pedido).then(r => {
        if (guardavel(r) || (!proprio && r && (r.ok || r.type === "opaque"))) cache.put(pedido, r.clone()).catch(() => null);
        return r;
      });
      if (guardada) { e.waitUntil(daRede.catch(() => null)); return guardada; }
      return daRede;
    })());
  }
  // Tudo o resto (API, login, mosaicos do mapa) segue para a rede como antes
});

// Chega um aviso (cifrado pelo nosso servidor; o browser já o decifrou)
self.addEventListener("push", e => {
  let m = {};
  try { m = e.data ? e.data.json() : {}; } catch (err) { m = { corpo: e.data ? e.data.text() : "" }; }
  e.waitUntil(self.registration.showNotification(m.titulo || "GEOREDE", {
    body: m.corpo || "",
    icon: "/icones/icone-192.png",
    badge: "/icones/icone-notificacao-96.png",
    tag: m.tag || undefined,
    renotify: !!m.tag,
    data: { url: m.url || "/alertas" }
  }));
});

// Toque na notificação: abre a app nessa secção (ou usa a janela que já estiver aberta)
self.addEventListener("notificationclick", e => {
  e.notification.close();
  const destino = new URL((e.notification.data && e.notification.data.url) || "/alertas", self.location.origin).href;
  e.waitUntil((async () => {
    const janelas = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const j of janelas) {
      if (new URL(j.url).origin !== self.location.origin) continue;
      await j.focus();
      try { await j.navigate(destino); } catch (err) { /* janela não controlada: fica só em primeiro plano */ }
      return;
    }
    await self.clients.openWindow(destino);
  })());
});
