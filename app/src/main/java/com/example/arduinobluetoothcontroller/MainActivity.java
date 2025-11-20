package com.example.arduinobluetoothcontroller;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.arduinobluetoothcontroller.bluetooth.BluetoothController;
import com.example.arduinobluetoothcontroller.bluetooth.BluetoothControllerProvider;
import com.example.arduinobluetoothcontroller.ui.LogDialogFragment;
import com.example.arduinobluetoothcontroller.view.JoystickView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final float DEAD_ZONE = 0.12f;
    private static final int MAX_LOG_LINES = 200;
    private static final long DRIVE_INTERVAL_MS = 80L;

    private final Handler driveHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> logBuffer = new ArrayList<>();
    private final BluetoothController.DataListener dataListener =
            packet -> runOnUiThread(() -> handleIncomingPacket(packet));
    private final BluetoothController.ConnectionListener connectionListener =
            new BluetoothController.ConnectionListener() {
                @Override
                public void onConnected(BluetoothDevice device) {
                    statusTextView.setText(R.string.bluetooth_status_connected);
                    macTextView.setText(device.getAddress());
                    connectButton.setText(R.string.disconnect);
                    appendLog("Connected to " + device.getName());
                }

                @Override
                public void onDisconnected() {
                    statusTextView.setText(R.string.bluetooth_status_disconnected);
                    macTextView.setText(R.string.bluetooth_mac_placeholder);
                    connectButton.setText(R.string.connect);
                    appendLog("Disconnected.");
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            };

    private BluetoothController bluetoothController;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private Runnable pendingPermissionAction;

    private MaterialButton connectButton;
    private MaterialButton showLogButton;
    private MaterialButton openArmControlButton;
    private TextView statusTextView;
    private TextView macTextView;
    private TextView speedValueText;
    private TextView angleValueText;
    private TextView pwmValueText;
    private TextView tiltValueText;
    private TextView batteryValueText;
    private TextView latestLogText;
    private JoystickView joystickView;

    private int pendingDriveSpeed;
    private int pendingDriveAngle = 90;
    private boolean driveCommandScheduled = false;
    private long nextDriveWindow = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        initBluetooth();
        initPermissionLauncher();
        initActions();
    }

    private void initViews() {
        connectButton = findViewById(R.id.btnConnect);
        showLogButton = findViewById(R.id.btnShowLog);
        statusTextView = findViewById(R.id.textBluetoothStatus);
        macTextView = findViewById(R.id.textBluetoothMac);
        speedValueText = findViewById(R.id.textSpeedValue);
        angleValueText = findViewById(R.id.textAngleValue);
        pwmValueText = findViewById(R.id.textPwmValue);
        tiltValueText = findViewById(R.id.textTiltValue);
        batteryValueText = findViewById(R.id.textBatteryValue);
        latestLogText = findViewById(R.id.textLatestLog);
        joystickView = findViewById(R.id.joystickView);
        openArmControlButton = findViewById(R.id.btnOpenArmControl);
    }

    private void initBluetooth() {
        bluetoothController = BluetoothControllerProvider.getInstance(getApplicationContext());
        if (bluetoothController == null) {
            Toast.makeText(this, R.string.message_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }
        bluetoothController.addConnectionListener(connectionListener);
        bluetoothController.addDataListener(dataListener);
    }

    private void initPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted = true;
                    for (Boolean value : result.values()) {
                        if (!Boolean.TRUE.equals(value)) {
                            granted = false;
                            break;
                        }
                    }
                    if (granted) {
                        if (pendingPermissionAction != null) {
                            pendingPermissionAction.run();
                        }
                    } else {
                        Toast.makeText(this, R.string.message_permission_denied, Toast.LENGTH_SHORT).show();
                    }
                    pendingPermissionAction = null;
                });
    }

    private void initActions() {
        connectButton.setOnClickListener(v -> {
            if (bluetoothController != null && bluetoothController.isConnected()) {
                bluetoothController.disconnect();
            } else {
                ensureBluetoothReady(this::showPairedDevicesDialog);
            }
        });

        showLogButton.setOnClickListener(v -> showLogDialog());

        joystickView.setOnJoystickChangeListener(this::handleJoystickInput);

        if (openArmControlButton != null) {
            openArmControlButton.setOnClickListener(v ->
                    startActivity(new Intent(this, ArmControlActivity.class)));
        }
    }

    private void ensureBluetoothReady(Runnable onGranted) {
        if (bluetoothController == null || !bluetoothController.isBluetoothAvailable()) {
            Toast.makeText(this, R.string.message_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasConnect = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean hasScan = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;

            if (!hasConnect || !hasScan) {
                pendingPermissionAction = onGranted;
                permissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                });
                return;
            }
        }

        if (onGranted != null) {
            onGranted.run();
        }
    }

    private void showPairedDevicesDialog() {
        if (bluetoothController == null) {
            return;
        }
        Set<BluetoothDevice> devices = bluetoothController.getBondedDevices();
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.message_no_paired_devices, Toast.LENGTH_SHORT).show();
            return;
        }

        List<BluetoothDevice> deviceList = new ArrayList<>(devices);
        CharSequence[] entries = new CharSequence[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice device = deviceList.get(i);
            String name = TextUtils.isEmpty(device.getName()) ? "Unnamed" : device.getName();
            entries[i] = name + " (" + device.getAddress() + ")";
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_select_device)
                .setItems(entries, (dialog, which) -> connectToDevice(deviceList.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void connectToDevice(BluetoothDevice device) {
        statusTextView.setText(R.string.bluetooth_status_connecting);
        macTextView.setText(device.getAddress());
        if (bluetoothController != null) {
            bluetoothController.connect(device);
        }
    }

    private void showLogDialog() {
        LogDialogFragment.newInstance(new ArrayList<>(logBuffer))
                .show(getSupportFragmentManager(), "logs");
    }

    private void handleJoystickInput(float x, float y) {
        float processedX = applyDeadZone(x);
        float processedY = applyDeadZone(y);

        int direction = processedY >= 0 ? 1 : -1;
        int pwm = (int) (Math.min(1f, Math.abs(processedY)) * 255f);
        if (processedY == 0f) {
            pwm = 0;
            direction = 0;
        }

        int signedSpeed = direction * pwm;
        int angle = 90 + Math.round(processedX * 45f);
        angle = Math.max(45, Math.min(135, angle));

        speedValueText.setText(String.valueOf(signedSpeed));
        angleValueText.setText(String.valueOf(angle));

        queueDriveCommand(signedSpeed, angle);
    }

    private void queueDriveCommand(int speed, int angle) {
        pendingDriveSpeed = speed;
        pendingDriveAngle = angle;

        if (!driveCommandScheduled) {
            driveCommandScheduled = true;
            long delay = Math.max(0, nextDriveWindow - SystemClock.uptimeMillis());
            driveHandler.postDelayed(this::flushDriveCommand, delay);
        }
    }

    private void flushDriveCommand() {
        driveCommandScheduled = false;
        nextDriveWindow = SystemClock.uptimeMillis() + DRIVE_INTERVAL_MS;
        if (bluetoothController != null) {
            String command = String.format(Locale.US, "<DRV:%d:%d>", pendingDriveSpeed, pendingDriveAngle);
            bluetoothController.sendCommand(command);
        }
    }

    private float applyDeadZone(float value) {
        return Math.abs(value) < DEAD_ZONE ? 0f : value;
    }

    private void handleIncomingPacket(String packet) {
        if (TextUtils.isEmpty(packet)) {
            return;
        }

        if (packet.startsWith("<STAT:") && packet.endsWith(">")) {
            String payload = packet.substring(1, packet.length() - 1);
            String[] parts = payload.split(":");
            if (parts.length >= 4) {
                updateDashboard(parts[1], parts[2], parts[3]);
            } else if (parts.length >= 3) {
                updateDashboard(parts[1], parts[2], "0");
            }
        } else if (packet.startsWith("<LOG:") && packet.endsWith(">")) {
            appendLog(packet.substring(5, packet.length() - 1));
        } else {
            appendLog(packet);
        }
    }

    private void updateDashboard(String pwm, String tilt, String battery) {
        pwmValueText.setText(pwm);
        tiltValueText.setText(tilt);
        String value = battery.endsWith("V") ? battery : battery + "V";
        batteryValueText.setText(value);
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        logBuffer.add(line);
        if (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.remove(0);
        }
        latestLogText.setText(line);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        driveHandler.removeCallbacksAndMessages(null);
        if (bluetoothController != null) {
            bluetoothController.removeDataListener(dataListener);
            bluetoothController.removeConnectionListener(connectionListener);
            bluetoothController.disconnect();
        }
    }
}