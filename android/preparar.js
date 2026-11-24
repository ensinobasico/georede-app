// GEOREDE — depois de `npx cap add android` (e sempre que este projeto mudar):
//  1. permissões de Bluetooth no AndroidManifest.xml (as do README do @capacitor-community/bluetooth-le);
//  2. serviço em primeiro plano para a ligação ao Geo com o ecrã bloqueado (nativo/*.java + permissões + <service>);
//  3. (06/11/2026) localização do telemóvel em todas as versões do Android, para a "Seta até ao Geo" (antes só até ao
//     Android 11, para o Bluetooth antigo). O WebView do Capacitor pede-a ao abrir a seta pela primeira vez.
//  4. (06/11/2026) notificações pelo Firebase (FCM): o google-services.json (da consola do Firebase, posto na pasta
//     deste projeto), a biblioteca firebase-messaging no build.gradle, o serviço GeoredePushServico no manifesto e
//     o ícone branco da barra de notificações (nativo/ic_georede_notificacao.png).
//  5. (06/11/2026) «Vamos encontrar-nos» com o ecrã bloqueado: permissão FOREGROUND_SERVICE_LOCATION e o serviço
//     GeoredeEncontroServico (tipo location) no manifesto.
//  6. (07/11/2026) Google Play:
//     - versão da app (VERSAO_NOME / VERSAO_CODIGO, aqui em baixo: subir o código a cada envio para a Play);
//     - compileSdk e targetSdk 36 (Android 16), obrigatório para apps novas desde 31/08/2026;
//     - sai a permissão REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (a Google só a aceita a poucas apps): o botão
//       «Deixar a app ligada com o ecrã bloqueado» abre as definições da app, onde a pessoa escolhe «Sem restrições»;
//     - sem cópia de segurança automática dos dados da app (allowBackup=false: a sessão não vai para a Google Drive).
//  7. (07/11/2026) «Exportar GPX» e «Descarregar os meus dados» na app: plugin GeoredeFicheiro (nativo/) e o
//     FileProvider do Capacitor com acesso à cache.
//  8. (07/11/2026) login no browser do Android (para a conta Google funcionar): plugin GeoredeLogin, a biblioteca
//     androidx.browser (Custom Tabs) e o intent-filter georede://login na MainActivity.
// Pode correr-se mais de uma vez.
const VERSAO_NOME = "1.0.0";
const VERSAO_CODIGO = 5;          // tem de subir sempre: 1, 2, 3, … (a Play recusa um código repetido)
const SDK = 36;

const fs = require("fs");
const path = require("path");
const BASE = path.join(__dirname, "android", "app", "src", "main");
const F = path.join(BASE, "AndroidManifest.xml");
if (!fs.existsSync(F)) { console.error("FALHOU: falta a pasta android. Corre primeiro: npx cap add android"); process.exit(1); }

// 1, 2 e 3: o manifesto
let x = fs.readFileSync(F, "utf8");
if (!x.includes("xmlns:tools=")) x = x.replace("<manifest ", '<manifest xmlns:tools="http://schemas.android.com/tools" ');
// 3: tirar o limite antigo (maxSdkVersion 30) da localização, se lá estiver
let semLimite = 0;
x = x.replace(/<uses-permission android:name="android\.permission\.(ACCESS_COARSE_LOCATION|ACCESS_FINE_LOCATION)" android:maxSdkVersion="30" \/>/g,
  (m, p) => { semLimite++; return `<uses-permission android:name="android.permission.${p}" />`; });
// 6: a permissão que a Google Play não aceita
// (com tools:node="remove", para nenhuma biblioteca a voltar a meter no manifesto final)
const REMOVER_BATERIA = '<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" tools:node="remove" />';
let semBateria = false;
x = x.replace(/<uses-permission android:name="android\.permission\.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"(?![^>]*tools:node="remove")[^>]*\/>/g,
  () => { semBateria = true; return REMOVER_BATERIA; });
