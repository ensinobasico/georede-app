package com.georede.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GEOREDE — o coração da ponte nativa, sem nada do Android (testa-se num computador com java simples).
 *
 * O serviço (GeoredeServico + GeoredeRadio) dá-lhe:
 *   - cada FromRadio lido do Geo  -> deFromRadio(bytes)
 *   - a resposta de cada POST /api/ponte -> depoisDoPedido(...)
 * e ele decide:
 *   - o que vai para a página (fila, lida pelo plugin quando a página está acordada);
 *   - o que sobe para o servidor (mqttClientProxyMessage do Geo);
 *   - o que se escreve no Geo (descidas do servidor, heartbeat, want_config).
 *
 * Protocolo do Meshtastic (mesh.proto, firmware 2.7.26):
 *   FromRadio: my_info = 3 (MyNodeInfo.my_node_num = 1), config_complete_id = 7, mqttClientProxyMessage = 14
 *   ToRadio:   want_config_id = 3, mqttClientProxyMessage = 6, heartbeat = 7
 *   MqttClientProxyMessage: topic = 1, data = 2, text = 3, retained = 4
 * Ponte (web/api/georede_api/ponte.py): POST /api/ponte {node_id, cursor, subidas:[{topico, dados}]} -> {cursor, descidas}
 */
public class PonteNucleo {
    public static final int MAX_PAGINA = 2000;      // pacotes guardados para a página enquanto dorme
    public static final int MAX_SUBIDAS_FILA = 300; // como a ponte da página: sem internet guarda até 300
    public static final int MAX_SUBIDAS_PEDIDO = 40;
    public static final int MAX_BYTES = 512;
    public static final long RITMO_MS = 3000;       // um pedido de 3 em 3 s (logo a seguir quando o Geo entrega algo)
    public static final long PRESSA_MS = 300;
    public static final long PAUSA_404_MS = 60000;  // o Geo não é desta conta
    public static final long PAUSA_SESSAO_MS = 30000;
    public static final long PAUSA_MAX_MS = 30000;

    // ---------------------------------------------------------------- estado
    private final ArrayDeque<byte[]> paraPagina = new ArrayDeque<>();
    private long perdidosPagina = 0;
    private final ArrayDeque<String[]> subidas = new ArrayDeque<>();   // {topico, base64}
    private long perdidasSubidas = 0;
    private long noAr = 0;               // quantas subidas vão no pedido em curso
    private Long cursor = null;
    private long nodeNum = 0;
    private boolean ponteLigada = true;  // a app pode desligá-la (modo de emergência, sem conta)
    private long proximoPedido = 0;
    private long falhasSeguidas = 0;
    private String estadoPonte = "à espera do Geo";
    private long enviados = 0, recebidos = 0;
    private long vistoServidor = 0;
    private int configNonce = 0;

    // ---------------------------------------------------------------- do Geo
    /** Um FromRadio lido do Geo. Devolve true se é para a página (false: tratado aqui, a ponte). */
    public synchronized boolean deFromRadio(byte[] fr, long agora) {
        if (fr == null || fr.length == 0) return false;
        Leitor l = new Leitor(fr);
        boolean paraAPagina = true;
        while (l.temMais()) {
            int chave = l.varint32();
            int campo = chave >>> 3, tipo = chave & 7;
            if (campo == 3 && tipo == 2) {                       // my_info
                Leitor mi = new Leitor(l.bytes());
                while (mi.temMais()) {
                    int k = mi.varint32();
                    if ((k >>> 3) == 1 && (k & 7) == 0) nodeNum = mi.varint64() & 0xFFFFFFFFL;
                    else mi.saltar(k & 7);
                }
            } else if (campo == 14 && tipo == 2) {               // mqttClientProxyMessage: sobe para o servidor
                byte[] m = l.bytes();
                String[] s = proxy(m);
                if (s != null) guardarSubida(s, agora);
                paraAPagina = false;
            } else {
                l.saltar(tipo);
            }
        }
        if (paraAPagina) paraPagina(fr);
        return paraAPagina;
    }

    private String[] proxy(byte[] m) {
        Leitor l = new Leitor(m);
        String topico = null; byte[] dados = null;
        while (l.temMais()) {
            int k = l.varint32();
            int c = k >>> 3, t = k & 7;
            if (c == 1 && t == 2) topico = new String(l.bytes(), StandardCharsets.UTF_8);
            else if (c == 2 && t == 2) dados = l.bytes();
            else l.saltar(t);                                   // text (3) e retained (4) não interessam à ponte
        }
        if (topico == null || dados == null || dados.length == 0 || dados.length > MAX_BYTES) return null;
        return new String[]{topico, Base64Simples.codificar(dados)};
    }

