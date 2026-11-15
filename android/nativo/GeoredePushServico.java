package com.georede.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GEOREDE — notificações na app Android (06/11/2026). O Firebase (FCM) acorda a app, mesmo fechada, com um aviso
 * que o servidor cifrou só para este telemóvel (push.py + fcm.py, RFC 8291): o campo `c` traz bytes opacos.
 * Aqui decifra-se com as chaves guardadas pelo GeoredePushPlugin e mostra-se a notificação, como o sw.js faz no
 * Chrome: {titulo, corpo, url, tag}. Um toque abre a app nessa secção (MainActivity, extra "georede_url").
 */
public class GeoredePushServico extends FirebaseMessagingService {
    static final String PREFS = "georede-push";
    static final String CANAL = "georede-avisos";
    static final String EXTRA_URL = "georede_url";

    @Override
    public void onNewToken(String token) {
        // O Firebase trocou o token: a página volta a subscrever da próxima vez que abrir (plugin.estado -> mudou)
        prefs(this).edit().putString("token", token).putBoolean("mudou", true).apply();
        Log.i("GEOREDE", "notificações: token novo do Firebase");
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        Map<String, String> dados = m.getData();
        String c = dados == null ? null : dados.get("c");
        if (c == null || c.isEmpty()) return;
        String titulo = "GEOREDE", corpo = "Tens novidades na GEOREDE.", url = "/alertas", tag = null;
        try {
            SharedPreferences p = prefs(this);
            String priv = p.getString("privada", null), pub = p.getString("publica", null), auth = p.getString("auth", null);
            if (priv == null || pub == null || auth == null) throw new IllegalStateException("sem chaves");
            byte[] texto = PushCifra.decifrar(PushCifra.deB64u(c), Base64Simples.descodificar(priv),
                Base64Simples.descodificar(pub), Base64Simples.descodificar(auth));
            Object j = Json.ler(new String(texto, StandardCharsets.UTF_8));
            if (j instanceof Map) {
                Map<?, ?> a = (Map<?, ?>) j;
                if (a.get("titulo") instanceof String && !((String) a.get("titulo")).isEmpty()) titulo = (String) a.get("titulo");
                if (a.get("corpo") instanceof String) corpo = (String) a.get("corpo");
                if (a.get("url") instanceof String && ((String) a.get("url")).startsWith("/")) url = (String) a.get("url");
                if (a.get("tag") instanceof String && !((String) a.get("tag")).isEmpty()) tag = (String) a.get("tag");
            }
        } catch (Exception e) {
            // Chaves trocadas ou aviso de outra subscrição: mostra-se um aviso genérico (nunca fica mudo)
            Log.w("GEOREDE", "notificação que não consegui decifrar: " + e.getMessage());
        }
        mostrar(this, titulo, corpo, url, tag);
    }

    static void mostrar(Context ctx, String titulo, String corpo, String url, String tag) {
        criarCanal(ctx);
        Intent abrir = new Intent(ctx, MainActivity.class);
        abrir.setAction(Intent.ACTION_VIEW);
        abrir.putExtra(EXTRA_URL, url);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int id = tag != null ? tag.hashCode() : (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(iconePequeno(ctx))
            .setContentTitle(titulo)
            .setContentText(corpo)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(corpo))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            try { nm.notify(id, n); } catch (Exception e) { Log.w("GEOREDE", "sem permissão de notificações"); }
        }
    }

    /** O G branco da barra de notificações (res/drawable/ic_georede_notificacao.png, posto pelo preparar.js). */
    static int iconePequeno(Context ctx) {
        int id = ctx.getResources().getIdentifier("ic_georede_notificacao", "drawable", ctx.getPackageName());
        return id != 0 ? id : android.R.drawable.ic_dialog_info;
    }

    static void criarCanal(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel c = new NotificationChannel(CANAL, "Avisos", NotificationManager.IMPORTANCE_HIGH);
        c.setDescription("Zonas de alerta, sem sinal, bateria, mensagens e encontros");
        nm.createNotificationChannel(c);
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
