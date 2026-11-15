# Avisos e origem do código

GEOREDE app — Copyright (C) 2026 David Azevedo. Distribuído sob a GNU General Public License, versão 3 (ver `LICENSE`).

## Meshtastic
A app fala com os Geos pelo protocolo Meshtastic. A tabela de mensagens `ESQ` em `web/index.html`
(nomes, números e tipos dos campos de ToRadio, FromRadio e das mensagens que eles usam) é gerada pelo
`ferramentas/proto2schema.py` a partir das definições oficiais `.proto` do Meshtastic:

- https://github.com/meshtastic/protobufs — GPL-3.0, © Meshtastic LLC e contribuidores.

O codificador (`GeoProto`) e o leitor de protobuf do Android (`PonteNucleo.java`) foram escritos de raiz.
Meshtastic® é uma marca registada da Meshtastic LLC; a GEOREDE não é afiliada nem apoiada pela Meshtastic LLC.

## Bibliotecas de terceiros
- **Capacitor** e **@capacitor-community/bluetooth-le**: MIT (instaladas pelo `npm install`, não incluídas aqui).
- **AndroidX**, **Firebase Cloud Messaging**, **ML Kit**: Apache-2.0 / termos da Google (via Gradle, não incluídas aqui).
- **MapLibre GL JS** (`web/vendor/maplibre-gl.css`): BSD-3-Clause, https://github.com/maplibre/maplibre-gl-js
- **Protomaps basemaps (estilos do mapa)** (`web/vendor/basemaps.js`): BSD-3-Clause (confirmar), https://github.com/protomaps/basemaps
- **Tipos de letra do mapa (glifos .pbf)** (`web/mapa/fontes/OFL.txt`): SIL Open Font License 1.1 (ver OFL.txt)
- **Ícones do mapa (sprites)** (`web/mapa/sprites/v4/black.json`): confirmar a origem (provavelmente Protomaps basemaps-assets), https://github.com/protomaps/basemaps-assets
