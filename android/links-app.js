// GEOREDE — links https://app.georede.com abrem na app (Android App Links), 09/11/2026.
// Corre na pasta do projeto ($HOME\georede-android), ANTES do preparar.js:
//   node links-app.js   →   node preparar.js   →   npx cap sync android
// Faz três coisas (pode correr-se mais de uma vez):
//  1. põe o GeoredeLinks.java em nativo\ (o preparar.js copia-o para o projeto Android);
//  2. no nativo\MainActivity.java: um link nosso é tratado como o toque numa notificação (abre essa secção);
//  3. no AndroidManifest.xml: o intent-filter com autoVerify para app.georede.com (só as secções da app).
// Não mexe em mais nada da MainActivity nem do preparar.js.
const fs = require("fs");
const path = require("path");

// Secções que os links podem abrir. Manter igual a GeoredeLinks.SECCOES.
const SECCOES = ["/mapa", "/mensagens", "/zonas", "/alertas", "/mais"];

let erros = 0;
const falha = m => { console.error("FALHOU: " + m); erros++; };

// 1. o GeoredeLinks.java
const nativo = path.join(__dirname, "nativo");
if (!fs.existsSync(nativo)) { console.error("FALHOU: falta a pasta nativo. Corre isto na pasta georede-android."); process.exit(1); }
fs.copyFileSync(path.join(__dirname, "links-app", "GeoredeLinks.java"), path.join(nativo, "GeoredeLinks.java"));
console.log("OK: nativo\\GeoredeLinks.java");

// 2. a MainActivity
const MA = path.join(nativo, "MainActivity.java");
if (!fs.existsSync(MA)) falha("falta nativo\\MainActivity.java");
else {
  let j = fs.readFileSync(MA, "utf8");
  const antes = j;
  if (!j.includes("GeoredeLinks.caminho(")) {
    const re = /(static\s+String\s+urlDaNotificacao\s*\(\s*Intent\s+i\s*\)\s*\{\s*\n(\s*)if \(i == null\) return null;)/;
    if (re.test(j)) {
      j = j.replace(re, (m, bloco, ind) =>
        `${bloco}\n${ind}String link = GeoredeLinks.caminho(i);              // link https://app.georede.com/... (email)\n${ind}if (link != null) return link;`);
    } else {
      falha("não encontrei o urlDaNotificacao na MainActivity. Manda-me o nativo\\MainActivity.java.");
    }
  }
  // Abrir sempre em app.georede.com (se a página estiver noutro sítio, por exemplo no login, não ir para lá)
  const origemAntiga = 'String origem = atual.replaceFirst("^(https://[^/]+).*$", "$1");';
  if (j.includes(origemAntiga)) j = j.replace(origemAntiga, 'String origem = "https://app.georede.com";   // links e notificações abrem sempre a app');
  if (j !== antes) { fs.writeFileSync(MA, j); console.log("OK: nativo\\MainActivity.java (links abrem a secção certa)"); }
  else if (j.includes("GeoredeLinks.caminho(")) console.log("OK: a MainActivity já tratava os links.");
}

// 3. o AndroidManifest.xml
const F = path.join(__dirname, "android", "app", "src", "main", "AndroidManifest.xml");
if (!fs.existsSync(F)) falha("falta a pasta android. Corre primeiro: npx cap add android");
else {
  let x = fs.readFileSync(F, "utf8");
  if (x.includes('android:host="app.georede.com"')) {
    console.log("OK: o AndroidManifest.xml já tinha os links de app.georede.com.");
  } else {
    const ini = x.search(/<activity\b[^>]*MainActivity/);
    const fim = ini < 0 ? -1 : x.indexOf("</activity>", ini);
    if (fim < 0) falha("não encontrei a MainActivity no AndroidManifest.xml");
    else {
      const linha = x.lastIndexOf("\n", fim) + 1;
      const ind = x.slice(linha, fim).match(/^[ \t]*/)[0];
      const i2 = ind + "    ", i3 = i2 + "    ";
      const filtro = [
        `${i2}<!-- GEOREDE: links de app.georede.com (emails) abrem na app -->`,
        `${i2}<intent-filter android:autoVerify="true">`,
        `${i3}<action android:name="android.intent.action.VIEW" />`,
        `${i3}<category android:name="android.intent.category.DEFAULT" />`,
        `${i3}<category android:name="android.intent.category.BROWSABLE" />`,
        `${i3}<data android:scheme="https" />`,
        `${i3}<data android:host="app.georede.com" />`,
        `${i3}<data android:path="/" />`,
        ...SECCOES.map(s => `${i3}<data android:pathPrefix="${s}" />`),
        `${i2}</intent-filter>`
      ].join("\n");
      x = x.slice(0, linha) + filtro + "\n" + x.slice(linha);
      fs.writeFileSync(F, x);
      console.log("OK: AndroidManifest.xml (intent-filter de app.georede.com, autoVerify)");
    }
  }
}

if (erros) process.exit(1);
console.log("Seguinte: subir VERSAO_CODIGO no preparar.js, depois  node preparar.js  e  npx cap sync android");
