package com.georede.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * GEOREDE — a página (index.html, bloco "notificações") chama isto por Capacitor.Plugins.GeoredePush em vez do
 * Web Push (que não existe no WebView):
 *   subscrever()  pede a permissão de notificações, cria as chaves deste telemóvel (se ainda não há) e pede o token
 *                 ao Firebase -> {endpoint: "fcm-android:<token>", keys: {p256dh, auth}}, no formato de uma
 *                 PushSubscription, para a página o mandar a POST /api/push/subscrever tal e qual.
 *   cancelar()    apaga as chaves e o token (-> {endpoint} do que existia, para a página o cancelar no servidor)
 *   estado()      {subscrito, autorizadas, mudou (o Firebase trocou o token: voltar a subscrever), endpoint}
 */
@CapacitorPlugin(name = "GeoredePush", permissions = {
    @Permission(strings = {Manifest.permission.POST_NOTIFICATIONS}, alias = "notificacoes")
})
public class GeoredePushPlugin extends Plugin {

    @PluginMethod
    public void estado(PluginCall call) {
        call.resolve(resumo());
    }

    @PluginMethod
    public void subscrever(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notificacoes") != PermissionState.GRANTED) {
            requestPermissionForAlias("notificacoes", call, "depoisDaPermissao");
            return;
        }
        continuar(call);
    }

    @PermissionCallback
    private void depoisDaPermissao(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notificacoes") != PermissionState.GRANTED) {
            JSObject r = resumo();
            r.put("recusada", true);
            call.resolve(r);
            return;
        }
        continuar(call);
    }

    private void continuar(PluginCall call) {
        SharedPreferences p = GeoredePushServico.prefs(getContext());
        try {
            if (p.getString("privada", null) == null) {
                PushCifra.Chaves c = PushCifra.novasChaves();
                p.edit().putString("privada", Base64Simples.codificar(c.privadaPkcs8))
                    .putString("publica", Base64Simples.codificar(c.publica))
                    .putString("auth", Base64Simples.codificar(c.auth)).apply();
            }
        } catch (Exception e) {
            call.reject("não consegui criar as chaves das notificações: " + e.getMessage());
            return;
        }
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult() == null || t.getResult().isEmpty()) {
                Exception e = t.getException();
                call.reject("o Firebase não deu o token: " + (e == null ? "?" : e.getMessage()));
                return;
            }
            p.edit().putString("token", t.getResult()).putBoolean("mudou", false).apply();
            GeoredePushServico.criarCanal(getContext());
            JSObject r = resumo();
            JSObject chaves = new JSObject();
            chaves.put("p256dh", PushCifra.b64u(Base64Simples.descodificar(p.getString("publica", ""))));
            chaves.put("auth", PushCifra.b64u(Base64Simples.descodificar(p.getString("auth", ""))));
            r.put("keys", chaves);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void cancelar(PluginCall call) {
        SharedPreferences p = GeoredePushServico.prefs(getContext());
        String token = p.getString("token", null);
        p.edit().clear().apply();
        FirebaseMessaging.getInstance().deleteToken();
        JSObject r = resumo();
        if (token != null) r.put("endpoint", "fcm-android:" + token);
        call.resolve(r);
    }

    private JSObject resumo() {
        SharedPreferences p = GeoredePushServico.prefs(getContext());
        String token = p.getString("token", null);
        boolean subscrito = token != null && p.getString("privada", null) != null;
        JSObject r = new JSObject();
        r.put("subscrito", subscrito);
        r.put("autorizadas", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        r.put("mudou", p.getBoolean("mudou", false));
        if (subscrito) r.put("endpoint", "fcm-android:" + token);
        return r;
    }
}
