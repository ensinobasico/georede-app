package com.georede.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.util.Map;

/**
 * GEOREDE — serviço em primeiro plano (tipo connectedDevice) enquanto a app está ligada a um Geo por Bluetooth.
 * Passo 2b (06/11/2026): é aqui que vive a ligação ao Geo e a ponte para o servidor (GeoredeCentral), por isso
 * continuam com o ecrã bloqueado mesmo que o Android congele a página. A notificação fixa acompanha o estado sozinha.
 *
 * Pedidos (Intent):
 *   ACAO_LIGAR  endereco, nome, origem, ponte -> liga ao Geo (plugin GeoredeRadio.ligar)
 *   ACAO_TEXTO  titulo, texto                 -> só muda a notificação (plugin GeoredeServico.iniciar, como no passo 2a)
 *   sem Intent  o Android reiniciou o serviço  -> volta a ligar ao último Geo guardado
 */
public class GeoredeServico extends Service implements GeoredeCentral.Ouvinte {
    static final String CANAL = "georede-ligacao";
    static final int ID = 4752;
    static final String ACAO_LIGAR = "com.georede.app.LIGAR";
    static final String ACAO_TEXTO = "com.georede.app.TEXTO";
    static final String ACAO_PILULA = "com.georede.app.PILULA";     // botão da notificação: tira ou põe a pílula
    static volatile boolean ativo = false;
    private PowerManager.WakeLock trinco;
    private GeoredeCentral central;
    private String titulo = "GEOREDE", texto = "A ligar ao teu Geo…", ultimoTexto = null;
    private String pilula = "A ligar";     // texto curto da pílula (Android 16+): menos de 7 caracteres aparece inteiro