    private void guardarSubida(String[] s, long agora) {
        if (!ponteLigada) return;
        subidas.addLast(s);
        while (subidas.size() > MAX_SUBIDAS_FILA) { subidas.removeFirst(); perdidasSubidas++; if (noAr > 0) noAr--; }
        if (falhasSeguidas == 0) proximoPedido = Math.min(proximoPedido, agora + PRESSA_MS);
    }

    private void paraPagina(byte[] fr) {
        paraPagina.addLast(fr);
        while (paraPagina.size() > MAX_PAGINA) { paraPagina.removeFirst(); perdidosPagina++; }
    }

    /** A página acordou: leva tudo o que chegou (no máximo n). */
    public synchronized List<byte[]> lerParaPagina(int n) {
        List<byte[]> out = new ArrayList<>();
        while (!paraPagina.isEmpty() && out.size() < n) out.add(paraPagina.removeFirst());
        return out;
    }

    public synchronized int porLer() { return paraPagina.size(); }
    public synchronized long perdidosPagina() { long p = perdidosPagina; perdidosPagina = 0; return p; }

    // ---------------------------------------------------------------- para o Geo
    public static byte[] toRadioWantConfig(int nonce) {
        Escritor e = new Escritor();
        e.chave(3, 0); e.varint(nonce & 0xFFFFFFFFL);
        return e.bytes();
    }

    public static byte[] toRadioHeartbeat() {
        Escritor e = new Escritor();
        e.chave(7, 2); e.varint(0);                                // Heartbeat {} vazio
        return e.bytes();
    }

    public static byte[] toRadioProxy(String topico, byte[] dados) {
        Escritor m = new Escritor();
        byte[] t = topico.getBytes(StandardCharsets.UTF_8);
        m.chave(1, 2); m.varint(t.length); m.raw(t);
        m.chave(2, 2); m.varint(dados.length); m.raw(dados);
        byte[] mb = m.bytes();
        Escritor e = new Escritor();
        e.chave(6, 2); e.varint(mb.length); e.raw(mb);
        return e.bytes();
    }

    /** Um want_config_id novo (o serviço manda-o a cada ligação, para o Geo começar a entregar pacotes). */
    public synchronized int novoNonce() {
        configNonce = (configNonce % 0x7FFFFFF0) + 1 + (int) (System.nanoTime() & 0xFF);
        return configNonce;
    }

    // ---------------------------------------------------------------- servidor
    public synchronized void ligarPonte(boolean sim) {
        ponteLigada = sim;
        if (!sim) { subidas.clear(); noAr = 0; estadoPonte = "desligada"; }
    }

    public synchronized void geoLigado(long agora) {
        // Uma ligação nova ao Geo: a ponte recomeça (cursor null = o servidor entrega o último minuto)
        cursor = null; noAr = 0; falhasSeguidas = 0; proximoPedido = agora;
        if (ponteLigada) estadoPonte = "a ligar";
    }

    public synchronized long nodeNum() { return nodeNum; }
    public synchronized void definirNodeNum(long n) { if (nodeNum == 0) nodeNum = n & 0xFFFFFFFFL; }

    /** Hora de um pedido? Devolve o corpo JSON (e marca as subidas que vão nele) ou null. */
    public synchronized String pedidoSeForAltura(long agora) {
        if (!ponteLigada || nodeNum == 0 || noAr != 0 || agora < proximoPedido) return null;
        StringBuilder b = new StringBuilder();
        b.append("{\"node_id\":").append(nodeNum).append(",\"cursor\":").append(cursor == null ? "null" : cursor.toString());
        b.append(",\"subidas\":[");
        int i = 0;
        for (String[] s : subidas) {
            if (i == MAX_SUBIDAS_PEDIDO) break;
            if (i > 0) b.append(',');
            b.append("{\"topico\":").append(Json.texto(s[0])).append(",\"dados\":\"").append(s[1]).append("\"}");
            i++;
        }
        b.append("]}");
        noAr = i;
        if (i == 0) noAr = -1;           // pedido sem subidas em curso (marca para não sobrepor)
        return b.toString();
    }

