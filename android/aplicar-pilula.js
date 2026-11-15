// GEOREDE — pílula na barra de estado (Android 16, "Live Updates"), 11/11/2026 (v2).
// Corre na pasta do projeto Android (a que tem o preparar.js e a pasta nativo):
//     node aplicar-pilula.js
// O que faz (e só escreve se TUDO encaixar; senão não toca em nada):
//  1. nativo/GeoredeServico.java: no Android 16+ a notificação fixa passa a pedir promoção a Live Update,
//     com o ícone G e um texto curto (Ligado / A ligar / Aviso) na pílula junto à câmara.
//  2. (v2) a notificação ganha o botão «Tirar a pílula» / «Pôr a pílula»; a escolha fica guardada no telemóvel.
//  3. preparar.js: acrescenta a permissão POST_PROMOTED_NOTIFICATIONS à lista de permissões do manifesto.
// Guarda cópias com a terminação .antes-pilula. Pode correr-se mais de uma vez (e por cima da v1).
const fs = require("fs");
const path = require("path");
const RAIZ = process.argv[2] ? path.resolve(process.argv[2]) : __dirname;
const JAVA = path.join(RAIZ, "nativo", "GeoredeServico.java");
const PREP = path.join(RAIZ, "preparar.js");

function ler(f) {
  if (!fs.existsSync(f)) { console.error("FALHOU: não encontro " + f + "\nCorre este script na pasta do projeto (a do preparar.js)."); process.exit(1); }
  const bruto = fs.readFileSync(f, "utf8");
  return { crlf: bruto.includes("\r\n"), s: bruto.replace(/\r\n/g, "\n") };
}
function trocar(s, de, para, nome) {
  const n = s.split(de).length - 1;
  if (n !== 1) { console.error(`FALHOU (${nome}): esperava encontrar 1 vez e encontrei ${n}. O ficheiro está diferente do que eu tinha; não alterei nada. Manda-me o GeoredeServico.java.`); process.exit(1); }
  return s.replace(de, () => para);
}

// ---- 1. GeoredeServico.java
const j = ler(JAVA);
let s = j.s;
let mudou = false;

// v1: a pílula
if (!s.includes("setRequestPromotedOngoing")) {
  s = trocar(s,
    'private String titulo = "GEOREDE", texto = "A ligar ao teu Geo…", ultimoTexto = null;',
    'private String titulo = "GEOREDE", texto = "A ligar ao teu Geo…", ultimoTexto = null;\n' +
    '    private String pilula = "A ligar";     // texto curto da pílula (Android 16+): menos de 7 caracteres aparece inteiro',
    "campos");
  s = trocar(s, "            ultimoTexto = texto;\n            return true;", "            ultimoTexto = texto + \"|\" + pilula;\n            return true;", "primeiroPlano");
  s = trocar(s,
    "        return new NotificationCompat.Builder(this, CANAL)\n            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)",
    "        if (Build.VERSION.SDK_INT >= 36) {\n" +
    "            // Android 16+: Live Update. A notificação fixa passa a aparecer também como pílula na barra de estado\n" +
    "            // (junto à câmara), com o ícone G e o texto curto. Em versões antigas fica a notificação de sempre.\n" +
    "            Notification.Builder b = new Notification.Builder(this, CANAL)\n" +
    "                .setSmallIcon(GeoredePushServico.iconePequeno(this))\n" +
    "                .setContentTitle(titulo)\n" +
    "                .setContentText(t)\n" +
    "                .setStyle(new Notification.BigTextStyle().bigText(t))\n" +
    "                .setOngoing(true)\n" +
    "                .setOnlyAlertOnce(true)\n" +
    "                .setCategory(Notification.CATEGORY_SERVICE)\n" +
    "                .setRequestPromotedOngoing(true)\n" +
    "                .setShortCriticalText(pilula);\n" +
    "            if (pi != null) b.setContentIntent(pi);\n" +
    "            return b.build();\n" +
    "        }\n" +
    "        return new NotificationCompat.Builder(this, CANAL)\n            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)",
    "notificacao");
  s = trocar(s,
    "        if (t.equals(ultimoTexto)) return;\n        ultimoTexto = t;\n",
    "        // pílula: \"Ligado\" com o Geo ligado, \"Aviso\" se a ponte para o servidor tem um problema, \"A ligar\" na procura\n" +
    "        String pil = \"A ligar\";\n" +
    "        if (\"ligado\".equals(radio)) {\n" +
    "            String pe = String.valueOf(r.get(\"estado\"));\n" +
    "            boolean problema = Boolean.TRUE.equals(r.get(\"ponte\")) && !\"ligada\".equals(pe)\n" +
    "                && !\"a ligar\".equals(pe) && !\"à espera do Geo\".equals(pe);\n" +
    "            pil = problema ? \"Aviso\" : \"Ligado\";\n" +
    "        }\n" +
    "        pilula = pil;\n" +
    "        String chave = t + \"|\" + pil;\n" +
    "        if (chave.equals(ultimoTexto)) return;\n" +
    "        ultimoTexto = chave;\n",
    "estado");
  mudou = true;
  console.log("OK: pílula acrescentada ao GeoredeServico.java");
} else {
  console.log("OK: GeoredeServico.java já tinha a pílula");
}

