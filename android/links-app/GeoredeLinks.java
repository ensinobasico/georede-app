package com.georede.app;

import android.content.Intent;
import android.net.Uri;

/**
 * GEOREDE — links https://app.georede.com/... (emails, mensagens) abrem na app (Android App Links, 09/11/2026).
 * O Android só entrega à app os caminhos do intent-filter (links-app.js); aqui confere-se outra vez e devolve-se
 * o caminho para a MainActivity abrir na página (window.georedeAbrir, como no toque numa notificação).
 * Fora destas secções (login, /api/legal, /api/e, confirmações de email) os links continuam no browser.
 */
final class GeoredeLinks {
    static final String HOST = "app.georede.com";
    /** Secções da app que um link pode abrir. Manter igual à lista do links-app.js. */
    static final String[] SECCOES = {"/mapa", "/mensagens", "/zonas", "/alertas", "/mais"};

    private GeoredeLinks() {}

    /** Caminho da app (ex.: "/alertas", "/mensagens?c=3") ou null se o intent não for um link nosso. */
    static String caminho(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction())) return null;
        Uri u = i.getData();
        if (u == null || !"https".equals(u.getScheme()) || !HOST.equalsIgnoreCase(u.getHost())) return null;
        if (u.getPort() != -1 && u.getPort() != 443) return null;
        String p = u.getEncodedPath();
        if (p == null || p.isEmpty() || p.equals("/")) p = "/mapa";
        boolean nosso = false;
        for (String s : SECCOES) if (p.equals(s) || p.startsWith(s + "/")) { nosso = true; break; }
        if (!nosso || !seguro(p)) return null;
        String q = u.getEncodedQuery();
        String r = (q == null || q.isEmpty()) ? p : p + "?" + q;
        if (!seguro(r)) r = p;                                  // pergunta estranha: abre só a secção
        i.setData(null);                                        // não voltar a abrir ao rodar o ecrã
        return r;
    }

    private static boolean seguro(String s) {
        return s.startsWith("/") && !s.startsWith("//") && s.length() <= 300 && s.matches("[A-Za-z0-9/?=&_.%~-]+");
    }
}
