// Corrige o erro "cannot find symbol setRequestPromotedOngoing" (SDK 36 não tem estes métodos).
// Troca a chamada direta por reflexão. Corre na pasta do projeto: node corrigir-pilula.js
const fs = require("fs");
const path = require("path");
const alvo = process.argv[2] || ".";
const candidatos = [
  path.join(alvo, "nativo", "GeoredeServico.java"),
  path.join(alvo, "android", "app", "src", "main", "java", "com", "georede", "app", "GeoredeServico.java"),
];
const velha = "if (com) b.setRequestPromotedOngoing(true).setShortCriticalText(pilula);";
const nova =
  "if (com) {\n" +
  "                // por reflexão: estes métodos só existem no SDK 36.1\n" +
  "                try {\n" +
  "                    Notification.Builder.class.getMethod(\"setRequestPromotedOngoing\", boolean.class).invoke(b, true);\n" +
  "                    Notification.Builder.class.getMethod(\"setShortCriticalText\", CharSequence.class).invoke(b, pilula);\n" +
  "                } catch (Throwable ignorado) { }\n" +
  "            }";
let feitos = 0;
for (const f of candidatos) {
  if (!fs.existsSync(f)) continue;
  let s = fs.readFileSync(f, "utf8");
  const crlf = s.includes("\r\n");
  if (crlf) s = s.replace(/\r\n/g, "\n");
  if (s.includes("getMethod(\"setRequestPromotedOngoing\"")) { console.log("já corrigido: " + f); continue; }
  const n = s.split(velha).length - 1;
  if (n !== 1) { console.log("não encontrei a linha (" + n + "x) em " + f + " — manda-me o ficheiro"); continue; }
  fs.copyFileSync(f, f + ".antes-reflexao");
  s = s.replace(velha, nova);
  if (crlf) s = s.replace(/\n/g, "\r\n");
  fs.writeFileSync(f, s);
  console.log("corrigido: " + f);
  feitos++;
}
if (!feitos) console.log("nada alterado");