if (!x.includes(REMOVER_BATERIA)) {
  x = x.replace(/\n([ \t]*)<application/, (m, ind) => `\n${ind}${REMOVER_BATERIA}\n${ind}<application`);
  semBateria = true;
}
// 6: sem cópia automática dos dados da app para a Google Drive (cookies da sessão, Geo guardado)
let semCopia = false;
if (/android:allowBackup="true"/.test(x)) { x = x.replace(/android:allowBackup="true"/, 'android:allowBackup="false"'); semCopia = true; }
else if (!/android:allowBackup=/.test(x)) { x = x.replace("<application", '<application android:allowBackup="false"'); semCopia = true; }
const permissoes = [
  '<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />',
  '<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />',
  '<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />',
  '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />',
  '<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" tools:targetApi="s" />',
  '<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />',
  '<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />',
  '<uses-feature android:name="android.hardware.location.gps" android:required="false" />',
  '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
  '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />',
  '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
  '<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />',
  '<uses-permission android:name="android.permission.WAKE_LOCK" />',
  '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />'
];
const nome = l => l.split('"')[1];
const novas = permissoes.filter(l => !x.includes('"' + nome(l) + '"'));
if (novas.length) x = x.replace(/\n([ \t]*)<application/, (m, ind) => `\n${ind}${novas.join("\n" + ind)}\n\n${ind}<application`);
const servico = '<service android:name=".GeoredeServico" android:exported="false" android:foregroundServiceType="connectedDevice" />';
let comServico = false;
if (!x.includes(".GeoredeServico\"")) {
  x = x.replace(/\n([ \t]*)<\/application>/, (m, ind) => `\n${ind}    ${servico}\n${ind}</application>`);
  comServico = true;
}
// 5: «Vamos encontrar-nos» com o ecrã bloqueado (serviço de localização em primeiro plano)
const servicoEncontro = '<service android:name=".GeoredeEncontroServico" android:exported="false" android:foregroundServiceType="location" />';
let comEncontro = false;
if (!x.includes(".GeoredeEncontroServico")) {
  x = x.replace(/\n([ \t]*)<\/application>/, (m, ind) => `\n${ind}    ${servicoEncontro}\n${ind}</application>`);
  comEncontro = true;
}
// 4: o serviço que recebe as notificações do Firebase
const servicoPush = [
  '<service android:name=".GeoredePushServico" android:exported="false">',
  '    <intent-filter>',
  '        <action android:name="com.google.firebase.MESSAGING_EVENT" />',
  '    </intent-filter>',
  '</service>'
];
let comPush = false;
if (!x.includes(".GeoredePushServico")) {
  x = x.replace(/\n([ \t]*)<\/application>/, (m, ind) => `\n${ind}    ${servicoPush.join("\n" + ind + "    ")}\n${ind}</application>`);
  comPush = true;
}
fs.writeFileSync(F, x);
console.log(novas.length ? `OK: ${novas.length} permissões acrescentadas ao AndroidManifest.xml` : "OK: as permissões já lá estavam.");
if (semLimite) console.log(`OK: localização do telemóvel em todas as versões do Android (${semLimite} linhas sem o limite antigo)`);
if (semBateria) console.log("OK: tirada a permissão REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (a Google Play não a aceita)");
if (semCopia) console.log("OK: allowBackup=false (os dados da app não vão para a cópia da Google Drive)");
if (comServico) console.log("OK: serviço GeoredeServico declarado no AndroidManifest.xml");
if (comPush) console.log("OK: serviço GeoredePushServico (notificações) declarado no AndroidManifest.xml");
if (comEncontro) console.log("OK: serviço GeoredeEncontroServico (encontro com o ecrã bloqueado) declarado no AndroidManifest.xml");

// 2: o código nativo (os plugins, os serviços e a MainActivity que os regista)
const destino = path.join(BASE, "java", "com", "georede", "app");
fs.mkdirSync(destino, { recursive: true });
for (const f of fs.readdirSync(path.join(__dirname, "nativo"))) {
  if (!f.endsWith(".java")) continue;
  fs.copyFileSync(path.join(__dirname, "nativo", f), path.join(destino, f));
  console.log("OK: " + f);
}

// 7: guardar/partilhar ficheiros (GPX, os teus dados): o FileProvider do Capacitor tem de dar acesso à cache
const caminhos = path.join(BASE, "res", "xml", "file_paths.xml");
if (fs.existsSync(caminhos)) {
  let c = fs.readFileSync(caminhos, "utf8");
  if (!/<cache-path[^>]*path="\."/.test(c)) {
    c = c.replace(/<\/paths>/, '    <cache-path name="georede_partilhar" path="." />\n</paths>');
    fs.writeFileSync(caminhos, c);
    console.log("OK: cache-path acrescentado a res/xml/file_paths.xml (partilhar o GPX)");
  }
} else {
  fs.mkdirSync(path.dirname(caminhos), { recursive: true });
  fs.writeFileSync(caminhos, '<?xml version="1.0" encoding="utf-8"?>\n<paths xmlns:android="http://schemas.android.com/apk/res/android">\n'
    + '    <cache-path name="georede_partilhar" path="." />\n</paths>\n');
  console.log("OK: res/xml/file_paths.xml criado (partilhar o GPX)");
}
if (!x.includes("androidx.core.content.FileProvider")) {
  const prov = '<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider" '
    + 'android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" '
    + 'android:resource="@xml/file_paths" /></provider>';
  x = x.replace(/\n([ \t]*)<\/application>/, (m, ind) => `\n${ind}    ${prov}\n${ind}</application>`);
  fs.writeFileSync(F, x);
  console.log("OK: FileProvider declarado no AndroidManifest.xml");
}

// 8: login no browser do Android (conta Google): o regresso georede://login abre a MainActivity
const regresso = '<intent-filter><action android:name="android.intent.action.VIEW" /><category android:name="android.intent.category.DEFAULT" />'
  + '<category android:name="android.intent.category.BROWSABLE" /><data android:scheme="georede" android:host="login" /></intent-filter>';
