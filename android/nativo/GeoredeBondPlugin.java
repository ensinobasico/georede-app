package com.georede.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.lang.reflect.Method;

/**
 * GEOREDE (24/11/2026): esquecer o emparelhamento Bluetooth de um Geo depois de ele ser libertado, para a pessoa não
 * ter de ir às definições do Android. O Android não tem uma forma pública de o fazer: usa-se o removeBond() (o mesmo
 * que o botão «Esquecer» das definições). Se o telemóvel não deixar, a app abre as definições de Bluetooth.
 *   esquecer({endereco})  -> {esquecido: true|false, emparelhado: true|false}
 *   abrirDefinicoes()     -> abre Definições › Bluetooth
 */
@CapacitorPlugin(name = "GeoredeBond")
public class GeoredeBondPlugin extends Plugin {

    @PluginMethod
    public void esquecer(PluginCall call) {
        String endereco = call.getString("endereco", "");
        JSObject r = new JSObject();
        r.put("esquecido", false);
        r.put("emparelhado", false);
        if (endereco == null || !BluetoothAdapter.checkBluetoothAddress(endereco.toUpperCase())) { call.resolve(r); return; }
        try {
            BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a == null) { call.resolve(r); return; }
            BluetoothDevice d = a.getRemoteDevice(endereco.toUpperCase());
            if (d.getBondState() == BluetoothDevice.BOND_NONE) { r.put("esquecido", true); call.resolve(r); return; }
            r.put("emparelhado", true);
            Method m = d.getClass().getMethod("removeBond");
            Object ok = m.invoke(d);
            r.put("esquecido", Boolean.TRUE.equals(ok));
        } catch (SecurityException e) {
            Log.w("GEOREDE", "esquecer o Geo: sem autorização de Bluetooth", e);
        } catch (Exception e) {
            Log.w("GEOREDE", "esquecer o Geo: o Android não deixou", e);
        }
        call.resolve(r);
    }

    @PluginMethod
    public void abrirDefinicoes(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("não consegui abrir as definições de Bluetooth");
        }
    }
}
