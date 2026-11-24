package com.georede.app;

import android.app.Activity;

import no.nordicsemi.android.dfu.DfuBaseService;

/**
 * GEOREDE — serviço que envia o programa novo para o Geo por Bluetooth (18/11/2026). O trabalho todo é da biblioteca
 * de atualização da Nordic (no.nordicsemi.android:dfu); aqui só se diz que atividade abre ao tocar na notificação.
 * Declarado no AndroidManifest.xml com foregroundServiceType="connectedDevice" (o dfu-app.js trata disso).
 */
public class GeoredeDfuServico extends DfuBaseService {
    @Override
    protected Class<? extends Activity> getNotificationTarget() { return MainActivity.class; }

    @Override
    protected boolean isDebug() { return false; }
}