    /** Resultado do pedido. Devolve os ToRadio a escrever no Geo (as descidas). */
    public synchronized List<byte[]> depoisDoPedido(int codigo, String corpo, long agora) {
        List<byte[]> out = new ArrayList<>();
        long foram = Math.max(noAr, 0);
        noAr = 0;
        if (codigo == 200) {
            Object j;
            try { j = Json.ler(corpo); } catch (RuntimeException e) { j = null; }
            if (!(j instanceof Map)) return falha("resposta estranha do servidor", agora, out);
            for (long k = 0; k < foram && !subidas.isEmpty(); k++) subidas.removeFirst();
            enviados += foram;
            Map<?, ?> r = (Map<?, ?>) j;
            Object c = r.get("cursor");
            if (c instanceof Number) cursor = ((Number) c).longValue();
            Object d = r.get("descidas");
            if (d instanceof List) {
                for (Object o : (List<?>) d) {
                    if (!(o instanceof Map)) continue;
                    Object t = ((Map<?, ?>) o).get("topico"), x = ((Map<?, ?>) o).get("dados");
                    if (!(t instanceof String) || !(x instanceof String)) continue;
                    byte[] dados;
                    try { dados = Base64Simples.descodificar((String) x); } catch (IllegalArgumentException e) { continue; }
                    out.add(toRadioProxy((String) t, dados));
                    recebidos++;
                }
            }
            falhasSeguidas = 0;
            vistoServidor = agora;
            estadoPonte = "ligada";
            proximoPedido = agora + (subidas.isEmpty() ? RITMO_MS : PRESSA_MS);
            return out;
        }
        if (codigo == 404) {
            estadoPonte = "este Geo não é da tua conta";
            proximoPedido = agora + PAUSA_404_MS;
            return out;
        }
        if (codigo == 401 || codigo == 403) {
            estadoPonte = "sessão terminada: abre a app";
            proximoPedido = agora + PAUSA_SESSAO_MS;
            return out;
        }
        if (codigo == 400) {
            // pedido recusado: deita fora as subidas que foram (senão ficava a repetir para sempre)
            for (long k = 0; k < foram && !subidas.isEmpty(); k++) { subidas.removeFirst(); perdidasSubidas++; }
            return falha("o servidor recusou o pedido", agora, out);
        }
        return falha(codigo <= 0 ? "sem internet" : "servidor em baixo (" + codigo + ")", agora, out);
    }

    private List<byte[]> falha(String porque, long agora, List<byte[]> out) {
        falhasSeguidas++;
        estadoPonte = porque;
        long espera = Math.min(PAUSA_MAX_MS, RITMO_MS << Math.min(falhasSeguidas - 1, 4));
        proximoPedido = agora + espera;
        return out;
    }

    public synchronized long proximoPedido() { return proximoPedido; }

    public synchronized Map<String, Object> resumo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ponte", ponteLigada);
        m.put("estado", estadoPonte);
        m.put("enviados", enviados);
        m.put("recebidos", recebidos);
        m.put("porEnviar", (long) subidas.size());
        m.put("perdidas", perdidasSubidas);
        m.put("vistoServidor", vistoServidor);
        m.put("nodeNum", nodeNum);
        m.put("porLer", (long) paraPagina.size());
        return m;
    }

    // ---------------------------------------------------------------- protobuf mínimo
    static final class Leitor {
        private final byte[] b; private int i = 0;
        Leitor(byte[] b) { this.b = b; }
        boolean temMais() { return i < b.length; }
        long varint64() {
            long r = 0; int s = 0;
            while (true) {
                if (i >= b.length) throw new IllegalArgumentException("varint cortado");
                int x = b[i++] & 0xFF;
                r |= (long) (x & 0x7F) << s;
                if ((x & 0x80) == 0) return r;
                s += 7;
                if (s > 63) throw new IllegalArgumentException("varint longo demais");
            }
        }
        int varint32() { return (int) varint64(); }
        byte[] bytes() {
            int n = (int) varint64();
            if (n < 0 || i + n > b.length) throw new IllegalArgumentException("campo cortado");
            byte[] r = new byte[n];
            System.arraycopy(b, i, r, 0, n);
            i += n;
            return r;
        }
        void saltar(int tipo) {
            switch (tipo) {
                case 0: varint64(); break;
                case 1: i += 8; break;
                case 2: bytes(); break;
                case 5: i += 4; break;
                default: throw new IllegalArgumentException("tipo " + tipo);
            }
            if (i > b.length) throw new IllegalArgumentException("campo cortado");
        }
    }

    static final class Escritor {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        void varint(long v) { while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; } o.write((int) v); }
        void chave(int campo, int tipo) { varint(((long) campo << 3) | tipo); }
        void raw(byte[] x) { o.write(x, 0, x.length); }
        byte[] bytes() { return o.toByteArray(); }
    }

    // ---------------------------------------------------------------- hexadecimal (o plugin fala como o BluetoothLe)
    public static String hex(byte[] b) {
        char[] H = "0123456789abcdef".toCharArray();
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) { s.append(H[(x >> 4) & 15]).append(H[x & 15]); }
        return s.toString();
    }

    public static byte[] deHex(String s) {
        String t = s.replaceAll("\\s", "");
        if (t.length() % 2 != 0) throw new IllegalArgumentException("hexadecimal ímpar");
        byte[] r = new byte[t.length() / 2];
        for (int i = 0; i < r.length; i++) {
            int a = Character.digit(t.charAt(2 * i), 16), c = Character.digit(t.charAt(2 * i + 1), 16);
            if (a < 0 || c < 0) throw new IllegalArgumentException("hexadecimal inválido");
            r[i] = (byte) ((a << 4) | c);
        }
        return r;
    }
}
