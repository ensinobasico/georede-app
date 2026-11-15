package com.georede.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * GEOREDE — a ligação Bluetooth ao Geo, dentro do serviço nativo (passo 2b, 06/11/2026).
 * É o único dono do BluetoothGatt: liga, lê o FromRadio sempre que o Geo avisa (FromNum), escreve o ToRadio, manda o
 * heartbeat e volta a ligar sozinho. Tudo corre numa só thread ("georede-radio"), uma operação GATT de cada vez.
 * O que lê vai para o PonteNucleo (que separa o que é da ponte do que é para a página).
 * Funciona com o ecrã bloqueado e com a página congelada: não depende do JavaScript.
 */
@SuppressLint("MissingPermission")     // as permissões de Bluetooth são pedidas pela página (plugin BluetoothLe) antes de ligar
final class GeoredeRadio {
    static final UUID SERVICO = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd");
    static final UUID TO_RADIO = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7");
    static final UUID FROM_RADIO = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002");
    static final UUID FROM_NUM = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final long HEARTBEAT_MS = 60_000, VIGIA_MS = 20_000, TEMPO_OP_MS = 10_000, TEMPO_LIGAR_MS = 20_000;

    interface Ouvinte {
        void estadoMudou(String estado);       // desligado | a_ligar | ligado | a_procurar
        void haDados();                        // há pacotes novos para a página
    }

    interface Feito { void feito(boolean ok, String erro); }

    private final Context ctx;
    private final PonteNucleo nucleo;
    private final Ouvinte ouvinte;
    private final Handler h;

    private String endereco;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic toR, fromR, fromNum;
    private volatile String estado = "desligado";
    private boolean querLigado = false;
    private int tentativa = 0;
    private int geracao = 0;                    // cada ligação nova invalida as respostas da anterior

    // operações GATT, uma de cada vez
    private static final class Op {
        final int tipo; final byte[] dados; final Feito feito;
        Op(int tipo, byte[] dados, Feito feito) { this.tipo = tipo; this.dados = dados; this.feito = feito; }
    }
    private static final int ESCREVER = 1, LER = 2;
    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private Op emCurso = null;
    private boolean aLer = false, lerDepois = false;

