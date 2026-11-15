// GEOREDE — voz nas Mensagens (ler em voz e ditar), 13/11/2026.
// Corre na pasta do projeto ($HOME\georede-android), ANTES do preparar.js:
//   node voz-app.js   →   node preparar.js   →   npx cap sync android
// Faz três coisas (pode correr-se mais de uma vez):
//  1. põe o GeoredeVozPlugin.java em nativo\ (o preparar.js copia-o para o projeto Android);
//  2. no nativo\MainActivity.java: registerPlugin(GeoredeVozPlugin.class);
//  3. no AndroidManifest.xml: a autorização do microfone e os <queries> do leitor de voz e do reconhecimento
//     (sem eles, no Android 11+ a app não "vê" esses serviços);
//  4. (13/11/2026) no android/app/build.gradle: o ML Kit de identificação da língua (corre no telemóvel, sem
//     Internet; ~1-2 MB a mais na app), para ler cada mensagem com a voz da língua dela.
const fs = require("fs");
const path = require("path");

let erros = 0;
const falha = m => { console.error("FALHOU: " + m); erros++; };

// 1. o plugin
const nativo = path.join(__dirname, "nativo");
if (!fs.existsSync(nativo)) { console.error("FALHOU: falta a pasta nativo. Corre isto na pasta georede-android."); process.exit(1); }
fs.copyFileSync(path.join(__dirname, "voz-app", "GeoredeVozPlugin.java"), path.join(nativo, "GeoredeVozPlugin.java"));
console.log("OK: nativo\\GeoredeVozPlugin.java");

// 2. a MainActivity
const MA = path.join(nativo, "MainActivity.java");
if (!fs.existsSync(MA)) falha("falta nativo\\MainActivity.java");
else {
  let j = fs.readFileSync(MA, "utf8");
  if (j.includes("registerPlugin(GeoredeVozPlugin.class)")) {
    console.log("OK: a MainActivity já registava o GeoredeVozPlugin.");
  } else {
    const re = /^([ \t]*)registerPlugin\(GeoredeLoginPlugin\.class\);[^\n]*\n/m;
    const re2 = /^([ \t]*)super\.onCreate\(savedInstanceState\);/m;
    if (re.test(j)) j = j.replace(re, (m, ind) => `${m}${ind}registerPlugin(GeoredeVozPlugin.class);\n`);
    else if (re2.test(j)) j = j.replace(re2, (m, ind) => `${ind}registerPlugin(GeoredeVozPlugin.class);\n${m}`);
    else falha("não encontrei onde registar o plugin na MainActivity. Manda-me o nativo\\MainActivity.java.");
    if (j.includes("registerPlugin(GeoredeVozPlugin.class)")) {
      fs.writeFileSync(MA, j);
      console.log("OK: nativo\\MainActivity.java (GeoredeVozPlugin registado)");
    }
  }
}

// 3. o AndroidManifest.xml
const F = path.join(__dirname, "android", "app", "src", "main", "AndroidManifest.xml");
if (!fs.existsSync(F)) falha("falta a pasta android. Corre primeiro: npx cap add android");
else {
  let x = fs.readFileSync(F, "utf8");
  const antes = x;
  const ind = (x.match(/\n([ \t]+)<application\b/) || [, "    "])[1];
  const i2 = ind + "    ", i3 = i2 + "    ";

  if (!x.includes("android.permission.RECORD_AUDIO")) {
    x = x.replace(/(\n[ \t]*)<application\b/, `\n${ind}<!-- GEOREDE: ditar mensagens (a voz passa a texto no telemóvel) -->\n${ind}<uses-permission android:name="android.permission.RECORD_AUDIO" />$1<application`);
  }
  const intents = [
    ["android.speech.RecognitionService", `${i2}<intent>\n${i3}<action android:name="android.speech.RecognitionService" />\n${i2}</intent>`],
    ["android.intent.action.TTS_SERVICE", `${i2}<intent>\n${i3}<action android:name="android.intent.action.TTS_SERVICE" />\n${i2}</intent>`]
  ];
  const faltam = intents.filter(([a]) => !x.includes(`"${a}"`)).map(([, b]) => b);
  if (faltam.length) {
    if (/<queries>/.test(x)) {
      x = x.replace(/<queries>/, `<queries>\n${faltam.join("\n")}`);
    } else {
      x = x.replace(/(\n[ \t]*)<application\b/, `\n${ind}<!-- GEOREDE: leitor de voz e reconhecimento de voz (Android 11+) -->\n${ind}<queries>\n${faltam.join("\n")}\n${ind}</queries>$1<application`);
    }
  }
  if (!x.includes("android.permission.RECORD_AUDIO") || !x.includes('"android.speech.RecognitionService"')) {
    falha("não consegui mexer no AndroidManifest.xml (não encontrei <application>)");
  } else if (x !== antes) {
    fs.writeFileSync(F, x);
    console.log("OK: AndroidManifest.xml (microfone + <queries> da voz)");
  } else {
    console.log("OK: o AndroidManifest.xml já tinha o microfone e os <queries> da voz.");
  }
}

// 4. o ML Kit (identificação da língua) no android/app/build.gradle
const G = path.join(__dirname, "android", "app", "build.gradle");
const MLKIT = "implementation 'com.google.mlkit:language-id:17.0.6'";
if (!fs.existsSync(G)) falha("falta o android/app/build.gradle. Corre primeiro: npx cap add android");
else {
  let g = fs.readFileSync(G, "utf8");
  if (g.includes("com.google.mlkit:language-id")) console.log("OK: o build.gradle já tinha o ML Kit (língua das mensagens).");
  else if (/dependencies \{\r?\n/.test(g)) {
    g = g.replace(/dependencies \{\r?\n/, m => `${m}    ${MLKIT}   // GEOREDE: língua de cada mensagem (no telemóvel)\n`);
    fs.writeFileSync(G, g);
    console.log("OK: android/app/build.gradle (ML Kit: língua de cada mensagem)");
  } else falha("não encontrei «dependencies {» no android/app/build.gradle");
}

if (erros) process.exit(1);
console.log("Seguinte: subir VERSAO_CODIGO no preparar.js, depois  node preparar.js  e  npx cap sync android");