if (!x.includes('android:scheme="georede"')) {
  const m = /<activity[^>]*android:name="(\.MainActivity|com\.georede\.app\.MainActivity)"[\s\S]*?<\/activity>/.exec(x);
  if (m) {
    const nova = m[0].replace(/\n([ \t]*)<\/activity>$/, (t, ind) => `\n${ind}    ${regresso}\n${ind}</activity>`);
    x = x.replace(m[0], nova === m[0] ? m[0].replace("</activity>", regresso + "</activity>") : nova);
    fs.writeFileSync(F, x);
    console.log("OK: regresso do login (georede://login) na MainActivity");
  } else {
    console.log("AVISO: não encontrei a MainActivity no AndroidManifest.xml (login com a conta Google)");
  }
}

// 4: o ícone branco da barra de notificações
const icone = path.join(__dirname, "nativo", "ic_georede_notificacao.png");
if (fs.existsSync(icone)) {
  const drawable = path.join(BASE, "res", "drawable");
  fs.mkdirSync(drawable, { recursive: true });
  fs.copyFileSync(icone, path.join(drawable, "ic_georede_notificacao.png"));
  console.log("OK: ícone das notificações (res/drawable/ic_georede_notificacao.png)");
}

// 4: o Firebase no Gradle
const raiz = path.join(__dirname, "android", "build.gradle");
let g = fs.readFileSync(raiz, "utf8");
if (!g.includes("com.google.gms:google-services")) {
  g = g.replace(/(classpath ['"]com\.android\.tools\.build:gradle:[^'"]+['"])/, `$1\n        classpath 'com.google.gms:google-services:4.4.2'`);
  fs.writeFileSync(raiz, g);
  console.log("OK: plugin google-services no android/build.gradle");
}
const app = path.join(__dirname, "android", "app", "build.gradle");
let a = fs.readFileSync(app, "utf8");
let mudouApp = false;
if (!a.includes("com.google.firebase:firebase-messaging")) {
  a = a.replace(/dependencies \{\n/, `dependencies {\n    implementation 'com.google.firebase:firebase-messaging:24.1.0'   // GEOREDE: notificações\n`);
  mudouApp = true;
}
if (!a.includes("androidx.browser:browser")) {
  a = a.replace(/dependencies \{\n/, `dependencies {\n    implementation 'androidx.browser:browser:1.8.0'   // GEOREDE: login no browser (Custom Tabs)\n`);
  mudouApp = true;
}
if (!a.includes("com.google.gms.google-services")) {
  a += `\ntry {\n    def servicesJSON = file('google-services.json')\n    if (servicesJSON.text) {\n        apply plugin: 'com.google.gms.google-services'\n    }\n} catch(Exception e) {\n    logger.info("google-services.json not found, google-services plugin not applied. Push Notifications won't work")\n}\n`;
  mudouApp = true;
}
// 6: a versão da app
const antes = a;
a = a.replace(/versionCode\s+\d+/, `versionCode ${VERSAO_CODIGO}`).replace(/versionName\s+"[^"]*"/, `versionName "${VERSAO_NOME}"`);
if (a !== antes) mudouApp = true;
if (mudouApp) fs.writeFileSync(app, a);
console.log(`OK: versão ${VERSAO_NOME} (código ${VERSAO_CODIGO}) e firebase-messaging no android/app/build.gradle`);

// 6: Android 16 (API 36) — no Capacitor os números estão em android/variables.gradle
const vars = path.join(__dirname, "android", "variables.gradle");
if (fs.existsSync(vars)) {
  let v = fs.readFileSync(vars, "utf8");
  const v0 = v;
  for (const k of ["compileSdkVersion", "targetSdkVersion"]) {
    v = v.replace(new RegExp(`(${k}\\s*=\\s*)(\\d+)`), (m, p, n) => (Number(n) < SDK ? p + SDK : m));
  }
  if (v !== v0) { fs.writeFileSync(vars, v); console.log(`OK: compileSdk e targetSdk ${SDK} em android/variables.gradle`); }
  const alvo = (v.match(/targetSdkVersion\s*=\s*(\d+)/) || [])[1];
  console.log(`OK: targetSdk = ${alvo || "?"} (a Google Play pede ${SDK} ou mais)`);
} else {
  console.log("AVISO: não encontrei android/variables.gradle; confirma no Android Studio que o targetSdk é " + SDK);
}

// 4: o google-services.json (vem da consola do Firebase: Definições do projeto → a app Android → transferir)
const gs = path.join(__dirname, "google-services.json");
if (fs.existsSync(gs)) {
  fs.copyFileSync(gs, path.join(__dirname, "android", "app", "google-services.json"));
  console.log("OK: google-services.json copiado para android/app/");
} else if (!fs.existsSync(path.join(__dirname, "android", "app", "google-services.json"))) {
  console.log("AVISO: falta o google-services.json nesta pasta (da consola do Firebase). Sem ele a app compila mas não recebe notificações.");
}
console.log("Seguinte: npx cap sync android  e depois, no Android Studio, ▶ (Run) ou Build → Generate Signed App Bundle");
