package com.georede.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Testa a PushCifra num computador (sem Android): decifra avisos cifrados pelo push.py (vetores-push.json) e o
 * exemplo da RFC 8291; chaves novas são válidas e diferentes; um aviso adulterado é recusado.
 *   javac -d /tmp/pc PushCifra.java Base64Simples.java Json.java testes/TestePushCifra.java
 *   java -cp /tmp/pc com.georede.app.TestePushCifra testes/vetores-push.json
 */
public class TestePushCifra {
    static int falhas = 0;

    static void verifica(boolean ok, String o) {
        System.out.println((ok ? "OK    " : "FALHA ") + o);
        if (!ok) falhas++;
    }

    public static void main(String[] args) throws Exception {
        String json = new String(Files.readAllBytes(Path.of(args[0])), StandardCharsets.UTF_8);
        List<?> vetores = (List<?>) Json.ler(json);
        int n = 0;
        for (Object o : vetores) {
            Map<?, ?> v = (Map<?, ?>) o;
            n++;
            byte[] priv = Base64Simples.descodificar((String) v.get("privada"));
            byte[] pub = Base64Simples.descodificar((String) v.get("publica"));
            byte[] auth = Base64Simples.descodificar((String) v.get("auth"));
            byte[] cifrado = Base64Simples.descodificar((String) v.get("cifrado"));
            byte[] esperado = Base64Simples.descodificar((String) v.get("texto"));
            byte[] texto = PushCifra.decifrar(cifrado, priv, pub, auth);
            verifica(Arrays.equals(texto, esperado), n + ". decifra o aviso cifrado pelo push.py"
                + (v.containsKey("rfc") ? " (exemplo da RFC 8291)" : "") + " (" + esperado.length + " bytes)");
            // adulterado: um byte trocado no texto cifrado
            byte[] mau = cifrado.clone();
            mau[mau.length - 5] ^= 1;
            boolean recusou = false;
            try { PushCifra.decifrar(mau, priv, pub, auth); } catch (Exception e) { recusou = true; }
            verifica(recusou, n + ". um aviso adulterado é recusado");
            // com a chave de outro telemóvel não abre
            PushCifra.Chaves outra = PushCifra.novasChaves();
            recusou = false;
            try { PushCifra.decifrar(cifrado, outra.privadaPkcs8, outra.publica, outra.auth); } catch (Exception e) { recusou = true; }
            verifica(recusou, n + ". com as chaves de outro telemóvel não abre");
        }
        PushCifra.Chaves a = PushCifra.novasChaves(), b = PushCifra.novasChaves();
        verifica(a.publica.length == 65 && a.publica[0] == 4 && a.auth.length == 16, "chaves novas: ponto de 65 bytes (0x04…) e auth de 16");
        verifica(!Arrays.equals(a.publica, b.publica) && !Arrays.equals(a.auth, b.auth), "chaves novas são sempre diferentes");
        verifica(PushCifra.dePonto(a.publica) != null, "a chave pública nova lê-se de volta (está na curva)");
        String p = PushCifra.b64u(a.publica), s = PushCifra.b64u(a.auth);
        verifica(!p.contains("=") && !p.contains("+") && !p.contains("/") && Arrays.equals(PushCifra.deB64u(p), a.publica)
            && Arrays.equals(PushCifra.deB64u(s), a.auth), "base64url como o browser (p256dh/auth) e de volta");
        // o que vai para o servidor tem de ser aceite pelo validar_subscricao: escreve-o para o Python conferir
        Files.writeString(Path.of(args[0]).resolveSibling("chaves-java.json"),
            "{\"p256dh\":\"" + p + "\",\"auth\":\"" + s + "\",\"privada\":\"" + Base64Simples.codificar(a.privadaPkcs8) + "\"}");
        System.out.println(falhas == 0 ? "\nTUDO CERTO" : "\n" + falhas + " FALHA(S)");
        System.exit(falhas == 0 ? 0 : 1);
    }
}
