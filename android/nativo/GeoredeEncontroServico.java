package com.georede.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GEOREDE — «Vamos encontrar-nos» com o ecrã bloqueado (06/11/2026). Serviço em primeiro plano do tipo `location`:
 * enquanto o encontro durar, lê o GPS do telemóvel e manda a posição ao servidor (POST /api/encontros/posicao,
 * com a sessão da página, como a ponte faz) de RITMO_MS em RITMO_MS. A resposta traz o estado do encontro e a
 * distância ao outro: a notificação fixa mostra «A caminho de Ana · 380 m»; quando o servidor diz que acabou
 * (encontraram-se, parado, expirou) o serviço pára sozinho. O aviso do fim chega pelo Firebase (GeoredePushServico).
 *
 * Pedidos (Intent): ACAO_INICIAR id, origem, nome  |  ACAO_PARAR
 */
public class GeoredeEncontroServico extends Service implements LocationListener {
    static final String CANAL = "georede-encontro";
    static final int ID = 4753;
    static final String ACAO_INICIAR = "com.georede.app.ENCONTRO_INICIAR";
    static final String ACAO_PARAR = "com.georede.app.ENCONTRO_PARAR";
    static final long RITMO_MS = 4000;          // no máximo um envio de 4 em 4 s (a página manda de 5 em 5)
    static final long GPS_MS = 2000;
    static volatile boolean ativo = false;
    static volatile long encontroAtivo = 0;

