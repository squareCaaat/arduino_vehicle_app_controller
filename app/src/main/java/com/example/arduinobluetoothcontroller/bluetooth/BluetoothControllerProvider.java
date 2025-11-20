package com.example.arduinobluetoothcontroller.bluetooth;

import android.content.Context;

public final class BluetoothControllerProvider {

    private static BluetoothController instance;

    private BluetoothControllerProvider() {
    }

    public static synchronized BluetoothController getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new BluetoothController(context.getApplicationContext());
        }
        return instance;
    }
}


