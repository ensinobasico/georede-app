// GEOREDE — método novo no plugin GeoredeServico para o botão «Atualizações em direto» (Mais) e o interruptor da pílula.
// Corre na pasta do projeto (a do preparar.js):  node aplicar-botao-pilula.js
// Acrescenta ao GeoredeServicoPlugin.java:
//   pilula()                       -> {ligada, suportada, permitida}
//   pilulaAlternar({ligada})       -> idem (liga ou desliga a pílula)
//   abrirAtualizacoesEmDireto()    -> abre a página do Android «Atualizações em direto» desta app
// e faz o GeoredeServico.java registar no Logcat (etiqueta GeoredeServico) porque é que a pílula falhou.
const fs = require("fs");
const path = require("path");
const raiz = process.argv[2] || ".";
const pastas = [
  path.join(raiz, "nativo"),
  path.join(raiz, "android", "app", "src", "main", "java", "com", "georede", "app"),
];

const METODOS = `    // ---- Atualizações em direto (a pílula, Android 16+) ----
    private static final String PREFS_PILULA = "georede_pilula";

    private boolean pilulaLigada() {
        return getContext().getSharedPreferences(PREFS_PILULA, Context.MODE_PRIVATE).getBoolean("ligada", true);
    }

    /** null = este Android não tem a função; true/false = a pessoa deixou ou não esta app mostrar a pílula. */
    private Boolean pilulaPermitida() {
        if (Build.VERSION.SDK_INT < 36) return null;
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            Object r = android.app.NotificationManager.class.getMethod("canPostPromotedNotifications").invoke(nm);
            return (Boolean) r;
        } catch (Throwable e) {
            return null;
        }
    }

    private JSObject resumoPilula() {
        JSObject r = new JSObject();
        Boolean p = pilulaPermitida();
        r.put("suportada", p != null);
        r.put("permitida", p != null && p);
        r.put("ligada", pilulaLigada());
        return r;
    }

    @PluginMethod
    public void pilula(PluginCall call) {
        call.resolve(resumoPilula());
    }

    /** {ligada: true|false}: o interruptor da pílula dentro da app (o mesmo botão da notificação). */
    @PluginMethod
    public void pilulaAlternar(PluginCall call) {
        Boolean quer = call.getBoolean("ligada");
        if (quer != null && quer != pilulaLigada()) {
            Context c = getContext();
            if (GeoredeServico.ativo) {
                try {
                    c.startService(new Intent(c, GeoredeServico.class).setAction(GeoredeServico.ACAO_PILULA));
                } catch (Exception e) {
                    c.getSharedPreferences(PREFS_PILULA, Context.MODE_PRIVATE).edit().putBoolean("ligada", quer).apply();
                }
            } else {
                c.getSharedPreferences(PREFS_PILULA, Context.MODE_PRIVATE).edit().putBoolean("ligada", quer).apply();
            }
        }
        JSObject r = resumoPilula();
        if (quer != null) r.put("ligada", quer);
        call.resolve(r);
    }

    /** Abre «Atualizações em direto» desta app; se este Android não tiver essa página, as notificações da app. */
    @PluginMethod
    public void abrirAtualizacoesEmDireto(PluginCall call) {
        Context c = getContext();
        String[] acoes = {
            "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS",
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        };
        for (String a : acoes) {
            try {
                Intent i = new Intent(a)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                JSObject r = resumoPilula();
                r.put("aberta", a.equals(acoes[0]) ? "atualizacoes" : "notificacoes");
                call.resolve(r);
                return;
            } catch (Exception e) {
                // tenta a seguinte
            }
        }
        call.reject("não consegui abrir as definições de notificações");
    }

`;

let feitos = 0, achou = false;
for (const p of pastas) {
  const f = path.join(p, "GeoredeServicoPlugin.java");
  if (!fs.existsSync(f)) continue;
  achou = true;
  let s = fs.readFileSync(f, "utf8");
  const crlf = s.includes("\r\n");
  if (crlf) s = s.replace(/\r\n/g, "\n");
  if (s.includes("abrirAtualizacoesEmDireto")) { console.log("já tinha o botão: " + f); continue; }
  const ancora = "    private JSObject resumo() {";
  if (s.split(ancora).length !== 2) { console.log("não encontro o ponto certo em " + f + " — manda-me o ficheiro"); process.exit(1); }
  fs.copyFileSync(f, f + ".antes-botao-pilula");
  s = s.replace(ancora, METODOS + ancora);
  if (crlf) s = s.replace(/\n/g, "\r\n");
  fs.writeFileSync(f, s);
  console.log("OK: métodos da pílula em " + f);
  feitos++;
}
if (!achou) { console.log("FALHOU: não encontro o GeoredeServicoPlugin.java. Corre na pasta do projeto (a do preparar.js)."); process.exit(1); }

// o serviço regista porque é que a pílula falhou (Logcat, etiqueta GeoredeServico)
for (const p of pastas) {
  const f = path.join(p, "GeoredeServico.java");
  if (!fs.existsSync(f)) continue;
  let s = fs.readFileSync(f, "utf8");
  const velha = "} catch (Throwable ignorado) { }";
  if (!s.includes(velha)) continue;
  fs.copyFileSync(f, f + ".antes-botao-pilula");
  s = s.replace(velha, "} catch (Throwable e) { android.util.Log.w(\"GeoredeServico\", \"pílula não disponível: \" + e); }");
  fs.writeFileSync(f, s);
  console.log("OK: o serviço regista no Logcat porque a pílula falhou: " + f);
}
console.log("\nSeguinte:\n  node preparar.js\n  npx cap sync android\n  Android Studio: ▶ Run");
