package com.georede.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GEOREDE — o centro da app nativa (passo 2b): uma só ligação ao Geo e uma só ponte por processo.
 * O serviço em primeiro plano mantém o processo vivo; a página (plugin GeoredeRadio) e o serviço falam com isto.
 * A ponte para o servidor corre aqui, em Java, de 3 em 3 s: continua com o ecrã bloqueado e a página congelada.
 */
final class GeoredeCentral implements GeoredeRadio.Ouvinte {
    interface Ouvinte {
        void estado(Map<String, Object> resumo);
        void haDados();
    }

    private static GeoredeCentral unica;
    static synchronized GeoredeCentral obter(Context c) {
        if (unica == null) unica = new GeoredeCentral(c.getApplicationContext());
        return unica;
    }

    static final String PREFS = "georede-radio";
    private final Context ctx;
    final PonteNucleo nucleo = new PonteNucleo();
    private final GeoredeRadio radio;
    private final ScheduledExecutorService ponte = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "georede-ponte"));
    private final List<Ouvinte> ouvintes = new CopyOnWriteArrayList<>();
    private volatile String origem = "https://app.georede.com";
    private volatile String nome = "";
    private volatile boolean dadosAvisados = false;     // um só aviso "dados" até a página ler (não encher o WebView congelado)

    private GeoredeCentral(Context ctx) {
        this.ctx = ctx;
        this.radio = new GeoredeRadio(ctx, nucleo, this);
        ponte.scheduleWithFixedDelay(this::volta, 1000, 500, TimeUnit.MILLISECONDS);
    }

    void juntar(Ouvinte o) { if (!ouvintes.contains(o)) ouvintes.add(o); }
    void largar(Ouvinte o) { ouvintes.remove(o); }

    // ---------------------------------------------------------------- comandos
    /** Liga (ou continua ligado) ao Geo. Guarda tudo para o serviço poder voltar sozinho se o Android o reiniciar. */
    void ligar(String endereco, String nomeGeo, String origemPagina, boolean comPonte) {
        if (origemPagina != null && origemPagina.startsWith("https://")) origem = origemPagina;
        if (nomeGeo != null && !nomeGeo.isEmpty()) nome = nomeGeo;
        nucleo.ligarPonte(comPonte);
        prefs().edit().putString("endereco", endereco).putString("nome", nome).putString("origem", origem)
            .putBoolean("ponte", comPonte).apply();
        radio.ligar(endereco);
    }

    /** O serviço foi reiniciado pelo Android (sem página): volta a ligar ao último Geo, se houver. */
    boolean religarGuardado() {
        SharedPreferences p = prefs();
        String e = p.getString("endereco", null);
        if (e == null) return false;
        ligar(e, p.getString("nome", ""), p.getString("origem", origem), p.getBoolean("ponte", true));
        return true;
    }

    void desligar() {
        prefs().edit().clear().apply();
        nucleo.ligarPonte(false);
        radio.desligar();
    }

    void ponte(boolean sim) {
        nucleo.ligarPonte(sim);
        prefs().edit().putBoolean("ponte", sim).apply();
        avisarEstado();
    }

    void nome(String n) { if (n != null && !n.isEmpty()) { nome = n; prefs().edit().putString("nome", n).apply(); avisarEstado(); } }

    void escrever(byte[] toRadio, GeoredeRadio.Feito feito) { radio.escrever(toRadio, feito); }

    List<byte[]> ler(int max) {
        dadosAvisados = false;
        List<byte[]> l = nucleo.lerParaPagina(max);
        if (nucleo.porLer() > 0) haDados();
        return l;
    }

    String estadoRadio() { return radio.estado(); }
    String nomeGeo() { return nome; }

    Map<String, Object> resumo() {
        Map<String, Object> m = nucleo.resumo();
        m.put("radio", radio.estado());
        m.put("endereco", radio.endereco());
        m.put("nome", nome);
        return m;
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- do rádio
    @Override public void estadoMudou(String estado) { avisarEstado(); }

    @Override public void haDados() {
        if (dadosAvisados) return;
        dadosAvisados = true;
        for (Ouvinte o : ouvintes) { try { o.haDados(); } catch (Exception e) { /* nada */ } }
    }

    private void avisarEstado() {
        Map<String, Object> r = resumo();
        for (Ouvinte o : ouvintes) { try { o.estado(r); } catch (Exception e) { /* nada */ } }
    }

    // ---------------------------------------------------------------- a ponte
    private String ultimoEstadoPonte = "";

    private void volta() {
        try {
            if (!"ligado".equals(radio.estado())) return;      // sem Geo não se pede (as descidas não teriam para onde ir)
            long agora = System.currentTimeMillis();
            String corpo = nucleo.pedidoSeForAltura(agora);
            if (corpo == null) return;
            int codigo; String resposta = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(origem + "/api/ponte").openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "application/json");
                String biscoitos = CookieManager.getInstance().getCookie(origem);     // a sessão da página (o mesmo login)
                if (biscoitos != null) c.setRequestProperty("Cookie", biscoitos);
                byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
                codigo = c.getResponseCode();
                guardarBiscoitos(c);
                if (codigo == 200) resposta = lerTudo(c.getInputStream());
                c.disconnect();
            } catch (Exception e) {
                codigo = -1;
            }
            List<byte[]> descidas = nucleo.depoisDoPedido(codigo, resposta, System.currentTimeMillis());
            for (byte[] d : descidas) radio.escrever(d, null);
            String e = String.valueOf(nucleo.resumo().get("estado"));
            if (!descidas.isEmpty() || !e.equals(ultimoEstadoPonte) || codigo == 200) {
                ultimoEstadoPonte = e;
                avisarEstado();
            }
        } catch (Exception e) {
            Log.w("GEOREDE", "ponte", e);
        }
    }

    /** Se o servidor renovar a sessão, a página e a ponte ficam com o mesmo biscoito. */
    private void guardarBiscoitos(HttpURLConnection c) {
        Map<String, List<String>> hs = c.getHeaderFields();
        if (hs == null) return;
        for (Map.Entry<String, List<String>> e : hs.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String v : e.getValue()) CookieManager.getInstance().setCookie(origem, v);
        }
        CookieManager.getInstance().flush();
    }

    private static String lerTudo(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        try (InputStream i = in) { while ((n = i.read(b)) > 0) { o.write(b, 0, n); if (o.size() > 1_000_000) break; } }
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    static void registo(String s) { Log.i("GEOREDE", s); }
}
