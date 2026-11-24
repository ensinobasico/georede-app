package com.georede.app;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * GEOREDE — contas da atualização do programa do Geo por Bluetooth (18/11/2026). Sem Android, para se poder testar
 * no computador (TesteDfuNucleo).
 */
final class GeoredeDfuNucleo {
    /** Tamanho máximo do pacote de atualização (os do Meshtastic para nRF52 andam pelos 500 a 800 KB). */
    static final int TAMANHO_MAX = 4 * 1024 * 1024;

    private GeoredeDfuNucleo() {}

    /** "aa:bb:cc:dd:ee:ff" em maiúsculas, ou null se não for um endereço Bluetooth. */
    static String endereco(String e) {
        if (e == null) return null;
        String s = e.trim().toUpperCase(Locale.ROOT);
        return s.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") ? s : null;
    }

    /**
     * O endereço com que o Geo se anuncia em modo de atualização: o mesmo, com o último byte mais um (é o que o
     * arranque da Adafruit/Meshtastic faz; FF dá a volta a 00, sem passar para o byte anterior).
     */
    static String enderecoDfu(String e) {
        String s = endereco(e);
        if (s == null) return null;
        int ultimo = (Integer.parseInt(s.substring(15), 16) + 1) & 0xFF;
        return s.substring(0, 15) + String.format(Locale.ROOT, "%02X", ultimo);
    }

    static String sha256(byte[] dados) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(dados);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    /** Um zip começa por "PK\3\4"; evita mandar ao Geo uma página de erro ou um ficheiro cortado. */
    static boolean pareceZip(byte[] d) {
        return d != null && d.length > 100 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    /** null se está tudo certo; senão, o motivo (para a página mostrar). */
    static String conferir(byte[] dados, String sha256Esperado, int bytesEsperados) {
        if (dados == null || dados.length == 0) return "ficheiro vazio";
        if (dados.length > TAMANHO_MAX) return "ficheiro demasiado grande";
        if (bytesEsperados > 0 && dados.length != bytesEsperados) return "o tamanho não bate certo";
        if (!pareceZip(dados)) return "não é um pacote de atualização";
        if (sha256Esperado == null || !sha256Esperado.matches("[0-9a-fA-F]{64}")) return "falta a assinatura do ficheiro";
        if (!sha256(dados).equalsIgnoreCase(sha256Esperado)) return "a assinatura não bate certo";
        return null;
    }
}
