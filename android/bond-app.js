// GEOREDE — esquecer o Geo no Bluetooth do telemóvel depois de o libertar (24/11/2026).
// Corre na pasta do projeto ($HOME\georede-android), ANTES do preparar.js:
//   node bond-app.js   →   subir VERSAO_CODIGO no preparar.js   →   node preparar.js   →   npx cap sync android   →   ▶
// Faz duas coisas (pode correr-se mais de uma vez):
//  1. põe o GeoredeBondPlugin.java em nativo\ (o preparar.js copia-o para o projeto Android);
//  2. no nativo\MainActivity.java: registerPlugin(GeoredeBondPlugin.class).
const fs = require("fs");
const path = require("path");

const nativo = path.join(__dirname, "nativo");
if (!fs.existsSync(nativo)) { console.error("FALHOU: falta a pasta nativo. Corre isto na pasta georede-android."); process.exit(1); }
fs.copyFileSync(path.join(__dirname, "bond-app", "GeoredeBondPlugin.java"), path.join(nativo, "GeoredeBondPlugin.java"));
console.log("OK: nativo\\GeoredeBondPlugin.java");

const MA = path.join(nativo, "MainActivity.java");
if (!fs.existsSync(MA)) { console.error("FALHOU: falta nativo\\MainActivity.java"); process.exit(1); }
let j = fs.readFileSync(MA, "utf8");
if (j.includes("registerPlugin(GeoredeBondPlugin.class)")) {
  console.log("OK: a MainActivity já registava o GeoredeBondPlugin.");
} else {
  const reVoz = /^([ \t]*)registerPlugin\(GeoredeVozPlugin\.class\);[^\n]*\n/m;
  const re2 = /^([ \t]*)super\.onCreate\(savedInstanceState\);/m;
  if (reVoz.test(j)) j = j.replace(reVoz, (m, ind) => `${m}${ind}registerPlugin(GeoredeBondPlugin.class);\n`);
  else if (re2.test(j)) j = j.replace(re2, (m, ind) => `${ind}registerPlugin(GeoredeBondPlugin.class);\n${m}`);
  if (!j.includes("registerPlugin(GeoredeBondPlugin.class)")) {
    console.error("FALHOU: não encontrei onde registar o plugin na MainActivity. Manda-me o nativo\\MainActivity.java.");
    process.exit(1);
  }
  fs.writeFileSync(MA, j);
  console.log("OK: nativo\\MainActivity.java (GeoredeBondPlugin registado)");
}
console.log("Seguinte: subir VERSAO_CODIGO no preparar.js, depois  node preparar.js  e  npx cap sync android  e ▶ no Android Studio");
