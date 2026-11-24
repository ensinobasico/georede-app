package com.georede.app;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceController;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

/**
 * GEOREDE — atualizar o programa (firmware Meshtastic) de um Geo por Bluetooth, 18/11/2026.
 * Só para Geos com processador nRF52 (T1000-E, Wio Tracker L1, Solar Node, Heltec T096/T114, RAK4631…): o programa
 * do Geo tem o serviço de atualização da Nordic e o arranque (bootloader) recebe o pacote "-ota.zip".
 *
 * A página (app.georede.com) escolhe o pacote certo no índice do servidor, descarrega-o, confere-o e larga a ligação
 * ao Geo; depois chama:
 *   Capacitor.Plugins.GeoredeDfu.iniciar({ endereco, nome, zip (base64), sha256, bytes, modoDfu })
 *   Capacitor.Plugins.GeoredeDfu.estado()    → { fase, pct, mensagem, entrouDfu }
 *   Capacitor.Plugins.GeoredeDfu.cancelar()
 * fase: parado | a_ligar | modo_dfu | a_enviar | a_validar | feito | erro | cancelado
 * A página pergunta o estado de segundo a segundo (não depende de eventos: com o ecrã bloqueado o Android congela a
 * página, e ao voltar ela lê onde a atualização vai). A transferência corre no serviço GeoredeDfuServico.
 *
 * modoDfu=true: o Geo já está em modo de atualização (uma tentativa anterior ficou a meio); nesse modo anuncia-se
 * com o endereço seguinte (último byte mais um).
 *
 * Definições da transferência: as que a documentação do arranque do Meshtastic recomenda (confirmação de receção a
 * cada 10 pacotes, sem pedir MTU maior, sem guardar o emparelhamento).
 */
@CapacitorPlugin(name = "GeoredeDfu")
public class GeoredeDfuPlugin extends Plugin {
    private final Object tranca = new Object();
    private String fase = "parado";
    private int pct = 0;
    private String mensagem = "";
    private boolean entrouDfu = false;
    private DfuServiceController controlo = null;

    private final DfuProgressListener ouvinte = new DfuProgressListenerAdapter() {
        @Override public void onDeviceConnecting(String e) { por("a_ligar", 0, ""); }
        @Override public void onDfuProcessStarting(String e) { por("a_ligar", 0, ""); }
        @Override public void onEnablingDfuMode(String e) { marcarDfu(); por("modo_dfu", 0, ""); }
        @Override public void onDfuProcessStarted(String e) { marcarDfu(); por("a_enviar", 0, ""); }
        @Override public void onProgressChanged(String e, int percent, float speed, float avgSpeed, int currentPart, int partsTotal) {
            marcarDfu();
            por("a_enviar", Math.max(0, Math.min(100, percent)), "");
        }
        @Override public void onFirmwareValidating(String e) { por("a_validar", 100, ""); }
        @Override public void onDfuCompleted(String e) { por("feito", 100, ""); limpar(); }
        @Override public void onDfuAborted(String e) { por("cancelado", 0, ""); limpar(); }
        @Override public void onError(String e, int error, int errorType, String message) {
            por("erro", 0, (message == null ? "" : message) + " (" + error + ")");
            limpar();
        }
    };

    @Override
    public void load() {
        DfuServiceListenerHelper.registerProgressListener(getContext(), ouvinte);
    }

    @Override
    protected void handleOnDestroy() {
        try { DfuServiceListenerHelper.unregisterProgressListener(getContext(), ouvinte); } catch (Exception ex) { /* nada */ }
        super.handleOnDestroy();
    }

    private void marcarDfu() { synchronized (tranca) { entrouDfu = true; } }

    private void por(String f, int p, String m) {
        synchronized (tranca) { fase = f; pct = p; mensagem = m == null ? "" : m; }
    }

    private void limpar() {
        synchronized (tranca) { controlo = null; }
        try {
            File f = ficheiro(getContext());
            if (f.exists()) f.delete();
        } catch (Exception ex) { /* fica para a próxima */ }
    }

    static File ficheiro(Context c) { return new File(new File(c.getCacheDir(), "dfu"), "programa-ota.zip"); }

    private boolean emCurso() {
        synchronized (tranca) {
            return "a_ligar".equals(fase) || "modo_dfu".equals(fase) || "a_enviar".equals(fase) || "a_validar".equals(fase);
        }
    }

    @PluginMethod
    public void iniciar(PluginCall call) {
        if (emCurso()) { call.reject("já há uma atualização a decorrer"); return; }
        String endereco = GeoredeDfuNucleo.endereco(call.getString("endereco"));
        if (endereco == null) { call.reject("endereço do Geo inválido"); return; }
        boolean modoDfu = Boolean.TRUE.equals(call.getBoolean("modoDfu", false));
        String alvo = modoDfu ? GeoredeDfuNucleo.enderecoDfu(endereco) : endereco;
        String nome = call.getString("nome", "Geo");
        String b64 = call.getString("zip");
        if (b64 == null || b64.isEmpty()) { call.reject("falta o programa"); return; }
        byte[] dados;
        try { dados = Base64.decode(b64, Base64.DEFAULT); }
        catch (Exception ex) { call.reject("o programa chegou estragado"); return; }
        Integer bytes = call.getInt("bytes", 0);
        String motivo = GeoredeDfuNucleo.conferir(dados, call.getString("sha256"), bytes == null ? 0 : bytes);
        if (motivo != null) { call.reject(motivo); return; }

        Context c = getContext();
        File f = ficheiro(c);
        try {
            File pasta = f.getParentFile();
            if (pasta != null && !pasta.exists() && !pasta.mkdirs()) { call.reject("sem espaço para o programa"); return; }
            try (OutputStream o = new FileOutputStream(f)) { o.write(dados); }
        } catch (Exception ex) {
            call.reject("não consegui guardar o programa: " + ex.getMessage());
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) DfuServiceInitiator.createDfuNotificationChannel(c);
            DfuServiceInitiator ini = new DfuServiceInitiator(alvo)
                    .setDeviceName(nome)
                    .setKeepBond(false)
                    .setForceDfu(false)
                    .setPacketsReceiptNotificationsEnabled(true)
                    .setPacketsReceiptNotificationsValue(10)
                    .setForceScanningForNewAddressInLegacyDfu(true)
                    .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(false)
                    .setNumberOfRetries(2)
                    .disableMtuRequest()
                    .setForeground(true)
                    .setDisableNotification(false);
            ini.setZip(f.getAbsolutePath());
            synchronized (tranca) { fase = "a_ligar"; pct = 0; mensagem = ""; entrouDfu = modoDfu; }
            DfuServiceController ctl = ini.start(c, GeoredeDfuServico.class);
            synchronized (tranca) { controlo = ctl; }
        } catch (Exception ex) {
            por("erro", 0, String.valueOf(ex.getMessage()));
            limpar();
            call.reject("não consegui começar a atualização: " + ex.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("ok", true);
        r.put("endereco", alvo);
        call.resolve(r);
    }

    @PluginMethod
    public void estado(PluginCall call) {
        JSObject r = new JSObject();
        synchronized (tranca) {
            r.put("fase", fase);
            r.put("pct", pct);
            r.put("mensagem", mensagem);
            r.put("entrouDfu", entrouDfu);
        }
        call.resolve(r);
    }

    @PluginMethod
    public void cancelar(PluginCall call) {
        DfuServiceController ctl;
        synchronized (tranca) { ctl = controlo; }
        try { if (ctl != null) ctl.abort(); } catch (Exception ex) { /* nada */ }
        call.resolve();
    }
}