    private final ExecutorService rede = Executors.newSingleThreadExecutor(r -> new Thread(r, "georede-encontro"));
    private PowerManager.WakeLock trinco;
    private LocationManager gps;
    private long id = 0;
    private String origem = "https://app.georede.com", nome = "";
    private long ultimoEnvio = 0;
    private volatile boolean aEnviar = false;
    private String ultimoTexto = null;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String acao = intent == null ? null : intent.getAction();
        if (ACAO_PARAR.equals(acao) || intent == null) { stopSelf(); return START_NOT_STICKY; }
        long novoId = intent.getLongExtra("id", 0);
        String o = intent.getStringExtra("origem"), n = intent.getStringExtra("nome");
        if (novoId <= 0) { stopSelf(); return START_NOT_STICKY; }
        if (o != null && o.startsWith("https://")) origem = o;
        if (n != null) nome = n;
        id = novoId;
        encontroAtivo = id;
        if (!primeiroPlano()) return START_NOT_STICKY;
        segurarTrinco();
        ligarGps();
        ativo = true;
        return START_NOT_STICKY;            // se o Android o matar, não volta sozinho (o encontro é curto; a página reinicia-o)
    }

    private boolean primeiroPlano() {
        try {
            criarCanal(this);
            int tipo = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
            ServiceCompat.startForeground(this, ID, notificacao(textoInicial()), tipo);
            return true;
        } catch (Exception e) {
            Log.e("GEOREDE", "encontro: não consegui pôr o serviço em primeiro plano", e);
            stopSelf();
            return false;
        }
    }

    private String textoInicial() { return nome.isEmpty() ? "A caminho…" : "A caminho de " + nome + "…"; }

    private void segurarTrinco() {
        if (trinco == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { trinco = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "georede:encontro"); trinco.setReferenceCounted(false); }
        }
        if (trinco != null) trinco.acquire(70L * 60 * 1000);      // um encontro dura no máximo 1 h
    }

    private void ligarGps() {
        if (gps != null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("GEOREDE", "encontro: sem permissão de localização");
            stopSelf();
            return;
        }
        gps = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (gps == null) { stopSelf(); return; }
        try {
            if (gps.isProviderEnabled(LocationManager.GPS_PROVIDER))
                gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_MS, 0f, this, Looper.getMainLooper());
            if (gps.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                gps.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, GPS_MS * 2, 0f, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.w("GEOREDE", "encontro: localização recusada");
            stopSelf();
        }
    }

    // ---------------------------------------------------------------- GPS
    @Override
    public void onLocationChanged(Location l) {
        if (l == null) return;
        long agora = System.currentTimeMillis();
        if (aEnviar || agora - ultimoEnvio < RITMO_MS) return;
        ultimoEnvio = agora;
        aEnviar = true;
        final double lat = l.getLatitude(), lon = l.getLongitude();
        final Float prec = l.hasAccuracy() ? l.getAccuracy() : null;
        rede.execute(() -> { try { enviar(lat, lon, prec); } finally { aEnviar = false; } });
    }

    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
    @Override @SuppressWarnings("deprecation") public void onStatusChanged(String p, int s, android.os.Bundle b) {}

    // ---------------------------------------------------------------- servidor
    private void enviar(double lat, double lon, Float prec) {
        String corpo = String.format(Locale.ROOT, "{\"id\":%d,\"lat\":%.7f,\"lon\":%.7f,\"precisao\":%s}", id, lat, lon,
            prec == null ? "null" : String.format(Locale.ROOT, "%.0f", prec));
        int codigo; String resposta = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(origem + "/api/encontros/posicao").openConnection();
            c.setConnectTimeout(10_000); c.setReadTimeout(15_000);
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setUseCaches(false); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json");
            String biscoitos = CookieManager.getInstance().getCookie(origem);
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
        if (codigo == 404 || codigo == 401 || codigo == 403) { Log.i("GEOREDE", "encontro: acabou ou sem sessão (" + codigo + ")"); stopSelf(); return; }
        if (codigo != 200 || resposta == null) { estado("Sem internet: a tentar outra vez…"); return; }
        try {
            Object j = Json.ler(resposta);
            Object e = j instanceof Map ? ((Map<?, ?>) j).get("encontro") : null;
            if (!(e instanceof Map)) return;
            Map<?, ?> enc = (Map<?, ?>) e;
            if ("terminado".equals(enc.get("estado"))) { Log.i("GEOREDE", "encontro: terminado (" + enc.get("fim") + ")"); stopSelf(); return; }
            Object d = enc.get("distancia");
            if (d instanceof Number) estado("A caminho de " + nome + " · " + metros(((Number) d).doubleValue()));
            else estado("A caminho de " + nome + " · à espera da posição");
        } catch (RuntimeException ex) {
            Log.w("GEOREDE", "encontro: resposta estranha");
        }
    }

    static String metros(double m) {
        if (m < 100) return Math.max(1, Math.round(m / 5) * 5) + " m";
        if (m < 1000) return Math.round(m / 10) * 10 + " m";
        return String.format(Locale.ROOT, m < 10000 ? "%.1f km" : "%.0f km", m / 1000).replace('.', ',');
    }

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
        byte[] b = new byte[4096]; int n;
        try (InputStream i = in) { while ((n = i.read(b)) > 0) { o.write(b, 0, n); if (o.size() > 200_000) break; } }
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- notificação fixa
    private void estado(String texto) {
        if (texto.equals(ultimoTexto)) return;
        ultimoTexto = texto;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) { try { nm.notify(ID, notificacao(texto)); } catch (Exception e) { /* sem permissão */ } }
    }

    private Notification notificacao(String texto) {
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setAction(Intent.ACTION_VIEW);
        abrir.putExtra(GeoredePushServico.EXTRA_URL, "/mapa?encontro=" + id);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, ID, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(GeoredePushServico.iconePequeno(this))
            .setContentTitle("Vamos encontrar-nos")
            .setContentText(texto)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    static void criarCanal(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel c = new NotificationChannel(CANAL, "Encontro em curso", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Manda a tua posição à pessoa com quem te vais encontrar, com o ecrã bloqueado");
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    @Override
    public void onDestroy() {
        ativo = false;
        encontroAtivo = 0;
        if (gps != null) { try { gps.removeUpdates(this); } catch (SecurityException e) { /* nada */ } gps = null; }
        if (trinco != null && trinco.isHeld()) trinco.release();
        trinco = null;
        rede.shutdown();
        super.onDestroy();
    }
}