    // 11/11/2026: com o Bluetooth do telemóvel desligado não faz sentido dizer "à procura do teu Geo".
    // O serviço ouve o Bluetooth ligar e desligar: desligado -> diz isso; ligado outra vez -> volta logo ao Geo.
    private volatile boolean btDesligado = false;
    private boolean receptorRegistado = false;
    private final BroadcastReceiver receptorBt = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())) return;
            int e = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (e == BluetoothAdapter.STATE_OFF || e == BluetoothAdapter.STATE_TURNING_OFF) {
                if (btDesligado) return;
                btDesligado = true;
                Log.i("GEOREDE", "Bluetooth do telemóvel desligado");
            } else if (e == BluetoothAdapter.STATE_ON) {
                if (!btDesligado) return;
                btDesligado = false;
                Log.i("GEOREDE", "Bluetooth do telemóvel ligado: volta ao Geo já");
                if (central != null) central.religarGuardado();     // sem esperar pela próxima tentativa (até 60 s)
            } else {
                return;
            }
            ultimoTexto = null;
            if (central != null) estado(central.resumo());
        }
    };

    private boolean bluetoothLigado() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            return a != null && a.isEnabled();
        } catch (Exception e) {
            return true;                               // na dúvida, comporta-se como antes
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        central = GeoredeCentral.obter(this);
        central.juntar(this);
        btDesligado = !bluetoothLigado();
        try {
            ContextCompat.registerReceiver(this, receptorBt, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
            receptorRegistado = true;
        } catch (Exception e) {
            Log.w("GEOREDE", "não consegui ouvir o estado do Bluetooth", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String acao = intent == null ? null : intent.getAction();
        if (ACAO_PILULA.equals(acao)) { alternarPilula(); return START_STICKY; }
        if (intent != null && intent.getStringExtra("titulo") != null) titulo = intent.getStringExtra("titulo");
        if (intent != null && intent.getStringExtra("texto") != null) texto = intent.getStringExtra("texto");
        if (!primeiroPlano()) return START_NOT_STICKY;
        segurarTrinco();
        ativo = true;
        if (ACAO_LIGAR.equals(acao)) {
            central.ligar(intent.getStringExtra("endereco"), intent.getStringExtra("nome"),
                intent.getStringExtra("origem"), intent.getBooleanExtra("ponte", true));
        } else if (intent == null) {
            // O Android matou a app e reiniciou só o serviço: a ponte continua sem a página
            if (!central.religarGuardado()) { stopSelf(); return START_NOT_STICKY; }
        }
        estado(central.resumo());
        return START_STICKY;       // se o Android o parar por falta de memória, volta e religa ao último Geo
    }

    // A pílula pode ser tirada e posta pelo botão da notificação; a escolha fica guardada (por defeito, ligada)
    // O telemóvel tem a pílula? (os métodos só existem no Android 16 atualizado, SDK 36.1)
    private static Boolean temPilula = null;
    private static boolean pilulaSuportada() {
        if (temPilula == null) {
            try {
                Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class);
                temPilula = true;
            } catch (Throwable e) {
                temPilula = false;
            }
        }
        return temPilula;
    }

    private boolean pilulaLigada() {
        return getSharedPreferences("georede_pilula", Context.MODE_PRIVATE).getBoolean("ligada", true);
    }

    private void alternarPilula() {
        getSharedPreferences("georede_pilula", Context.MODE_PRIVATE).edit().putBoolean("ligada", !pilulaLigada()).apply();
        ultimoTexto = null;                        // força a notificação a ser refeita
        estado(central.resumo());
    }

    private boolean primeiroPlano() {
        try {
            criarCanal();
            int tipo = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
            ServiceCompat.startForeground(this, ID, notificacao(texto), tipo);
            ultimoTexto = texto + "|" + pilula;
            return true;
        } catch (Exception e) {
            Log.e("GEOREDE", "não consegui pôr o serviço em primeiro plano", e);
            ativo = false;
            stopSelf();
            return false;
        }
    }

    private void segurarTrinco() {
        if (trinco == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                trinco = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "georede:ligacao");
                trinco.setReferenceCounted(false);
            }
        }
        if (trinco != null) trinco.acquire(12L * 60 * 60 * 1000);      // no máximo 12 h seguidas; renova a cada pedido
    }

    private Notification notificacao(String t) {
        Intent abrir = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = abrir == null ? null
            : PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16+: Live Update. A notificação fixa passa a aparecer também como pílula na barra de estado
            // (junto à câmara), com o ícone G e o texto curto. Em versões antigas fica a notificação de sempre.
            Notification.Builder b = new Notification.Builder(this, CANAL)
                .setSmallIcon(GeoredePushServico.iconePequeno(this))
                .setContentTitle(titulo)
                .setContentText(t)
                .setStyle(new Notification.BigTextStyle().bigText(t))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
            boolean com = pilulaLigada();
            if (com && pilulaSuportada()) {
                // por reflexão: estes métodos só existem no SDK 36.1
                try {
                    Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(b, true);
                    Notification.Builder.class.getMethod("setShortCriticalText", CharSequence.class).invoke(b, pilula);
                } catch (Throwable e) { android.util.Log.w("GeoredeServico", "pílula não disponível: " + e); }
            }
            PendingIntent alt = PendingIntent.getService(this, 1,
                new Intent(this, GeoredeServico.class).setAction(ACAO_PILULA),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            if (pilulaSuportada()) b.addAction(new Notification.Action.Builder(0, com ? "Tirar a pílula" : "Pôr a pílula", alt).build());
            if (pi != null) b.setContentIntent(pi);
            return b.build();
        }
        return new NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(titulo)
            .setContentText(t)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(t))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    // A notificação segue o estado do Geo e da ponte, mesmo com a página congelada
    @Override
    public void estado(Map<String, Object> r) {
        String n = String.valueOf(r.get("nome"));
        if (n.isEmpty() || "null".equals(n)) n = "Geo";
        String radio = String.valueOf(r.get("radio"));
        String t;
        if ("ligado".equals(radio)) {
            t = "Ligada ao teu Geo " + n;
            String p = String.valueOf(r.get("estado"));
            if (Boolean.TRUE.equals(r.get("ponte"))) {
                if ("ligada".equals(p)) t += " · ponte para o servidor ligada";
                else if (!"a ligar".equals(p) && !"à espera do Geo".equals(p)) t += " · " + p;
            }
        } else if ("desligado".equals(radio)) {
            return;                                   // quem desliga pára o serviço
        } else if (btDesligado) {
            t = "Bluetooth desligado. Liga-o para a GEOREDE voltar ao teu Geo " + n + ".";
        } else {
            t = "À procura do teu Geo " + n + " (fora do alcance do Bluetooth)…";
        }
        // pílula: "Ligado" com o Geo ligado, "Aviso" se a ponte para o servidor tem um problema, "A ligar" na procura
        String pil = "A ligar";
        if (btDesligado && !"ligado".equals(radio)) pil = "Sem BT";
        else if ("ligado".equals(radio)) {
            String pe = String.valueOf(r.get("estado"));
            boolean problema = Boolean.TRUE.equals(r.get("ponte")) && !"ligada".equals(pe)
                && !"a ligar".equals(pe) && !"à espera do Geo".equals(pe);
            pil = problema ? "Aviso" : "Ligado";
        }
        pilula = pil;
        String chave = t + "|" + pil + "|" + pilulaLigada();
        if (chave.equals(ultimoTexto)) return;
        ultimoTexto = chave;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) { try { nm.notify(ID, notificacao(t)); } catch (Exception e) { /* sem permissão de notificações */ } }
    }

    @Override
    public void haDados() { /* só a página quer saber */ }

    private void criarCanal() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel c = new NotificationChannel(CANAL, "Ligação ao Geo", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Mantém a GEOREDE ligada ao teu Geo com o ecrã bloqueado");
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    @Override
    public void onDestroy() {
        ativo = false;
        if (receptorRegistado) { try { unregisterReceiver(receptorBt); } catch (Exception e) { /* já saiu */ } receptorRegistado = false; }
        if (central != null) { central.largar(this); central.desligar(); }
        if (trinco != null && trinco.isHeld()) trinco.release();
        trinco = null;
        super.onDestroy();
    }
}
