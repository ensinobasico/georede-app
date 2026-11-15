package com.georede.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GEOREDE — a página fala com a ligação nativa ao Geo (passo 2b). Capacitor.Plugins.GeoredeRadio:
 *   ligar({deviceId, nome, origem, ponte})  arranca o serviço e liga; resolve quando o Geo está pronto (até 20 s)
 *   desligar()                              desliga e esquece o Geo neste serviço (sair da conta, botão Desligar)
 *   escrever({value})                       ToRadio em hexadecimal
 *   ler({max})                              -> {pacotes: [hex…], porLer}  o que chegou do Geo (também enquanto a página dormia)
 *   ponte({ligada})                         liga ou desliga a ponte (modo de emergência, sem conta)
 *   estado()                                -> {radio, ponte, estado, enviados, recebidos, porEnviar, …}
 * Eventos: "dados" (há pacotes para ler), "estado" (mudou o rádio ou a ponte), "desligado".
 */
@CapacitorPlugin(name = "GeoredeRadio")
public class GeoredeRadioPlugin extends Plugin implements GeoredeCentral.Ouvinte {
    private GeoredeCentral central;
    private final Handler principal = new Handler(Looper.getMainLooper());
    private final List<PluginCall> aEsperar = new ArrayList<>();
    private String ultimoRadio = "desligado";

    @Override
    public void load() {
        central = GeoredeCentral.obter(getContext());
        central.juntar(this);
        ultimoRadio = central.estadoRadio();
    }

    @Override
    protected void handleOnDestroy() {
        if (central != null) central.largar(this);
    }

    @PluginMethod
    public void ligar(PluginCall call) {
        String id = call.getString("deviceId");
        if (id == null || id.isEmpty()) { call.reject("falta o deviceId"); return; }
        Context c = getContext();
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4752);
        }
        Intent i = new Intent(c, GeoredeServico.class).setAction(GeoredeServico.ACAO_LIGAR);
        i.putExtra("endereco", id);
        i.putExtra("nome", call.getString("nome", ""));
        i.putExtra("origem", call.getString("origem", ""));
        i.putExtra("ponte", Boolean.TRUE.equals(call.getBoolean("ponte", true)));
        try {
            ContextCompat.startForegroundService(c, i);
        } catch (Exception e) {
            call.reject("não consegui manter a ligação em segundo plano: " + e.getMessage());
            return;
        }
        if ("ligado".equals(central.estadoRadio()) && id.equalsIgnoreCase(String.valueOf(central.resumo().get("endereco")))) {
            call.resolve(resumoJs());
            return;
        }
        call.setKeepAlive(true);
        synchronized (aEsperar) { aEsperar.add(call); }
        principal.postDelayed(() -> {
            boolean ainda;
            synchronized (aEsperar) { ainda = aEsperar.remove(call); }
            if (ainda) { call.setKeepAlive(false); call.reject("GATT: o Geo não respondeu (connect)"); }
        }, GeoredeRadio.TEMPO_LIGAR_MS + 2000);
    }

    @PluginMethod
    public void desligar(PluginCall call) {
        central.desligar();
        getContext().stopService(new Intent(getContext(), GeoredeServico.class));
        call.resolve(resumoJs());
    }

    @PluginMethod
    public void escrever(PluginCall call) {
        byte[] v;
        try { v = PonteNucleo.deHex(call.getString("value", "")); }
        catch (IllegalArgumentException e) { call.reject("valor em hexadecimal inválido"); return; }
        central.escrever(v, (ok, erro) -> { if (ok) call.resolve(); else call.reject(erro == null ? "a escrita falhou" : erro); });
    }

    @PluginMethod
    public void ler(PluginCall call) {
        int max = call.getInt("max", 64);
        List<byte[]> l = central.ler(Math.max(1, Math.min(max, 500)));
        JSArray a = new JSArray();
        for (byte[] b : l) a.put(PonteNucleo.hex(b));
        JSObject r = new JSObject();
        r.put("pacotes", a);
        r.put("porLer", central.nucleo.porLer());
        r.put("perdidos", central.nucleo.perdidosPagina());
        call.resolve(r);
    }

    @PluginMethod
    public void ponte(PluginCall call) {
        central.ponte(Boolean.TRUE.equals(call.getBoolean("ligada", true)));
        call.resolve(resumoJs());
    }

    @PluginMethod
    public void nome(PluginCall call) {
        central.nome(call.getString("nome", ""));
        call.resolve(resumoJs());
    }

    @PluginMethod
    public void estado(PluginCall call) {
        call.resolve(resumoJs());
    }

    private JSObject resumoJs() { return paraJs(central.resumo()); }

    private static JSObject paraJs(Map<String, Object> m) {
        JSObject o = new JSObject();
        for (Map.Entry<String, Object> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        return o;
    }

    // ---------------------------------------------------------------- do centro
    @Override
    public void estado(Map<String, Object> r) {
        String radio = String.valueOf(r.get("radio"));
        principal.post(() -> {
            if ("ligado".equals(radio)) {
                List<PluginCall> l;
                synchronized (aEsperar) { l = new ArrayList<>(aEsperar); aEsperar.clear(); }
                for (PluginCall c : l) { c.setKeepAlive(false); c.resolve(paraJs(r)); }
            }
            boolean caiu = "ligado".equals(ultimoRadio) && !"ligado".equals(radio);
            ultimoRadio = radio;
            notifyListeners("estado", paraJs(r));
            if (caiu) notifyListeners("desligado", paraJs(r));
        });
    }

    @Override
    public void haDados() {
        principal.post(() -> notifyListeners("dados", new JSObject()));
    }
}