    private final Runnable religar = this::ligarAgora;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!"ligado".equals(estado)) return;
            escrever(PonteNucleo.toRadioHeartbeat(), null);
            h.postDelayed(this, HEARTBEAT_MS);
        }
    };
    private final Runnable vigia = new Runnable() {          // por segurança: lê de vez em quando, mesmo sem aviso
        @Override public void run() {
            if (!"ligado".equals(estado)) return;
            lerTudo();
            h.postDelayed(this, VIGIA_MS);
        }
    };
    private final Runnable tempoOp = () -> {
        Log.w("GEOREDE", "operação Bluetooth sem resposta: volta a ligar");
        caiu("sem resposta");
    };
    private final Runnable tempoLigar = () -> {
        if ("a_ligar".equals(estado)) caiu("o Geo não respondeu a tempo");
    };

    GeoredeRadio(Context ctx, PonteNucleo nucleo, Ouvinte ouvinte) {
        this.ctx = ctx.getApplicationContext();
        this.nucleo = nucleo;
        this.ouvinte = ouvinte;
        HandlerThread t = new HandlerThread("georede-radio");
        t.start();
        this.h = new Handler(t.getLooper());
    }

    String estado() { return estado; }
    String endereco() { return endereco; }

    // ---------------------------------------------------------------- ligar e desligar
    void ligar(String novoEndereco) {
        h.post(() -> {
            boolean outro = endereco != null && !endereco.equalsIgnoreCase(novoEndereco);
            endereco = novoEndereco;
            querLigado = true;
            if (outro) fechar();
            if ("ligado".equals(estado) || "a_ligar".equals(estado)) { avisar(estado); return; }   // já está
            h.removeCallbacks(religar);
            tentativa = 0;
            ligarAgora();
        });
    }

    void desligar() {
        h.post(() -> {
            querLigado = false;
            h.removeCallbacks(religar);
            fechar();
            mudar("desligado");
        });
    }

    private void ligarAgora() {
        if (!querLigado || endereco == null) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) { caiu("Bluetooth desligado"); return; }
        try {
            BluetoothDevice d = a.getRemoteDevice(endereco);
            fechar();
            geracao++;
            mudar("a_ligar");
            h.postDelayed(tempoLigar, TEMPO_LIGAR_MS);
            final int g = geracao;
            gatt = d.connectGatt(ctx, false, new Chamadas(g), BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) caiu("não consegui ligar");
        } catch (SecurityException e) {
            querLigado = false;
            fechar();
            mudar("desligado");
            Log.e("GEOREDE", "sem permissão de Bluetooth", e);
        } catch (IllegalArgumentException e) {
            querLigado = false;
            mudar("desligado");
            Log.e("GEOREDE", "endereço inválido: " + endereco, e);
        }
    }

    /** Fecha a ligação atual (sem decidir se volta a ligar). */
    private void fechar() {
        h.removeCallbacks(heartbeat);
        h.removeCallbacks(vigia);
        h.removeCallbacks(tempoOp);
        h.removeCallbacks(tempoLigar);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception e) { /* já caiu */ }
            try { gatt.close(); } catch (Exception e) { /* nada */ }
        }
        gatt = null; toR = fromR = fromNum = null;
        if (emCurso != null && emCurso.feito != null) emCurso.feito.feito(false, "a ligação ao Geo caiu");
        emCurso = null;
        for (Op o : ops) if (o.feito != null) o.feito.feito(false, "a ligação ao Geo caiu");
        ops.clear();
        aLer = false; lerDepois = false;
    }

    private void caiu(String porque) {
        Log.i("GEOREDE", "ligação ao Geo: " + porque);
        fechar();
        if (!querLigado) { mudar("desligado"); return; }
        mudar("a_procurar");
        tentativa++;
        long espera = Math.min(60, 1L << Math.min(tentativa, 6)) * 1000L;     // 2, 4, 8… até 60 s, como a página
        h.removeCallbacks(religar);
        h.postDelayed(religar, espera);
    }

    private void mudar(String e) {
        if (e.equals(estado)) return;
        estado = e;
        avisar(e);
    }

    private void avisar(String e) {
        try { ouvinte.estadoMudou(e); } catch (Exception x) { Log.w("GEOREDE", "ouvinte", x); }
    }

    // ---------------------------------------------------------------- escrever e ler
    /** Escreve um ToRadio no Geo (a página ou a ponte). `feito` pode ser null. */
    void escrever(byte[] toRadio, Feito feito) {
        h.post(() -> {
            if (!"ligado".equals(estado) || toR == null) { if (feito != null) feito.feito(false, "sem ligação ao Geo"); return; }
            ops.addLast(new Op(ESCREVER, toRadio, feito));
            proxima();
        });
    }

    /** Lê o FromRadio até vir vazio. */
    void lerTudo() {
        h.post(() -> {
            if (fromR == null) return;
            if (aLer) { lerDepois = true; return; }
            aLer = true;
            ops.addLast(new Op(LER, null, null));
            proxima();
        });
    }

    private void proxima() {
        if (emCurso != null || gatt == null) return;
        Op o = ops.pollFirst();
        if (o == null) return;
        emCurso = o;
        boolean ok;
        try {
            if (o.tipo == ESCREVER) ok = escreverAgora(o.dados);
            else ok = gatt.readCharacteristic(fromR);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) { terminar(false, null, "o Android recusou a operação"); return; }
        h.postDelayed(tempoOp, TEMPO_OP_MS);
    }

    @SuppressWarnings("deprecation")
    private boolean escreverAgora(byte[] v) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(toR, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;   // BluetoothStatusCodes.SUCCESS
        }
        toR.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        toR.setValue(v);
        return gatt.writeCharacteristic(toR);
    }

    /** Fim da operação em curso (na thread do rádio). */
    private void terminar(boolean ok, byte[] lido, String erro) {
        h.removeCallbacks(tempoOp);
        Op o = emCurso;
        emCurso = null;
        if (o == null) { proxima(); return; }
        if (o.tipo == ESCREVER) {
            if (o.feito != null) o.feito.feito(ok, ok ? null : (erro != null ? erro : "a escrita falhou"));
            if (ok) lerTudo();                     // o Geo costuma responder logo (ack, eco, configuração)
        } else {
            if (ok && lido != null && lido.length > 0) {
                boolean paraPagina;
                try { paraPagina = nucleo.deFromRadio(lido, System.currentTimeMillis()); }
                catch (RuntimeException e) { Log.w("GEOREDE", "FromRadio ilegível", e); paraPagina = true; }
                if (paraPagina) { try { ouvinte.haDados(); } catch (Exception x) { /* nada */ } }
                ops.addFirst(new Op(LER, null, null));         // continua a ler até vir vazio
            } else {
                aLer = false;
                if (lerDepois) { lerDepois = false; aLer = true; ops.addLast(new Op(LER, null, null)); }
            }
        }
        proxima();
    }

    private void prontoParaUsar() {
        h.removeCallbacks(tempoLigar);
        tentativa = 0;
        nucleo.geoLigado(System.currentTimeMillis());
        mudar("ligado");
        // Sem um want_config o Geo não entrega pacotes (nem os do MQTT): pede-se logo, com ou sem página acordada.
        escrever(PonteNucleo.toRadioWantConfig(nucleo.novoNonce()), null);
        h.removeCallbacks(heartbeat);
        h.postDelayed(heartbeat, HEARTBEAT_MS);
        h.removeCallbacks(vigia);
        h.postDelayed(vigia, VIGIA_MS);
    }

    // ---------------------------------------------------------------- respostas do Android
    private final class Chamadas extends BluetoothGattCallback {
        private final int g;
        Chamadas(int g) { this.g = g; }
        private boolean velha() { return g != geracao; }

        @Override
        public void onConnectionStateChange(BluetoothGatt gt, int status, int novo) {
            h.post(() -> {
                if (velha()) { try { gt.close(); } catch (Exception e) { /* nada */ } return; }
                if (novo == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        gt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                        if (!gt.requestMtu(512)) gt.discoverServices();
                    } catch (SecurityException e) { caiu("sem permissão"); }
                } else if (novo == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    caiu("desligou (estado " + status + ")");
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt gt, int mtu, int status) {
            h.post(() -> {
                if (velha()) return;
                try { if (!gt.discoverServices()) caiu("não consegui ver os serviços"); } catch (SecurityException e) { caiu("sem permissão"); }
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onServicesDiscovered(BluetoothGatt gt, int status) {
            h.post(() -> {
                if (velha()) return;
                BluetoothGattService s = status == BluetoothGatt.GATT_SUCCESS ? gt.getService(SERVICO) : null;
                if (s == null) { caiu("este aparelho não é um Geo"); return; }
                toR = s.getCharacteristic(TO_RADIO);
                fromR = s.getCharacteristic(FROM_RADIO);
                fromNum = s.getCharacteristic(FROM_NUM);
                if (toR == null || fromR == null || fromNum == null) { caiu("faltam características do Meshtastic"); return; }
                try {
                    gt.setCharacteristicNotification(fromNum, true);
                    BluetoothGattDescriptor d = fromNum.getDescriptor(CCCD);
                    if (d == null) { prontoParaUsar(); return; }
                    boolean ok;
                    if (Build.VERSION.SDK_INT >= 33) ok = gt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0;
                    else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); ok = gt.writeDescriptor(d); }
                    if (!ok) caiu("não consegui pedir os avisos do Geo");
                } catch (SecurityException e) { caiu("sem permissão"); }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gt, BluetoothGattDescriptor d, int status) {
            h.post(() -> {
                if (velha()) return;
                if (status == BluetoothGatt.GATT_SUCCESS) prontoParaUsar();
                else caiu("avisos do Geo recusados (" + status + ")");      // ex.: PIN errado / não emparelhado
            });
        }

        // Android 13+
        @Override
        public void onCharacteristicRead(BluetoothGatt gt, BluetoothGattCharacteristic c, byte[] v, int status) {
            final byte[] copia = v == null ? null : v.clone();
            h.post(() -> { if (!velha()) terminar(status == BluetoothGatt.GATT_SUCCESS, copia, "leitura falhou (" + status + ")"); });
        }

        // Android 12 e anteriores
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt gt, BluetoothGattCharacteristic c, int status) {
            final byte[] v = c.getValue() == null ? null : c.getValue().clone();
            h.post(() -> { if (!velha()) terminar(status == BluetoothGatt.GATT_SUCCESS, v, "leitura falhou (" + status + ")"); });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gt, BluetoothGattCharacteristic c, int status) {
            h.post(() -> { if (!velha()) terminar(status == BluetoothGatt.GATT_SUCCESS, null, "escrita falhou (" + status + ")"); });
        }

        // Aviso do FromNum: há dados para ler (Android 13+ e anteriores)
        @Override
        public void onCharacteristicChanged(BluetoothGatt gt, BluetoothGattCharacteristic c, byte[] v) {
            h.post(() -> { if (!velha()) lerTudo(); });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt gt, BluetoothGattCharacteristic c) {
            h.post(() -> { if (!velha()) lerTudo(); });
        }
    }
}
