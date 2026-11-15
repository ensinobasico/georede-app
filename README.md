# GEOREDE — app

Código-fonte da app GEOREDE (web e Android) que se instala no telemóvel: mapa dos Geos, mensagens, zonas, alertas
e ligação por Bluetooth aos Geos (Meshtastic).

**Licença:** GNU GPL-3.0 (ver `LICENSE`). Copyright (C) 2026 David Azevedo. Avisos e origem do código em `NOTICE.md`.

## O que está aqui
- `web/` — a app web (servida em `https://app.georede.com`; a app Android abre esta página).
- `android/` — o projeto Android (Capacitor): `preparar.js`, código nativo em `nativo/`, configuração.
- `ferramentas/proto2schema.py` — gera a tabela ESQ de `web/index.html` a partir dos `.proto` do Meshtastic.

## O que não está aqui
O servidor da GEOREDE (API, descodificação, zonas, alertas, Farol) não é distribuído e não faz parte deste código.
Também não estão as chaves de assinatura nem a configuração do Firebase (`google-services.json`).

## Construir a app Android
```
cd android
npm install
npx cap add android
node preparar.js
npx cap sync android
npx cap open android
```
Para as notificações é preciso um projeto Firebase próprio (o seu `google-services.json` na pasta `android/`).

## Marca
A licença GPL cobre o código, não a marca. «GEOREDE», o nome e o logótipo não podem ser usados em versões
modificadas ou cópias sem autorização. Uma versão modificada tem de ter outro nome e outro ícone.

## Contacto
ola@georede.com
