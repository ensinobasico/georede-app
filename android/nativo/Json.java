package com.georede.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GEOREDE — JSON mínimo para a ponte (sem o org.json do Android, para se testar num computador). */
final class Json {
    private final String s; private int i = 0;
    private Json(String s) { this.s = s; }

    static Object ler(String s) {
        if (s == null) throw new IllegalArgumentException("vazio");
        Json j = new Json(s);
        Object v = j.valor();
        j.espacos();
        if (j.i != s.length()) throw new IllegalArgumentException("lixo no fim");
        return v;
    }

    static String texto(String t) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private void espacos() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private char ve() {
        espacos();
        if (i >= s.length()) throw new IllegalArgumentException("fim inesperado");
        return s.charAt(i);
    }

    private Object valor() {
        char c = ve();
        if (c == '{') {
            i++; Map<String, Object> m = new LinkedHashMap<>();
            if (ve() == '}') { i++; return m; }
            while (true) {
                if (ve() != '"') throw new IllegalArgumentException("chave");
                String k = (String) valor();
                if (ve() != ':') throw new IllegalArgumentException(":");
                i++;
                m.put(k, valor());
                char d = ve(); i++;
                if (d == '}') return m;
                if (d != ',') throw new IllegalArgumentException(",");
            }
        }
        if (c == '[') {
            i++; List<Object> l = new ArrayList<>();
            if (ve() == ']') { i++; return l; }
            while (true) {
                l.add(valor());
                char d = ve(); i++;
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException(",");
            }
        }
        if (c == '"') {
            i++; StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("texto cortado");
                char d = s.charAt(i++);
                if (d == '"') return b.toString();
                if (d != '\\') { b.append(d); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (ini == i) throw new IllegalArgumentException("valor");
        String n = s.substring(ini, i);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        return Long.parseLong(n);
    }
}
