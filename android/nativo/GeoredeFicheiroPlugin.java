package com.georede.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * GEOREDE — guardar um ficheiro que a página gerou (GPX de uma rota, os teus dados em JSON), 07/11/2026.
 * O WebView do Android não descarrega ficheiros: escreve-se na cache da app e abre-se o «Partilhar» do Android
 * (Strava, Komoot, Garmin Connect, Drive, Ficheiros, email…). Usa o FileProvider do Capacitor
 * (`${applicationId}.fileprovider`, res/xml/file_paths.xml com cache-path; o preparar.js confirma-o).
 *
 * Capacitor.Plugins.GeoredeFicheiro.partilhar({ nome: "georede-praia.gpx", tipo: "application/gpx+xml", texto })
 */
@CapacitorPlugin(name = "GeoredeFicheiro")
public class GeoredeFicheiroPlugin extends Plugin {
    static final int TAMANHO_MAX = 20 * 1024 * 1024;

    @PluginMethod
    public void partilhar(PluginCall call) {
        String nome = call.getString("nome", "georede.txt");
        String tipo = call.getString("tipo", "text/plain");
        String texto = call.getString("texto");
        if (texto == null) { call.reject("sem conteúdo"); return; }
        // só letras, números, - _ . (o nome vem da página, mas não sai da pasta da cache)
        nome = nome.replaceAll("[^A-Za-z0-9._-]", "-");
        if (nome.isEmpty() || nome.startsWith(".")) nome = "georede.txt";
        if (!tipo.matches("[a-z]+/[a-z0-9.+-]+")) tipo = "application/octet-stream";
        byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
        if (dados.length > TAMANHO_MAX) { call.reject("ficheiro demasiado grande"); return; }
        Context c = getContext();
        try {
            File pasta = new File(c.getCacheDir(), "partilhar");
            if (!pasta.exists() && !pasta.mkdirs()) { call.reject("sem espaço para o ficheiro"); return; }
            File[] antigos = pasta.listFiles();
            if (antigos != null) {
                long limite = System.currentTimeMillis() - 24L * 3600 * 1000;
                for (File f : antigos) if (f.lastModified() < limite) f.delete();
            }
            File f = new File(pasta, nome);
            try (OutputStream o = new FileOutputStream(f)) { o.write(dados); }
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
            Intent enviar = new Intent(Intent.ACTION_SEND);
            enviar.setType(tipo);
            enviar.putExtra(Intent.EXTRA_STREAM, uri);
            enviar.putExtra(Intent.EXTRA_TITLE, nome);
            enviar.setClipData(ClipData.newRawUri(nome, uri));
            enviar.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent escolher = Intent.createChooser(enviar, nome);
            escolher.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (getActivity() != null) getActivity().startActivity(escolher);
            else { escolher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(escolher); }
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("nome", nome);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("não consegui partilhar o ficheiro: " + e.getMessage());
        }
    }
}
