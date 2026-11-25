# Avisos e origem do código

GEOREDE app — Copyright (C) 2026 David Azevedo. Distribuído sob a GNU General Public License, versão 3 (ver `LICENSE`).

## Meshtastic
A app fala com os Geos pelo protocolo Meshtastic. A tabela de mensagens `ESQ` em `web/index.html`
(nomes, números e tipos dos campos de ToRadio, FromRadio e das mensagens que eles usam) é gerada pelo
`ferramentas/proto2schema.py` a partir das definições oficiais `.proto` do Meshtastic:

- https://github.com/meshtastic/protobufs — GPL-3.0, © Meshtastic LLC e contribuidores.

O codificador (`GeoProto`) e o leitor de protobuf do Android (`PonteNucleo.java`) foram escritos de raiz.
O aviso de copyright e de licença da Meshtastic também está no próprio `web/index.html`, por cima da tabela `ESQ`.

Meshtastic® is a registered trademark of Meshtastic LLC. Meshtastic software components are released under various licenses, see GitHub for details. No warranty is provided - use at your own risk.

A GEOREDE é um projeto independente, não afiliado nem apoiado pela Meshtastic LLC.
GEOREDE is an independent project, not affiliated with or endorsed by Meshtastic LLC.

## Firmware dos Geos
Os Geos usam o firmware Meshtastic® oficial, sem alterações (GPL-3.0). A fonte de cada versão está nas releases do
Meshtastic, com o mesmo nome da versão:

- 2.7.26.54e0d8d — https://github.com/meshtastic/firmware/releases/tag/v2.7.26.54e0d8d

O servidor da GEOREDE guarda estes ficheiros para a atualização pela app, em `/firmware/`, junto da licença
(`LICENSE-GPL-3.0.txt`) e de um `LEIA-ME.txt` com os links da fonte.

## Suporte
O suporte dos Geos e da app é dado pela GEOREDE (ola@georede.com). Não contactes o projeto Meshtastic nem os grupos da
comunidade sobre a GEOREDE.
Support for GEOREDE devices and the app comes from GEOREDE (ola@georede.com). Please don't contact the Meshtastic project
or community groups about GEOREDE.

## Bibliotecas de terceiros
- **Capacitor** e **@capacitor-community/bluetooth-le**: MIT (instaladas pelo `npm install`, não incluídas aqui).
- **AndroidX**, **Firebase Cloud Messaging**, **ML Kit**: Apache-2.0 / termos da Google (via Gradle, não incluídas aqui).
- **MapLibre GL JS** (`web/vendor/maplibre-gl.css`): BSD-3-Clause, https://github.com/maplibre/maplibre-gl-js
- **Protomaps basemaps (estilos do mapa)** (`web/vendor/basemaps.js`): BSD-3-Clause (confirmar), https://github.com/protomaps/basemaps
- **Tipos de letra do mapa (glifos .pbf)** (`web/mapa/fontes/OFL.txt`): SIL Open Font License 1.1 (ver OFL.txt)
- **Ícones do mapa (sprites)** (`web/mapa/sprites/v4/black.json`): confirmar a origem (provavelmente Protomaps basemaps-assets), https://github.com/protomaps/basemaps-assets
