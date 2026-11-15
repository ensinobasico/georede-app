package com.georede.app;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * GEOREDE — as chaves do telemóvel e a decifra dos avisos (RFC 8291, aes128gcm), sem nada do Android.
 * É o mesmo que o browser faz com o Web Push: o servidor (push.py, `cifrar`) cifra cada aviso só para este
 * telemóvel; o Firebase transporta bytes opacos. Testa-se num computador com java simples (TestePushCifra.java).
 *
 * Chaves: um par P-256 (a pública vai ao servidor como `p256dh`, 65 bytes em base64url) e um segredo `auth`
 * de 16 bytes. Guardam-se em PKCS#8 / bytes (quem as guarda é o plugin, nas preferências privadas da app).
 */
public final class PushCifra {
    public static final class Chaves {
        public final byte[] privadaPkcs8;   // PKCS#8
        public final byte[] publica;        // ponto X9.62 sem compressão (65 bytes, começa por 0x04)
        public final byte[] auth;           // 16 bytes
        Chaves(byte[] p, byte[] q, byte[] a) { privadaPkcs8 = p; publica = q; auth = a; }
    }

    private PushCifra() {}

    /** Chaves novas para este telemóvel. */
    public static Chaves novasChaves() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair par = g.generateKeyPair();
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        return new Chaves(par.getPrivate().getEncoded(), ponto((ECPublicKey) par.getPublic()), auth);
    }

    /** O que o servidor cifrou (sal | rs | idlen | chave efémera | texto cifrado) -> o texto (JSON do aviso). */
    public static byte[] decifrar(byte[] corpo, byte[] privadaPkcs8, byte[] publica, byte[] auth) throws Exception {
        if (corpo == null || corpo.length < 21 + 65 + 17) throw new IllegalArgumentException("aviso curto demais");
        byte[] sal = Arrays.copyOfRange(corpo, 0, 16);
        int idlen = corpo[20] & 0xFF;
        if (idlen != 65 || corpo.length < 21 + idlen + 17) throw new IllegalArgumentException("cabeçalho inválido");
        byte[] efemera = Arrays.copyOfRange(corpo, 21, 21 + idlen);
        byte[] cifrado = Arrays.copyOfRange(corpo, 21 + idlen, corpo.length);

        PrivateKey privada = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privadaPkcs8));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(privada);
        ka.doPhase(dePonto(efemera), true);
        byte[] partilhado = ka.generateSecret();

        byte[] info = juntar("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), publica, efemera);
        byte[] ikm = hkdf(auth, partilhado, info, 32);
        byte[] cek = hkdf(sal, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(sal, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] texto = c.doFinal(cifrado);
        // Fim do registo: o último byte é 0x02 (único registo), antes dele pode haver zeros de enchimento
        int fim = texto.length - 1;
        while (fim >= 0 && texto[fim] == 0) fim--;
        if (fim < 0 || texto[fim] != 2) throw new IllegalArgumentException("fim de registo inválido");
        return Arrays.copyOfRange(texto, 0, fim);
    }

    // ---------------------------------------------------------------- HKDF (um só bloco, como no push.py)
    static byte[] hkdf(byte[] sal, byte[] ikm, byte[] info, int tamanho) throws Exception {
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(sal, "HmacSHA256"));
        byte[] prk = h.doFinal(ikm);
        h.init(new SecretKeySpec(prk, "HmacSHA256"));
        h.update(info);
        h.update((byte) 1);
        return Arrays.copyOf(h.doFinal(), tamanho);
    }

    // ---------------------------------------------------------------- pontos da curva
    static byte[] ponto(ECPublicKey k) {
        byte[] r = new byte[65];
        r[0] = 4;
        copiar(k.getW().getAffineX(), r, 1);
        copiar(k.getW().getAffineY(), r, 33);
        return r;
    }

    static PublicKey dePonto(byte[] p) throws Exception {
        if (p.length != 65 || p[0] != 4) throw new IllegalArgumentException("ponto inválido");
        ECParameterSpec curva = parametrosP256();
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(p, 1, 33)), new BigInteger(1, Arrays.copyOfRange(p, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, curva));
    }

    private static ECParameterSpec curva;
    private static synchronized ECParameterSpec parametrosP256() throws Exception {
        if (curva == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            curva = ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
        }
        return curva;
    }

    private static void copiar(BigInteger n, byte[] destino, int pos) {
        byte[] b = n.toByteArray();
        int inicio = b.length > 32 ? b.length - 32 : 0;   // tira o zero de sinal
        int tam = b.length - inicio;
        System.arraycopy(b, inicio, destino, pos + 32 - tam, tam);
    }

    static byte[] juntar(byte[]... partes) {
        int n = 0;
        for (byte[] p : partes) n += p.length;
        byte[] r = new byte[n];
        int i = 0;
        for (byte[] p : partes) { System.arraycopy(p, 0, r, i, p.length); i += p.length; }
        return r;
    }

    // ---------------------------------------------------------------- base64url (sem '=')
    public static String b64u(byte[] b) {
        return Base64Simples.codificar(b).replace('+', '-').replace('/', '_').replace("=", "");
    }

    public static byte[] deB64u(String s) {
        String t = s.replace('-', '+').replace('_', '/');
        while (t.length() % 4 != 0) t += "=";
        return Base64Simples.descodificar(t);
    }
}
