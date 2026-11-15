# GEOREDE — links dos emails abrem na app Android (09/11/2026)

Com a app instalada, um link `https://app.georede.com/alertas` (ou `/mensagens`, `/mapa`, `/zonas`, `/mais`, ou só `app.georede.com`) abre **na app, na secção certa**. Sem a app, abre no browser como hoje.

Ficam sempre no browser: o login (`/auth/...`), os Termos e a Privacidade (`/api/legal/...`), a página «Encontrei» (`/api/e/...`) e as confirmações de email.

São precisas duas partes: **a app** (diz ao Android que esses links são dela) e **o servidor** (o `assetlinks.json` prova ao Android que a app é mesmo nossa). Sem a parte do servidor, o Android continua a abrir no browser.

---

## Parte 1 — No PC (pasta `$HOME\georede-android`)

1. Extrair o zip **para dentro** da pasta `georede-android`. Ficam lá o `links-app.js` e a pasta `links-app\`.
2. No `preparar.js`, subir o `VERSAO_CODIGO` (no topo) em 1.
3. No PowerShell, na pasta do projeto:
   ```
   node links-app.js
   node preparar.js
   npx cap sync android
   ```
   O `links-app.js` tem de dizer três vezes «OK». Se disser «FALHOU», pára e manda-me a mensagem.
4. Android Studio: ▶ (Run) para testar, ou **Build → Generate Signed App Bundle** para enviar para a Play.

O que o `links-app.js` muda (pode correr-se mais de uma vez):
- põe o `GeoredeLinks.java` na pasta `nativo\`;
- na `nativo\MainActivity.java`, acrescenta 2 linhas: um link nosso é tratado como o toque numa notificação. Também passa a abrir sempre em `app.georede.com`, mesmo que a página esteja noutro sítio (por exemplo, no login);
- no `AndroidManifest.xml`, junta o intent-filter `https://app.georede.com` com `autoVerify`.

> Se um dia correres `npx cap add android` de novo (pasta `android` nova), volta a correr o `node links-app.js`.

---

## Parte 2 — As «impressões digitais» das chaves (SHA-256)

São precisas duas, cada uma do género `AB:CD:12:…` (32 pares):

1. **Chave da Play** (para quem instala pela Play, incluindo o teste interno):
   Play Console → a app GEOREDE → **Testar e lançar → Configuração → Integridade da app → Assinatura de apps**.
   Copiar a **«Impressão digital do certificado SHA-256»** da *chave de assinatura de apps*.
   (A Google mostra ali também um bloco «Digital Asset Links JSON» pronto: serve para conferir.)
2. **Chave de debug** (para quando instalas pelo ▶ do Android Studio). No PowerShell:
   ```
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "$HOME\.android\debug.keystore" -alias androiddebugkey -storepass android
   ```
   Copiar a linha **SHA256:**.

Colar as duas no `assetlinks.json` deste zip, no lugar dos `COLAR_AQUI_…` (entre aspas, com os dois pontos).

---

## Parte 3 — No servidor (Caddy)

O ficheiro tem de abrir em `https://app.georede.com/.well-known/assetlinks.json` **sem sessão**: o Android vai buscá-lo sem cookies. Fazer como já está feito para o `/manifest.webmanifest`.

1. Abrir o `Caddyfile`, no bloco do `app.georede.com`, **ao lado da regra do `/manifest.webmanifest`** (antes do `forward_auth`), e colar isto, com o conteúdo do `assetlinks.json` já com as duas chaves no lugar do `[ … ]`:
   ```
   # GEOREDE: App Links do Android (sem sessão)
   handle /.well-known/assetlinks.json {
   	header Content-Type application/json
   	respond `[ … o conteúdo do assetlinks.json … ]` 200
   }
   ```
2. Aplicar: `docker compose up -d --force-recreate caddy`
3. Confirmar, sem cookies:
   ```
   curl -i https://app.georede.com/.well-known/assetlinks.json
   ```
   Tem de dar **200**, `content-type: application/json` e o JSON (não a página de login, nem um 302).
4. Confirmar como a Google o vê (abrir no browser):
   `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://app.georede.com&relation=delegate_permission/common.handle_all_urls`
   Tem de aparecer `com.georede.app` e as duas impressões digitais.
   - Se a Cloudflare bloquear (Bot Fight Mode ou desafio), abrir uma exceção para `/.well-known/*`.

---

## Parte 4 — Testar no telemóvel

1. Instalar a app nova (pela Play ou pelo ▶). O Android verifica o domínio na instalação: com o servidor já pronto **antes** de instalar.
2. Definições → Apps → GEOREDE → **Abrir por predefinição**: `app.georede.com` tem de aparecer como **verificado**.
   - Se não aparecer, no PC com o telemóvel ligado por USB:
     ```
     adb shell pm verify-app-links --re-verify com.georede.app
     adb shell pm get-app-links com.georede.app
     ```
     Tem de dizer `app.georede.com: verified`.
3. Mandar a ti próprio um email com `https://app.georede.com/alertas` e tocar no link no Gmail: tem de abrir a app nos Alertas.
4. Com a app fechada e com a app aberta noutro ecrã: os dois casos têm de ir para a secção certa.

**Notas**
- **iPhone:** a app no iPhone é a do Safari (PWA) e o iOS não abre essas apps a partir de links. Lá continua a abrir no Safari.
- **Outlook / Hotmail:** pode trocar os links por links seus («Ligações Seguras»). Nesse caso o Android já não os reconhece e abre no browser.
- **Link para uma secção nova da app:** juntá-la a `SECCOES` no `links-app.js` **e** no `GeoredeLinks.java` (precisa de nova versão na Play).
