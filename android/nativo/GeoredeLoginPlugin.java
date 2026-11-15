package com.georede.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;

import androidx.browser.customtabs.CustomTabsIntent;

import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * GEOREDE — login no browser do Android (07/11/2026). A Google não deixa entrar com a conta Google dentro de um
 * WebView («disallowed_useragent»), por isso o login da app não abre no WebView:
 *   1. o WebView vai para /auth/login (o Caddy manda para lá quem não tem sessão) → aqui cancela-se e abre-se
 *      /auth/login?app=<desafio> num Custom Tab (o Chrome do telemóvel), onde o Keycloak e a Google funcionam;
 *   2. no fim, o servidor manda o browser para georede://login?c=<código> → o Android volta à app (intent-filter
 *      da MainActivity) → aqui abre-se no WebView /auth/troca?c=<código>&v=<verificador>; a sessão nasce no WebView.
 * O desafio é o SHA-256 do verificador (como no PKCE), e o verificador nunca sai do telemóvel antes do passo 2:
 * outra app que registasse georede:// não consegue usar o código.
 */
@CapacitorPlugin(name = "GeoredeLogin")
public class GeoredeLoginPlugin extends Plugin {
    static final String PREFS = "georede-login";
    static final long VALIDADE_MS = 15 * 60 * 1000;     // um login esquecido no browser deixa de valer

    @Override
    public void load() {
        Intent i = getActivity() == null ? null : getActivity().getIntent();
        if (eRegresso(i)) {
            final Intent intent = i;
            getActivity().setIntent(new Intent(intent).setData(null));
            WebView w = getBridge().getWebView();
            if (w != null) w.postDelayed(() -> tratarRegresso(intent), 400);
        }
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (eRegresso(intent)) tratarRegresso(intent);
    }

    @Override
    public Boolean shouldOverrideLoad(Uri url) {
        if (url == null || !"https".equals(url.getScheme())) return null;
        Uri origem = origem();
        if (origem.getHost() == null || !origem.getHost().equals(url.getHost())) return null;
        if (!"/auth/login".equals(url.getPath()) || url.getQueryParameter("app") != null) return null;
        abrirNoBrowser(url);
        return true;                                        // o WebView não carrega o login
    }

    // ---------------------------------------------------------------- 1. abrir o login no browser
    private void abrirNoBrowser(Uri login) {
        Context c = getContext();
        String verificador = aleatorio(64);
        prefs(c).edit().putString("verificador", verificador).putLong("desde", System.currentTimeMillis()).apply();
        Uri alvo = login.buildUpon().appendQueryParameter("app", desafio(verificador)).build();
        String destino = login.getQueryParameter("destino");
        mostrarEspera(destino == null ? "/" : destino);
        try {
            CustomTabsIntent tab = new CustomTabsIntent.Builder().setShowTitle(true).build();
            tab.launchUrl(getActivity() != null ? getActivity() : c, alvo);
        } catch (ActivityNotFoundException | IllegalStateException e) {
            Intent i = new Intent(Intent.ACTION_VIEW, alvo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(i); } catch (Exception e2) { Log.e("GEOREDE", "login: sem browser para abrir", e2); }
        }
    }

    /** Enquanto a pessoa entra no browser, o WebView mostra uma página simples com um botão para tentar outra vez. */
    private void mostrarEspera(String destino) {
        WebView w = getBridge().getWebView();
        if (w == null) return;
        String outraVez = origem().buildUpon().path("/auth/login").appendQueryParameter("destino", destino).build().toString();
        String html = "<!doctype html><html lang=\"pt\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
            + "body{margin:0;font:17px/1.5 system-ui,sans-serif;background:#0B1220;color:#E8EEF7;display:grid;place-items:center;"
            + "min-height:100vh}main{max-width:20em;padding:24px;text-align:center}a{display:inline-block;margin-top:18px;"
            + "padding:14px 22px;border-radius:12px;background:#2563EB;color:#fff;text-decoration:none;font-weight:700}"
            + "</style></head><body><main><h1>GEOREDE</h1><p>A entrada na tua conta abriu no browser do telemóvel. "
            + "Quando acabares, voltas aqui sozinho.</p><a href=\"" + outraVez.replace("\"", "%22")
            + "\">Abrir outra vez</a></main></body></html>";
        w.post(() -> w.loadDataWithBaseURL(origem().toString(), html, "text/html", "utf-8", null));
    }

    // ---------------------------------------------------------------- 2. regresso: georede://login?c=…
    static boolean eRegresso(Intent i) {
        Uri d = i == null ? null : i.getData();
        return d != null && "georede".equals(d.getScheme()) && "login".equals(d.getHost());
    }

    private void tratarRegresso(Intent i) {
        String codigo = i.getData().getQueryParameter("c");
        SharedPreferences p = prefs(getContext());
        String verificador = p.getString("verificador", null);
        long desde = p.getLong("desde", 0);
        p.edit().remove("verificador").remove("desde").apply();
        WebView w = getBridge().getWebView();
        if (w == null) return;
        Uri base = origem();
        if (codigo == null || verificador == null || System.currentTimeMillis() - desde > VALIDADE_MS) {
            Log.w("GEOREDE", "login: regresso sem código ou sem verificador (pedido antigo?)");
            w.post(() -> w.loadUrl(base.buildUpon().path("/mapa").build().toString()));
            return;
        }
        String troca = base.buildUpon().path("/auth/troca").appendQueryParameter("c", codigo)
            .appendQueryParameter("v", verificador).build().toString();
        w.post(() -> w.loadUrl(troca));
    }

    // ---------------------------------------------------------------- auxiliares
    private Uri origem() {
        String s = null;
        try { s = getBridge().getConfig().getServerUrl(); } catch (Exception e) { /* sem configuração */ }
        Uri u = Uri.parse(s == null || s.isEmpty() ? "https://app.georede.com" : s);
        return new Uri.Builder().scheme("https").authority(u.getAuthority() == null ? "app.georede.com" : u.getAuthority()).build();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String aleatorio(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static String desafio(String verificador) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(verificador.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(h, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
