# GEOREDE — app Android nova (09/11/2026): letra até 115 % + links dos emails

Este pacote **substitui** o `MainActivity.java` dos 115 % de hoje à tarde. Esse vinha de uma cópia antiga e não registava o GeoredeFicheiro (GPX, «Descarregar os meus dados») nem o GeoredeLogin (entrar com a conta Google).

Este regista os **6 plugins** (Servico, Radio, Push, Encontro, Ficheiro, Login) e traz:
- a letra a seguir a do Android, até **115 %** no máximo;
- os **links dos emails** (`app.georede.com/alertas`, etc.) a abrir na app. Isto só funciona depois de o `assetlinks.json` estar no servidor (ver `LEIA-ME-links.md`, partes 2 e 3). Até lá, os links abrem no browser como hoje e nada se estraga.

## No PC (PowerShell)

1. Extrair o zip para dentro da pasta do projeto (substitui o `nativo\MainActivity.java`):
   ```
   Expand-Archive $HOME\Downloads\app-nova.zip -DestinationPath $HOME\georede-android -Force
   cd $HOME\georede-android
   ```
2. No `preparar.js`, subir o `VERSAO_CODIGO` (no topo) em 1.
3. Correr, um de cada vez:
   ```
   node links-app.js
   node preparar.js
   npx cap sync android
   npx cap open android
   ```
   - O `links-app.js` tem de dizer «OK» três vezes (na MainActivity: «já tratava os links»).
   - No `preparar.js` têm de aparecer `OK: MainActivity.java`, `OK: GeoredeLinks.java`, `OK: GeoredeFicheiroPlugin.java` e `OK: GeoredeLoginPlugin.java`.
4. Confirmar que os 6 plugins chegaram ao projeto Android:
   ```
   Select-String -Path android\app\src\main\java\com\georede\app\MainActivity.java -Pattern "registerPlugin"
   ```
   Têm de aparecer **6 linhas**.
5. Android Studio: esperar pela sincronização e depois **▶ Run** (telemóvel por USB), ou **Build → Generate Signed App Bundle** para a Play.

## Testar no telemóvel
- Letra: com a letra do Android grande, os títulos ficam maiores do que o texto e o painel mostra mais coisas.
- **Mais → Histórico → Exportar GPX** abre o «Partilhar» do Android (Ficheiro OK).
- Sair da conta e entrar com **Google** (Login OK).
- Uma notificação continua a abrir a secção certa.

O cartão do Fundador cortado à direita é outra coisa: vem da página (`index.html` no servidor), não da app. Corrige-se no servidor, sem nova app.
