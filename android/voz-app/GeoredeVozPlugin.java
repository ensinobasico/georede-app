package com.georede.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.RequiresApi;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.mlkit.nl.languageid.IdentifiedLanguage;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * GEOREDE — voz nas Mensagens (13/11/2026). A página chama isto por Capacitor.Plugins.GeoredeVoz.
 *
 *  LER EM VOZ (TextToSpeech do Android, voz instalada no telemóvel, funciona sem Internet):
 *    falar({texto, lingua, fila})  → {ok, semVoz}      fila=true junta à fila; false corta o que está a dizer
 *    calar()                       → {}
 *    evento "fala" {id, estado: "inicio"|"fim"|"erro"}
 *
 *  DITAR (SpeechRecognizer: a voz passa a texto e a mensagem sai como texto normal, pela rádio):
 *    ditar({lingua, soLocal})      → {texto, local}    soLocal=true (por defeito): só o reconhecimento no próprio
 *                                                       telemóvel; se não houver, recusa com o código "sem-local"
 *    acabarDitado()                → {}                pára de ouvir e entrega o que já ouviu
 *    cancelarDitado()              → {}                deita fora
 *    evento "ditado" {texto, parcial: true}            o texto a aparecer enquanto se fala
 *
 *    estado({lingua})              → {fala, ouve, local}
 *    detetarLingua({textos: [...]})  → {linguas: [{lingua: "de", confianca: 0.97}, ...]}   (ML Kit, no telemóvel,
 *                                    sem Internet; "und" quando não sabe). Para ler cada mensagem com a voz da língua
 *                                    dela e para o 🎤 ditar na língua da conversa (13/11/2026).
 *    prepararLingua({lingua})      → {sabe, instalada, suportada, aTransferir, parecidas[]}   (Android 13+)
 *                                    se a língua falta no reconhecimento do telemóvel mas existe, pede ao Android
 *                                    para a transferir (13/11/2026: no telemóvel do David faltava o pt-PT)
 *
 * Nada disto passa pelo servidor da GEOREDE. Com soLocal=false e sem reconhecimento no telemóvel, o Android pode
 * mandar o áudio ao serviço da Google (é por isso que, por defeito, se pede só o local).
 */
