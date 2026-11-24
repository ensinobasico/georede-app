package com.georede.app;

/** Corre com: javac -d out GeoredeDfuNucleo.java TesteDfuNucleo.java && java -cp out com.georede.app.TesteDfuNucleo */
public class TesteDfuNucleo {
    static int falhas = 0;
    static void v(boolean c, String t) { System.out.println((c ? "OK    " : "FALHA ") + t); if (!c) falhas++; }
    public static void main(String[] a) {
        v("AA:BB:CC:DD:EE:0F".equals(GeoredeDfuNucleo.endereco(" aa:bb:cc:dd:ee:0f ")), "endereço em maiúsculas");
        v(GeoredeDfuNucleo.endereco("aa:bb:cc:dd:ee") == null && GeoredeDfuNucleo.endereco("zz:bb:cc:dd:ee:ff") == null
          && GeoredeDfuNucleo.endereco(null) == null && GeoredeDfuNucleo.endereco("AABBCCDDEEFF") == null, "endereços inválidos recusados");
        v("AA:BB:CC:DD:EE:10".equals(GeoredeDfuNucleo.enderecoDfu("aa:bb:cc:dd:ee:0f")), "modo de atualização: último byte mais um");
        v("AA:BB:CC:DD:EE:00".equals(GeoredeDfuNucleo.enderecoDfu("AA:BB:CC:DD:EE:FF")), "FF dá a volta a 00 sem mexer no byte anterior");
        v(GeoredeDfuNucleo.enderecoDfu("lixo") == null, "endereço inválido: sem modo de atualização");
        byte[] z = new byte[200]; z[0] = 'P'; z[1] = 'K'; z[2] = 3; z[3] = 4;
        String h = GeoredeDfuNucleo.sha256(z);
        v(h.length() == 64 && "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(GeoredeDfuNucleo.sha256(new byte[0])), "sha256 certo");
        v(GeoredeDfuNucleo.conferir(z, h, 200) == null && GeoredeDfuNucleo.conferir(z, h.toUpperCase(), 0) == null, "pacote certo passa");
        v(GeoredeDfuNucleo.conferir(z, h, 199) != null, "tamanho errado recusado");
        byte[] m = z.clone(); m[150] = 1;
        v(GeoredeDfuNucleo.conferir(m, h, 200) != null, "um byte trocado: recusado");
        byte[] html = "<!doctype html><html><head><title>GEOREDE</title></head><body>entrar na conta ........................................................</body></html>".getBytes();
        v(GeoredeDfuNucleo.conferir(html, GeoredeDfuNucleo.sha256(html), html.length) != null, "uma página em vez do pacote: recusada");
        v(GeoredeDfuNucleo.conferir(z, null, 200) != null && GeoredeDfuNucleo.conferir(z, "abc", 200) != null, "sem assinatura: recusado");
        v(GeoredeDfuNucleo.conferir(null, h, 0) != null && GeoredeDfuNucleo.conferir(new byte[GeoredeDfuNucleo.TAMANHO_MAX + 1], h, 0) != null, "vazio ou enorme: recusado");
        System.exit(falhas == 0 ? 0 : 1);
    }
}
