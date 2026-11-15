// GEOREDE — o botão «Tirar a pílula» da notificação só aparece nos telemóveis que têm a pílula (Android 16 atualizado).
// Corre na pasta do projeto (a do preparar.js):  node esconder-botao-pilula.js
const fs = require("fs");
const path = require("path");
const raiz = process.argv[2] || ".";
const ficheiros = [
  path.join(raiz, "nativo", "GeoredeServico.java"),
  path.join(raiz, "android", "app", "src", "main", "java", "com", "georede", "app", "GeoredeServico.java"),
];
const trocas = [
  ["    private boolean pilulaLigada() {",
   "    // O telemóvel tem a pílula? (os métodos só existem no Android 16 atualizado, SDK 36.1)\n" +
   "    private static Boolean temPilula = null;\n" +
   "    private static boolean pilulaSuportada() {\n" +
   "        if (temPilula == null) {\n" +
   "            try {\n" +
   "                Notification.Builder.class.getMethod(\"setRequestPromotedOngoing\", boolean.class);\n" +
   "                temPilula = true;\n" +
   "            } catch (Throwable e) {\n" +
   "                temPilula = false;\n" +
   "            }\n" +
   "        }\n" +
   "        return temPilula;\n" +
   "    }\n\n" +
   "    private boolean pilulaLigada() {"],
  ["            if (com) {\n", "            if (com && pilulaSuportada()) {\n"],
  ["            b.addAction(new Notification.Action.Builder(0, com ?", "            if (pilulaSuportada()) b.addAction(new Notification.Action.Builder(0, com ?"],
];
let achou = false;
for (const f of ficheiros) {
  if (!fs.existsSync(f)) continue;
  achou = true;
  let s = fs.readFileSync(f, "utf8");
  const crlf = s.includes("\r\n");
  if (crlf) s = s.replace(/\r\n/g, "\n");
  if (s.includes("pilulaSuportada()")) { console.log("já estava: " + f); continue; }
  let ok = true;
  for (const [a] of trocas) if (s.split(a).length !== 2) { console.log("não encontro «" + a.trim().slice(0, 50) + "» em " + f + " — manda-me o ficheiro"); ok = false; }
  if (!ok) continue;
  fs.copyFileSync(f, f + ".antes-esconder");
  for (const [a, b] of trocas) s = s.replace(a, b);
  if (crlf) s = s.replace(/\n/g, "\r\n");
  fs.writeFileSync(f, s);
  console.log("OK: botão da notificação só com pílula: " + f);
}
if (!achou) { console.log("FALHOU: não encontro o GeoredeServico.java. Corre na pasta do projeto."); process.exit(1); }