@CapacitorPlugin(
    name = "GeoredeVoz",
    permissions = { @Permission(alias = "microfone", strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class GeoredeVozPlugin extends Plugin {
    private final Handler principal = new Handler(Looper.getMainLooper());

    // ---- ler em voz ----
    private TextToSpeech tts;
    private boolean ttsPronto = false;
    private boolean ttsFalhou = false;
    private final ArrayList<PluginCall> aEsperaDaVoz = new ArrayList<>();
    private int contador = 0;

    // ---- língua de cada mensagem ----
    private LanguageIdentifier identificador;

    // ---- ditar ----
    private SpeechRecognizer reconhecedor;
    private PluginCall chamadaDitado;
    private boolean ditadoLocal = false;
    private String ultimoParcial = "";

    @Override
    public void load() {
        tts = new TextToSpeech(getContext(), estado -> {
            ttsPronto = (estado == TextToSpeech.SUCCESS);
            ttsFalhou = !ttsPronto;
            if (ttsPronto) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)   // baixa a música, como o GPS
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { avisar(id, "inicio"); }
                    @Override public void onDone(String id) { avisar(id, "fim"); }
                    @Override public void onError(String id) { avisar(id, "erro"); }
                });
            }
            ArrayList<PluginCall> lista = new ArrayList<>(aEsperaDaVoz);
            aEsperaDaVoz.clear();
            for (PluginCall c : lista) falarAgora(c);
        });
    }

    private void avisar(String id, String estado) {
        JSObject r = new JSObject();
        r.put("id", id);
        r.put("estado", estado);
        notifyListeners("fala", r);
    }

    @PluginMethod
    public void falar(PluginCall call) {
        if (!ttsPronto && !ttsFalhou) { aEsperaDaVoz.add(call); return; }   // a voz ainda está a arrancar
        falarAgora(call);
    }

    private void falarAgora(PluginCall call) {
        if (!ttsPronto) { call.reject("o telemóvel não tem leitor de voz", "sem-voz"); return; }
        String texto = call.getString("texto", "");
        if (texto == null || texto.trim().isEmpty()) { call.resolve(ok(false)); return; }
        int max = TextToSpeech.getMaxSpeechInputLength();
        if (texto.length() > max) texto = texto.substring(0, max);
        Locale l = local(call.getString("lingua", "pt-PT"));
        int r = tts.setLanguage(l);
        boolean semVoz = (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED);
        if (semVoz) {
            // Sem voz para essa língua: tenta só a língua (ex.: "pt" se não houver "pt-PT")
            r = tts.setLanguage(new Locale(l.getLanguage()));
            semVoz = (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED);
        }
        if (semVoz) { JSObject o = ok(false); o.put("semVoz", true); call.resolve(o); return; }
        boolean fila = Boolean.TRUE.equals(call.getBoolean("fila", true));
        String id = "g" + (++contador);
        tts.speak(texto, fila ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, null, id);
        JSObject o = ok(true);
        o.put("id", id);
        call.resolve(o);
    }

    @PluginMethod
    public void calar(PluginCall call) {
        if (tts != null) tts.stop();
        call.resolve();
    }

    // ---- ditar ----

    @PluginMethod
    public void ditar(PluginCall call) {
        if (getPermissionState("microfone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microfone", call, "depoisDoMicrofone");
            return;
        }
        principal.post(() -> comecarDitado(call));
    }

    @PermissionCallback
    private void depoisDoMicrofone(PluginCall call) {
        if (getPermissionState("microfone") == PermissionState.GRANTED) principal.post(() -> comecarDitado(call));
        else call.reject("sem autorização para usar o microfone", "sem-microfone");
    }

    private void comecarDitado(PluginCall call) {
        Context c = getContext();
        if (chamadaDitado != null) { call.reject("já estou a ouvir", "ocupado"); return; }
        if (tts != null) tts.stop();                                   // não se ouvir a si própria
        boolean soLocal = !Boolean.FALSE.equals(call.getBoolean("soLocal", true));
        boolean temLocal = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c);
        try {
            if (temLocal) {
                reconhecedor = SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
                ditadoLocal = true;
            } else if (soLocal) {
                call.reject("este telemóvel não reconhece a voz sem Internet", "sem-local");
                return;
            } else if (SpeechRecognizer.isRecognitionAvailable(c)) {
                reconhecedor = SpeechRecognizer.createSpeechRecognizer(c);
                ditadoLocal = false;
            } else {
                call.reject("este telemóvel não tem reconhecimento de voz", "sem-ditado");
                return;
            }
        } catch (Exception e) {
            call.reject("não consegui ligar o reconhecimento de voz: " + e.getMessage(), "sem-ditado");
            return;
        }
        chamadaDitado = call;
        ultimoParcial = "";
        reconhecedor.setRecognitionListener(new Ouvinte());
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, local(call.getString("lingua", "pt-PT")).toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // Deixar falar com pausas curtas sem cortar logo (o Android pode ignorar)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        try {
            reconhecedor.startListening(i);
        } catch (Exception e) {
            terminar(null, "não consegui começar a ouvir: " + e.getMessage(), "sem-ditado");
        }
    }

    @PluginMethod
    public void acabarDitado(PluginCall call) {
        principal.post(() -> { if (reconhecedor != null) reconhecedor.stopListening(); });
        call.resolve();
    }

    @PluginMethod
    public void cancelarDitado(PluginCall call) {
        principal.post(() -> {
            if (reconhecedor != null) reconhecedor.cancel();
            terminar("", null, null);
        });
        call.resolve();
    }

    @PluginMethod
    public void estado(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        boolean fala = false;
        if (ttsPronto) {
            int d = tts.isLanguageAvailable(local(call.getString("lingua", "pt-PT")));
            fala = d >= TextToSpeech.LANG_AVAILABLE;
        }
        r.put("fala", fala);
        r.put("ouve", SpeechRecognizer.isRecognitionAvailable(c));
        r.put("local", Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c));
        call.resolve(r);
    }

    @PluginMethod
    public void detetarLingua(PluginCall call) {
        List<Object> l;
        try { JSArray a = call.getArray("textos"); l = a == null ? new ArrayList<>() : a.toList(); }
        catch (Exception e) { call.reject("textos inválidos"); return; }
        final int n = Math.min(l.size(), 30);
        if (n == 0) { JSObject r = new JSObject(); r.put("linguas", new JSArray()); call.resolve(r); return; }
        if (identificador == null) identificador = LanguageIdentification.getClient();
        final String[] tags = new String[n];
        final double[] conf = new double[n];
        final int[] faltam = { n };
        for (int i = 0; i < n; i++) {
            final int k = i;
            String t = String.valueOf(l.get(i));
            if (t.length() > 500) t = t.substring(0, 500);
            identificador.identifyPossibleLanguages(t).addOnCompleteListener(tarefa -> {
                String tag = "und";
                double c = 0;
                if (tarefa.isSuccessful() && tarefa.getResult() != null) {
                    for (IdentifiedLanguage x : tarefa.getResult()) {
                        if (x.getConfidence() > c) { c = x.getConfidence(); tag = x.getLanguageTag(); }
                    }
                }
                tags[k] = tag;
                conf[k] = c;
                if (--faltam[0] == 0) {
                    JSArray out = new JSArray();
                    for (int j = 0; j < n; j++) {
                        JSObject o = new JSObject();
                        o.put("lingua", tags[j]);
                        o.put("confianca", conf[j]);
                        out.put(o);
                    }
                    JSObject r = new JSObject();
                    r.put("linguas", out);
                    call.resolve(r);
                }
            });
        }
    }

    @PluginMethod
    public void prepararLingua(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33) { JSObject r = new JSObject(); r.put("sabe", false); call.resolve(r); return; }
        principal.post(() -> {
            try { if (Build.VERSION.SDK_INT >= 33) prepararLingua33(call); }
            catch (Exception e) { JSObject r = new JSObject(); r.put("sabe", false); r.put("erro", String.valueOf(e.getMessage())); call.resolve(r); }
        });
    }

    @RequiresApi(33)
    private void prepararLingua33(PluginCall call) {
        Context c = getContext();
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) { JSObject r = new JSObject(); r.put("sabe", false); call.resolve(r); return; }
        final SpeechRecognizer sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
        final String tag = local(call.getString("lingua", "pt-PT")).toLanguageTag();
        final Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        sr.checkRecognitionSupport(i, c.getMainExecutor(), new RecognitionSupportCallback() {
            @Override public void onSupportResult(RecognitionSupport s) {
                List<String> inst = s.getInstalledOnDeviceLanguages(), sup = s.getSupportedOnDeviceLanguages(), pend = s.getPendingOnDeviceLanguages();
                boolean instalada = contem(inst, tag), suportada = contem(sup, tag) || instalada, pendente = contem(pend, tag);
                boolean pediu = false;
                if (!instalada && suportada && !pendente) {
                    try { sr.triggerModelDownload(i); pediu = true; } catch (Exception ignorar) {}
                }
                LinkedHashSet<String> todas = new LinkedHashSet<>();
                if (inst != null) todas.addAll(inst);
                if (sup != null) todas.addAll(sup);
                JSArray parecidas = new JSArray();
                String base = tag.length() >= 2 ? tag.substring(0, 2).toLowerCase(Locale.ROOT) : tag;
                for (String l : todas) if (l != null && l.toLowerCase(Locale.ROOT).startsWith(base)) parecidas.put(l);
                JSObject r = new JSObject();
                r.put("sabe", true); r.put("instalada", instalada); r.put("suportada", suportada);
                r.put("aTransferir", pendente || pediu); r.put("parecidas", parecidas);
                try { sr.destroy(); } catch (Exception ignorar) {}
                call.resolve(r);
            }
            @Override public void onError(int erro) {
                try { sr.destroy(); } catch (Exception ignorar) {}
                JSObject r = new JSObject(); r.put("sabe", false); r.put("erro", erro);
                call.resolve(r);
            }
        });
    }

    private static boolean contem(List<String> l, String tag) {
        if (l == null) return false;
        String t = tag.replace('_', '-');
        for (String x : l) if (x != null && x.replace('_', '-').equalsIgnoreCase(t)) return true;
        return false;
    }

    /** Fecha o ditado: texto != null resolve; senão recusa com a mensagem e o código. */
    private void terminar(String texto, String erro, String codigo) {
        PluginCall c = chamadaDitado;
        chamadaDitado = null;
        if (reconhecedor != null) { try { reconhecedor.destroy(); } catch (Exception ignorar) {} reconhecedor = null; }
        if (c == null) return;
        if (texto != null) {
            JSObject r = new JSObject();
            r.put("texto", texto);
            r.put("local", ditadoLocal);
            c.resolve(r);
        } else {
            c.reject(erro, codigo);
        }
    }

    private class Ouvinte implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) { notifyListeners("ditado", estadoDitado("a-ouvir")); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() { notifyListeners("ditado", estadoDitado("a-pensar")); }
        @Override public void onEvent(int t, Bundle b) {}

        @Override public void onPartialResults(Bundle b) {
            String t = primeiro(b);
            if (t == null || t.isEmpty()) return;
            ultimoParcial = t;
            JSObject r = new JSObject();
            r.put("texto", t);
            r.put("parcial", true);
            notifyListeners("ditado", r);
        }

        @Override public void onResults(Bundle b) {
            String t = primeiro(b);
            terminar(t != null && !t.isEmpty() ? t : ultimoParcial, null, null);
        }

        @Override public void onError(int erro) {
            switch (erro) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    terminar(ultimoParcial, null, null);                     // não ouviu nada: texto vazio
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    terminar(null, "sem autorização para usar o microfone", "sem-microfone"); break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    terminar(null, "o reconhecimento de voz precisa de Internet", "sem-rede"); break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    terminar(null, "falta a língua no reconhecimento de voz do telemóvel", "sem-lingua"); break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    terminar(null, "o microfone está ocupado", "ocupado"); break;
                default:
                    terminar(null, "o reconhecimento de voz falhou (" + erro + ")", "erro");
            }
        }
    }

    private JSObject estadoDitado(String e) {
        JSObject r = new JSObject();
        r.put("estado", e);
        return r;
    }

    private static String primeiro(Bundle b) {
        if (b == null) return null;
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }

    private static Locale local(String tag) {
        if (tag == null || !tag.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")) tag = "pt-PT";
        return Locale.forLanguageTag(tag);
    }

    private static JSObject ok(boolean v) {
        JSObject o = new JSObject();
        o.put("ok", v);
        return o;
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (identificador != null) { try { identificador.close(); } catch (Exception ignorar) {} identificador = null; }
        principal.post(() -> { if (reconhecedor != null) { reconhecedor.destroy(); reconhecedor = null; } });
        super.handleOnDestroy();
    }
}
