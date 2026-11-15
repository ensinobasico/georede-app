package com.georede.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

/**
 * GEOREDE — atividade principal (Capacitor). Regista os plugins próprios:
 *   GeoredeServico (serviço em primeiro plano, passo 2a), GeoredeRadio (ligação nativa ao Geo e ponte, passo 2b)
 *   GeoredePush (notificações pelo Firebase) e GeoredeEncontro («Vamos encontrar-nos» com o ecrã bloqueado), 06/11/2026;
 *   GeoredeFicheiro (guardar/partilhar o GPX das rotas e os teus dados) e GeoredeLogin (login no browser do Android,
 *   para a conta Google funcionar), 07/11/2026.
 * Com o serviço ligado tenta não deixar o WebView adormecer; mesmo que o Android o congele, a ligação e a ponte
 * continuam no serviço (passo 2b) e a página recebe o que chegou quando voltar.
 * Um toque numa notificação chega com o extra "georede_url" (ex.: /mensagens?c=3): a página muda para essa secção.
 * 09/11/2026 (revisão da UI): a letra da app segue o tamanho de letra do Android, mas no máximo 115 % (com a letra do
 * sistema a 130 % os títulos ficavam do tamanho do texto e o painel mostrava muito pouco). Quem usa letra pequena ou
 * normal fica igual.
 * 09/11/2026: links https://app.georede.com/... (emails) abrem na app, na secção certa (GeoredeLinks + intent-filter).
 */
public class MainActivity extends BridgeActivity {
    /** Aumento máximo da letra da página, em % (100 = tamanho normal). */
    static final int LETRA_MAX = 115;
    private String urlPendente = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GeoredeServicoPlugin.class);
        registerPlugin(GeoredeRadioPlugin.class);
        registerPlugin(GeoredePushPlugin.class);
        registerPlugin(GeoredeEncontroPlugin.class);
        registerPlugin(GeoredeFicheiroPlugin.class);
        registerPlugin(GeoredeLoginPlugin.class);
        registerPlugin(GeoredeVozPlugin.class);
        super.onCreate(savedInstanceState);
        urlPendente = urlDaNotificacao(getIntent());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { voltar(); }
        });
        limitarLetra();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = urlDaNotificacao(intent);
        if (url != null) abrirNaPagina(url);
    }

    @Override
    public void onResume() {
        super.onResume();
        limitarLetra();                               // a pessoa pode ter mudado a letra do Android entretanto
        if (urlPendente != null) {
            // A app acabou de arrancar pela notificação ou por um link: a página ainda está a carregar; dá-lhe um momento
            final String url = urlPendente;
            urlPendente = null;
            WebView w = getBridge() == null ? null : getBridge().getWebView();
            if (w != null) w.postDelayed(() -> abrirNaPagina(url), 1500);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration novo) {
        super.onConfigurationChanged(novo);
        limitarLetra();
    }

    @Override
    public void onPause() {
        super.onPause();
        manterAcordado();
    }

    @Override
    public void onStop() {
        super.onStop();
        manterAcordado();
    }

    /** Botao para tras (11/11): pergunta a pagina; se nao houver nada para fechar, a app vai para segundo plano. */
    private void voltar() {
        WebView w = getBridge() == null ? null : getBridge().getWebView();
        if (w == null) { moveTaskToBack(true); return; }
        w.evaluateJavascript("(function(){try{if(typeof window.georedeVoltar==='function'){return window.georedeVoltar()?'sim':'nao'}}catch(e){}return 'fora'})()", r -> {
            if (r != null && r.contains("sim")) return;
            if (r != null && r.contains("nao")) { moveTaskToBack(true); return; }
            if (w.canGoBack()) w.goBack(); else moveTaskToBack(true);
        });
    }

    /** Letra da página = a do Android (fontScale), sem passar de LETRA_MAX %. */
    private void limitarLetra() {
        if (getBridge() == null) return;
        WebView w = getBridge().getWebView();
        if (w == null) return;
        WebSettings s = w.getSettings();
        float escala = getResources().getConfiguration().fontScale;
        int zoom = Math.round(Math.min(escala * 100f, LETRA_MAX));
        if (s.getTextZoom() != zoom) s.setTextZoom(zoom);
    }

    private void manterAcordado() {
        if (!GeoredeServico.ativo || getBridge() == null) return;
        WebView w = getBridge().getWebView();
        if (w == null) return;
        w.onResume();
        w.resumeTimers();
    }

    /** Caminho a abrir: um link https://app.georede.com/... ou o toque numa notificação. Só caminhos da nossa app. */
    static String urlDaNotificacao(Intent i) {
        if (i == null) return null;
        String link = GeoredeLinks.caminho(i);              // link https://app.georede.com/... (email)
        if (link != null) return link;
        String u = i.getStringExtra(GeoredePushServico.EXTRA_URL);
        if (u == null || !u.startsWith("/") || u.startsWith("//") || !u.matches("[A-Za-z0-9/?=&_.%~-]{1,300}")) return null;
        i.removeExtra(GeoredePushServico.EXTRA_URL);          // não voltar a abrir ao rodar o ecrã
        return u;
    }

    /** Muda a página para a secção (window.georedeAbrir, no index.html); se a página não responder, carrega-a. */
    private void abrirNaPagina(String url) {
        if (getBridge() == null) return;
        WebView w = getBridge().getWebView();
        if (w == null) return;
        w.post(() -> {
            String atual = w.getUrl();
            if (atual == null || !atual.startsWith("https://")) { w.loadUrl("https://app.georede.com" + url); return; }
            String origem = "https://app.georede.com";   // links e notificações abrem sempre a app (não o login)
            w.evaluateJavascript("(function(){try{if(typeof window.georedeAbrir==='function'){window.georedeAbrir('" + url
                + "');return 'ok'}}catch(e){}return 'nao'})()", r -> {
                if (r == null || !r.contains("ok")) w.loadUrl(origem + url);
            });
        });
    }
}
