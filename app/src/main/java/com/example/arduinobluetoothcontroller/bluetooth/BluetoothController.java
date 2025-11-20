package com.example.arduinobluetoothcontroller.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothController {

    public interface ConnectionListener {
        @MainThread
        void onConnected(BluetoothDevice device);

        @MainThread
        void onDisconnected();

        @MainThread
        void onError(String message);
    }

    public interface DataListener {
        void onPacket(String packet);
    }

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DataListener> dataListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Future<?> readerFuture;

    public BluetoothController(Context context) {
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter defaultAdapter = bluetoothManager != null
                ? bluetoothManager.getAdapter()
                : BluetoothAdapter.getDefaultAdapter();
        this.adapter = defaultAdapter;
    }

    public boolean isBluetoothAvailable() {
        return adapter != null;
    }

    public boolean isConnected() {
        return connected.get() && socket != null && socket.isConnected();
    }

    @SuppressLint("MissingPermission")
    public Set<BluetoothDevice> getBondedDevices() {
        if (adapter == null) {
            return Collections.emptySet();
        }
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        return devices != null ? devices : Collections.emptySet();
    }

    public void addConnectionListener(ConnectionListener listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
    }

    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    public void addDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.add(listener);
        }
    }

    public void removeDataListener(DataListener listener) {
        dataListeners.remove(listener);
    }

    @SuppressLint("MissingPermission")
    public void connect(@Nullable BluetoothDevice device) {
        if (adapter == null || device == null) {
            notifyError("Bluetooth device not available.");
            return;
        }

        connectionExecutor.execute(() -> {
            disconnectInternal(false);
            try {
                adapter.cancelDiscovery();
                BluetoothSocket targetSocket =
                        device.createRfcommSocketToServiceRecord(SPP_UUID);
                targetSocket.connect();

                socket = targetSocket;
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                connected.set(true);
                notifyConnected(device);
                startReader();
            } catch (IOException ioException) {
                notifyError(ioException.getMessage());
                disconnectInternal(true);
            }
        });
    }

    public void disconnect() {
        connectionExecutor.execute(() -> disconnectInternal(true));
    }

    public void sendCommand(String command) {
        if (!isConnected() || TextUtils.isEmpty(command)) {
            return;
        }

        writerExecutor.execute(() -> {
            try {
                if (outputStream != null) {
                    outputStream.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            } catch (IOException ignored) {
                notifyError("Failed to send command.");
            }
        });
    }

    private void startReader() {
        stopReader();
        readerFuture = readerExecutor.submit(() -> {
            byte[] buffer = new byte[1024];
            StringBuilder builder = new StringBuilder();
            try {
                while (!Thread.currentThread().isInterrupted()
                        && isConnected()
                        && inputStream != null) {
                    int size = inputStream.read(buffer);
                    if (size == -1) {
                        break;
                    }
                    for (int i = 0; i < size; i++) {
                        char c = (char) buffer[i];
                        if (c == '\n') {
                            String packet = builder.toString().trim();
                            builder.setLength(0);
                            if (!packet.isEmpty()) {
                                notifyPacket(packet);
                            }
                        } else if (c != '\r') {
                            builder.append(c);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Connection lost.
            } finally {
                builder.setLength(0);
                disconnectInternal(true);
            }
        });
    }

    private void stopReader() {
        if (readerFuture != null) {
            readerFuture.cancel(true);
            readerFuture = null;
        }
    }

    private void disconnectInternal(boolean notify) {
        stopReader();
        closeQuietly(inputStream);
        closeQuietly(outputStream);
        closeQuietly(socket);
        inputStream = null;
        outputStream = null;
        socket = null;

        if (connected.getAndSet(false) && notify) {
            notifyDisconnected();
        }
    }

    private void closeQuietly(@Nullable BluetoothSocket targetSocket) {
        if (targetSocket != null) {
            try {
                targetSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(@Nullable InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(@Nullable OutputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void notifyConnected(BluetoothDevice device) {
        mainHandler.post(() -> {
            for (ConnectionListener listener : connectionListeners) {
                listener.onConnected(device);
            }
        });
    }

    private void notifyDisconnected() {
        mainHandler.post(() -> {
            for (ConnectionListener listener : connectionListeners) {
                listener.onDisconnected();
            }
        });
    }

    private void notifyError(String message) {
        mainHandler.post(() -> {
            for (ConnectionListener listener : connectionListeners) {
                listener.onError(message);
            }
        });
    }

    private void notifyPacket(String packet) {
        for (DataListener listener : dataListeners) {
            listener.onPacket(packet);
        }
    }
}


