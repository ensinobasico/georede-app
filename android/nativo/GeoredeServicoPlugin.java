package com.georede.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * GEOREDE — a página (index.html, servicoNativo) chama isto por Capacitor.Plugins.GeoredeServico.
 * Desde o passo 2b o serviço é arrancado pelo GeoredeRadio.ligar; aqui `iniciar` só muda o texto da notificação
 * (e arranca o serviço, para a app antiga sem ligação nativa) e `parar` desliga tudo.
 *
 * 07/11/2026 (Google Play): `pedirSemRestricoes` deixa de usar o pedido direto do Android
 * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS precisa da permissão REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, que a Google
 * só aceita a poucas apps). Abre as definições desta app (Bateria → «Sem restrições»); se não der, a lista geral.
 */
@CapacitorPlugin(name = "GeoredeServico")
public class GeoredeServicoPlugin extends Plugin {

    @PluginMethod
    public void iniciar(PluginCall call) {
        Context c = getContext();
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4752);
        }
        Intent i = new Intent(c, GeoredeServico.class).setAction(GeoredeServico.ACAO_TEXTO);
        i.putExtra("titulo", call.getString("titulo", "GEOREDE"));
        i.putExtra("texto", call.getString("texto", "Ligada ao teu Geo"));
        try {
            ContextCompat.startForegroundService(c, i);
            JSObject r = resumo();
            r.put("ativo", true);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("não consegui manter a ligação em segundo plano: " + e.getMessage());
        }
    }

    @PluginMethod
    public void parar(PluginCall call) {
        GeoredeCentral.obter(getContext()).desligar();
        getContext().stopService(new Intent(getContext(), GeoredeServico.class));
        JSObject r = resumo();
        r.put("ativo", false);
        call.resolve(r);
    }

    @PluginMethod
    public void estado(PluginCall call) {
        call.resolve(resumo());
    }

    /** Abre as definições da app para a pessoa a tirar da poupança de bateria (Bateria → «Sem restrições»). */
    @PluginMethod
    public void pedirSemRestricoes(PluginCall call) {
        Context c = getContext();
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            JSObject r = resumo();
            r.put("aberto", "app");
            call.resolve(r);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                JSObject r = resumo();
                r.put("aberto", "lista");
                call.resolve(r);
            } catch (Exception e2) {
                call.reject("não consegui abrir as definições da bateria: " + e2.getMessage());
            }
        }
    }

    // ---- Atualizações em direto (a pílula, Android 16+) ----
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

    private JSObject resumo() {
        JSObject r = new JSObject();
        r.put("ativo", GeoredeServico.ativo);
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        r.put("semRestricoes", pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName()));
        return r;
    }
}
