// GEOREDE — atualizar o programa do Geo por Bluetooth, dentro da app (18/11/2026).
// Corre na pasta do projeto ($HOME\georede-android), ANTES do preparar.js:
//   node dfu-app.js   →   node preparar.js   →   npx cap sync android
// Faz cinco coisas (pode correr-se mais de uma vez):
//  1. põe GeoredeDfuNucleo.java, GeoredeDfuPlugin.java e GeoredeDfuServico.java em nativo\ (o preparar.js copia-os);
//  2. no nativo\MainActivity.java: regista o plugin GeoredeDfu;
//  3. no AndroidManifest.xml: declara o serviço GeoredeDfuServico (tipo connectedDevice, como o GeoredeServico);
//  4. no android\app\build.gradle: a biblioteca de atualização da Nordic (no.nordicsemi.android:dfu);
//  5. no android\app\proguard-rules.pro (se existir): não mexer nas classes dessa biblioteca.
// Não mexe em mais nada.
const fs = require("fs");
const path = require("path");

const BIBLIOTECA = "no.nordicsemi.android:dfu";
const VERSAO_BIBLIOTECA = "2.11.0";
const JAVAS = ["GeoredeDfuNucleo.java", "GeoredeDfuPlugin.java", "GeoredeDfuServico.java"];

let erros = 0;
const falha = m => { console.error("FALHOU: " + m); erros++; };

// 1. o código Java
const nativo = path.join(__dirname, "nativo");
if (!fs.existsSync(nativo)) { console.error("FALHOU: falta a pasta nativo. Corre isto na pasta georede-android."); process.exit(1); }
for (const f of JAVAS) {
  const origem = path.join(__dirname, "dfu-app", f);
  if (!fs.existsSync(origem)) { falha("falta dfu-app\\" + f + " (extrai o zip inteiro para a pasta do projeto)"); continue; }
  fs.copyFileSync(origem, path.join(nativo, f));
  console.log("OK: nativo\\" + f);
}

// 2. a MainActivity
const MA = path.join(nativo, "MainActivity.java");
if (!fs.existsSync(MA)) falha("falta nativo\\MainActivity.java");
else {
  let j = fs.readFileSync(MA, "utf8");
  if (j.includes("GeoredeDfuPlugin.class")) console.log("OK: a MainActivity já registava o GeoredeDfu.");
  else {
    const re = /^([ \t]*)super\.onCreate\(savedInstanceState\);/m;
    if (!re.test(j)) falha("não encontrei o super.onCreate na MainActivity. Manda-me o nativo\\MainActivity.java.");
    else {
      j = j.replace(re, (m, ind) => `${ind}registerPlugin(GeoredeDfuPlugin.class);        // 18/11/2026: atualizar o programa do Geo\n${m}`);
      fs.writeFileSync(MA, j);
      console.log("OK: nativo\\MainActivity.java (plugin GeoredeDfu registado)");
    }
  }
}

// 3. o AndroidManifest.xml
const F = path.join(__dirname, "android", "app", "src", "main", "AndroidManifest.xml");
if (!fs.existsSync(F)) falha("falta a pasta android. Corre primeiro: npx cap add android");
else {
  let x = fs.readFileSync(F, "utf8");
  if (x.includes(".GeoredeDfuServico")) console.log("OK: o AndroidManifest.xml já tinha o serviço GeoredeDfuServico.");
  else if (!/\n[ \t]*<\/application>/.test(x)) falha("não encontrei o </application> no AndroidManifest.xml");
  else {
    const servico = '<service android:name=".GeoredeDfuServico" android:exported="false" android:foregroundServiceType="connectedDevice" />';
    x = x.replace(/\n([ \t]*)<\/application>/, (m, ind) => `\n${ind}    ${servico}\n${ind}</application>`);
    fs.writeFileSync(F, x);
    console.log("OK: serviço GeoredeDfuServico declarado no AndroidManifest.xml");
  }
  for (const p of ["BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "FOREGROUND_SERVICE_CONNECTED_DEVICE"]) {
    if (!x.includes("android.permission." + p)) falha(`falta a permissão ${p} no AndroidManifest.xml (corre o preparar.js e depois este outra vez)`);
  }
}

// 4. a biblioteca no Gradle
const app = path.join(__dirname, "android", "app", "build.gradle");
if (!fs.existsSync(app)) falha("falta android\\app\\build.gradle");
else {
  let a = fs.readFileSync(app, "utf8");
  if (a.includes(BIBLIOTECA)) console.log("OK: a biblioteca de atualização já estava no android\\app\\build.gradle.");
  else if (!/dependencies \{\r?\n/.test(a)) falha("não encontrei o bloco dependencies no android\\app\\build.gradle");
  else {
    a = a.replace(/dependencies \{(\r?\n)/, (m, nl) => `dependencies {${nl}    implementation '${BIBLIOTECA}:${VERSAO_BIBLIOTECA}'   // GEOREDE: atualizar o programa do Geo por Bluetooth${nl}`);
    fs.writeFileSync(app, a);
    console.log(`OK: ${BIBLIOTECA}:${VERSAO_BIBLIOTECA} no android\\app\\build.gradle`);
  }
}

// 5. ProGuard/R8 (só conta se a app for compilada com minify)
const pg = path.join(__dirname, "android", "app", "proguard-rules.pro");
if (fs.existsSync(pg)) {
  let p = fs.readFileSync(pg, "utf8");
  if (!p.includes("no.nordicsemi.android.dfu")) {
    p += "\n# GEOREDE: biblioteca de atualização do Geo (Nordic DFU)\n-keep class no.nordicsemi.android.dfu.** { *; }\n";
    fs.writeFileSync(pg, p);
    console.log("OK: regra do ProGuard para a biblioteca de atualização");
  }
}

if (erros) process.exit(1);
console.log("Seguinte: node preparar.js  e  npx cap sync android ; depois, no Android Studio, ▶ (Run) com o telemóvel ligado por cabo.");
