package com.georede.app;

/** GEOREDE — base64 padrão (como o btoa/atob da página e o base64 do Python), sem depender da versão do Android. */
final class Base64Simples {
    private static final char[] A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] V = new int[128];
    static { java.util.Arrays.fill(V, -1); for (int k = 0; k < A.length; k++) V[A[k]] = k; }

    static String codificar(byte[] b) {
        StringBuilder s = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xFF) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xFF) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xFF : 0);
            s.append(A[n >> 18 & 63]).append(A[n >> 12 & 63]);
            s.append(i + 1 < b.length ? A[n >> 6 & 63] : '=');
            s.append(i + 2 < b.length ? A[n & 63] : '=');
        }
        return s.toString();
    }

    static byte[] descodificar(String s) {
        String t = s.trim();
        if (t.length() % 4 != 0) throw new IllegalArgumentException("base64: tamanho");
        int pad = t.endsWith("==") ? 2 : t.endsWith("=") ? 1 : 0;
        byte[] r = new byte[t.length() / 4 * 3 - pad];
        int o = 0;
        for (int i = 0; i < t.length(); i += 4) {
            int n = 0;
            for (int k = 0; k < 4; k++) {
                char c = t.charAt(i + k);
                int v;
                if (c == '=') { if (i + k < t.length() - pad) throw new IllegalArgumentException("base64: ="); v = 0; }
                else { v = c < 128 ? V[c] : -1; if (v < 0) throw new IllegalArgumentException("base64: carácter"); }
                n = n << 6 | v;
            }
            if (o < r.length) r[o++] = (byte) (n >> 16);
            if (o < r.length) r[o++] = (byte) (n >> 8);
            if (o < r.length) r[o++] = (byte) n;
        }
        return r;
    }
}