// v2: o botão «Tirar a pílula» / «Pôr a pílula»
if (!s.includes("ACAO_PILULA")) {
  s = trocar(s,
    '    static final String ACAO_TEXTO = "com.georede.app.TEXTO";\n',
    '    static final String ACAO_TEXTO = "com.georede.app.TEXTO";\n' +
    '    static final String ACAO_PILULA = "com.georede.app.PILULA";     // botão da notificação: tira ou põe a pílula\n',
    "ACAO_PILULA");
  s = trocar(s,
    "        String acao = intent == null ? null : intent.getAction();\n",
    "        String acao = intent == null ? null : intent.getAction();\n" +
    "        if (ACAO_PILULA.equals(acao)) { alternarPilula(); return START_STICKY; }\n",
    "onStartCommand");
  s = trocar(s,
    "    private boolean primeiroPlano() {",
    "    // A pílula pode ser tirada e posta pelo botão da notificação; a escolha fica guardada (por defeito, ligada)\n" +
    "    private boolean pilulaLigada() {\n" +
    "        return getSharedPreferences(\"georede_pilula\", Context.MODE_PRIVATE).getBoolean(\"ligada\", true);\n" +
    "    }\n\n" +
    "    private void alternarPilula() {\n" +
    "        getSharedPreferences(\"georede_pilula\", Context.MODE_PRIVATE).edit().putBoolean(\"ligada\", !pilulaLigada()).apply();\n" +
    "        ultimoTexto = null;                        // força a notificação a ser refeita\n" +
    "        estado(central.resumo());\n" +
    "    }\n\n" +
    "    private boolean primeiroPlano() {",
    "metodos");
  s = trocar(s,
    "                .setCategory(Notification.CATEGORY_SERVICE)\n" +
    "                .setRequestPromotedOngoing(true)\n" +
    "                .setShortCriticalText(pilula);\n" +
    "            if (pi != null) b.setContentIntent(pi);\n" +
    "            return b.build();\n",
    "                .setCategory(Notification.CATEGORY_SERVICE);\n" +
    "            boolean com = pilulaLigada();\n" +
    "            if (com) {\n" +
    "                // por reflexão: estes métodos só existem no SDK 36.1; assim compila com o 36\n" +
    "                try {\n" +
    "                    Notification.Builder.class.getMethod(\"setRequestPromotedOngoing\", boolean.class).invoke(b, true);\n" +
    "                    Notification.Builder.class.getMethod(\"setShortCriticalText\", CharSequence.class).invoke(b, pilula);\n" +
    "                } catch (Throwable ignorado) { }\n" +
    "            }\n" +
    "            PendingIntent alt = PendingIntent.getService(this, 1,\n" +
    "                new Intent(this, GeoredeServico.class).setAction(ACAO_PILULA),\n" +
    "                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);\n" +
    "            b.addAction(new Notification.Action.Builder(0, com ? \"Tirar a pílula\" : \"Pôr a pílula\", alt).build());\n" +
    "            if (pi != null) b.setContentIntent(pi);\n" +
    "            return b.build();\n",
    "botao");
  s = trocar(s,
    '        String chave = t + "|" + pil;\n',
    '        String chave = t + "|" + pil + "|" + pilulaLigada();\n',
    "chave");
  mudou = true;
  console.log("OK: botão «Tirar a pílula» / «Pôr a pílula» acrescentado");
} else {
  console.log("OK: GeoredeServico.java já tinha o botão da pílula");
}
if (mudou) {
  fs.writeFileSync(JAVA + ".antes-pilula", j.crlf ? j.s.replace(/\n/g, "\r\n") : j.s);
  fs.writeFileSync(JAVA, j.crlf ? s.replace(/\n/g, "\r\n") : s);
  console.log("OK: nativo/GeoredeServico.java gravado (cópia do que lá estava em GeoredeServico.java.antes-pilula)");
}

// ---- 2. preparar.js: a permissão
const p = ler(PREP);
if (p.s.includes("POST_PROMOTED_NOTIFICATIONS")) {
  console.log("OK: preparar.js já tinha a permissão POST_PROMOTED_NOTIFICATIONS");
} else {
  const re = /^([ \t]*)'<uses-permission android:name="android\.permission\.POST_NOTIFICATIONS" \/>',[ \t]*$/m;
  const m = p.s.match(re);
  if (!m) {
    console.log("AVISO: não encontrei a linha do POST_NOTIFICATIONS no preparar.js. Acrescenta à mão, na lista 'permissoes', esta linha:\n" +
      "  '<uses-permission android:name=\"android.permission.POST_PROMOTED_NOTIFICATIONS\" />',");
  } else {
    const novo = p.s.replace(re, (linha) => linha + "\n" + m[1] + "'<uses-permission android:name=\"android.permission.POST_PROMOTED_NOTIFICATIONS\" />',");
    fs.writeFileSync(PREP + ".antes-pilula", p.crlf ? p.s.replace(/\n/g, "\r\n") : p.s);
    fs.writeFileSync(PREP, p.crlf ? novo.replace(/\n/g, "\r\n") : novo);
    console.log("OK: preparar.js alterado (permissão POST_PROMOTED_NOTIFICATIONS; cópia em preparar.js.antes-pilula)");
  }
}
console.log("\nSeguinte:\n  node preparar.js\n  npx cap sync android\n  no Android Studio: ▶ (Run) com o telemóvel ligado por USB");
