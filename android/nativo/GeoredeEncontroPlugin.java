package com.georede.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * GEOREDE — a página chama isto por Capacitor.Plugins.GeoredeEncontro quando um encontro é aceite (e no fim):
 *   iniciar({id, origem, nome})  pede a localização se faltar e arranca o GeoredeEncontroServico (posição com o ecrã bloqueado)
 *   parar()                      pára o serviço
 *   estado()                     -> {ativo, id}
 */
@CapacitorPlugin(name = "GeoredeEncontro", permissions = {
    @Permission(strings = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, alias = "localizacao")
})
public class GeoredeEncontroPlugin extends Plugin {

    @PluginMethod
    public void iniciar(PluginCall call) {
        if (getPermissionState("localizacao") != PermissionState.GRANTED) {
            requestPermissionForAlias("localizacao", call, "depoisDaPermissao");
            return;
        }
        arrancar(call);
    }

    @PermissionCallback
    private void depoisDaPermissao(PluginCall call) {
        if (getPermissionState("localizacao") != PermissionState.GRANTED) {
            JSObject r = resumo();
            r.put("recusada", true);
            call.resolve(r);
            return;
        }
        arrancar(call);
    }

    private void arrancar(PluginCall call) {
        Integer id = call.getInt("id");
        if (id == null || id <= 0) { call.reject("falta o id do encontro"); return; }
        Context c = getContext();
        Intent i = new Intent(c, GeoredeEncontroServico.class).setAction(GeoredeEncontroServico.ACAO_INICIAR);
        i.putExtra("id", (long) id);
        i.putExtra("origem", call.getString("origem", ""));
        i.putExtra("nome", call.getString("nome", ""));
        try {
            ContextCompat.startForegroundService(c, i);
            JSObject r = resumo();
            r.put("ativo", true);
            r.put("id", id);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("não consegui manter o encontro em segundo plano: " + e.getMessage());
        }
    }

    @PluginMethod
    public void parar(PluginCall call) {
        getContext().stopService(new Intent(getContext(), GeoredeEncontroServico.class));
        JSObject r = resumo();
        r.put("ativo", false);
        call.resolve(r);
    }

    @PluginMethod
    public void estado(PluginCall call) {
        call.resolve(resumo());
    }

    private JSObject resumo() {
        JSObject r = new JSObject();
        r.put("ativo", GeoredeEncontroServico.ativo);
        r.put("id", GeoredeEncontroServico.encontroAtivo);
        return r;
    }
}
