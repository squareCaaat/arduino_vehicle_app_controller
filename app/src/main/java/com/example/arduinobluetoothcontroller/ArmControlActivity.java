package com.example.arduinobluetoothcontroller;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.arduinobluetoothcontroller.bluetooth.BluetoothController;
import com.example.arduinobluetoothcontroller.bluetooth.BluetoothControllerProvider;
import com.google.android.material.slider.Slider;

import java.util.Locale;

public class ArmControlActivity extends AppCompatActivity {

    private static final long ARM_INTERVAL_MS = 50L;
    private static final int MIN_ANGLE = 0;
    private static final int MAX_ANGLE = 180;
    private static final int DEFAULT_ANGLE = 90;

    private final Handler armHandler = new Handler(Looper.getMainLooper());
    private final android.util.SparseIntArray jointAngles = new android.util.SparseIntArray();

    private BluetoothController bluetoothController;
    private TextView statusText;
    private TextView baseAngleText;
    private TextView link1AngleText;
    private TextView link2AngleText;
    private Slider sliderLink1;
    private Slider sliderLink2;

    private final BluetoothController.ConnectionListener connectionListener =
            new BluetoothController.ConnectionListener() {
                @Override
                public void onConnected(BluetoothDevice device) {
                    updateStatusText();
                }

                @Override
                public void onDisconnected() {
                    updateStatusText();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(ArmControlActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_arm_control);

        bluetoothController = BluetoothControllerProvider.getInstance(getApplicationContext());
        if (bluetoothController != null) {
            bluetoothController.addConnectionListener(connectionListener);
        }

        initViews();
        initializeAngles();
        initActions();
        updateStatusText();
    }

    private void initViews() {
        statusText = findViewById(R.id.textArmStatus);
        baseAngleText = findViewById(R.id.textBaseAngle);
        link1AngleText = findViewById(R.id.textLink1Angle);
        link2AngleText = findViewById(R.id.textLink2Angle);
        sliderLink1 = findViewById(R.id.sliderLink1);
        sliderLink2 = findViewById(R.id.sliderLink2);
    }

    private void initializeAngles() {
        jointAngles.put(0, DEFAULT_ANGLE);
        jointAngles.put(1, DEFAULT_ANGLE);
        jointAngles.put(2, DEFAULT_ANGLE);
        updateAngleDisplay(0, DEFAULT_ANGLE);
        updateAngleDisplay(1, DEFAULT_ANGLE);
        updateAngleDisplay(2, DEFAULT_ANGLE);
    }

    private void initActions() {
        configureLinkSlider(sliderLink1, 1);
        configureLinkSlider(sliderLink2, 2);
        attachBaseControl(R.id.btnBaseLeft, -1);
        attachBaseControl(R.id.btnBaseRight, 1);

        View grabButton = findViewById(R.id.btnGripperGrab);
        View releaseButton = findViewById(R.id.btnGripperRelease);
        grabButton.setOnClickListener(v -> sendGripperCommand(true));
        releaseButton.setOnClickListener(v -> sendGripperCommand(false));

    }

    private void updateStatusText() {
        if (statusText == null) {
            return;
        }
        boolean connected = bluetoothController != null && bluetoothController.isConnected();
        statusText.setText(connected
                ? getString(R.string.bluetooth_status_connected)
                : getString(R.string.bluetooth_status_disconnected));
    }

    private void configureLinkSlider(Slider slider, int jointId) {
        if (slider == null) {
            return;
        }
        int initial = jointAngles.get(jointId, DEFAULT_ANGLE);
        slider.setValue(initial);
        slider.addOnChangeListener((s, value, fromUser) -> {
            int angle = Math.round(value);
            jointAngles.put(jointId, angle);
            updateAngleDisplay(jointId, angle);
            if (fromUser) {
                sendArmCommand(jointId, angle);
            }
        });
    }

    private void attachBaseControl(int viewId, int direction) {
        View button = findViewById(viewId);
        if (button == null) {
            return;
        }

        button.setOnTouchListener(new View.OnTouchListener() {
            private final Runnable repeatTask = new Runnable() {
                @Override
                public void run() {
                    long held = SystemClock.uptimeMillis() - touchStartTime;
                    int step = calculateStep(held) * direction;
                    applyBaseStep(step);
                    armHandler.postDelayed(this, ARM_INTERVAL_MS);
                }
            };

            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartTime = SystemClock.uptimeMillis();
                        armHandler.post(repeatTask);
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        armHandler.removeCallbacks(repeatTask);
                        v.setPressed(false);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private int calculateStep(long heldMillis) {
        if (heldMillis > 2000) {
            return 5;
        } else if (heldMillis > 1000) {
            return 2;
        }
        return 1;
    }

    private void applyBaseStep(int delta) {
        int jointId = 0;
        int current = jointAngles.get(jointId, DEFAULT_ANGLE);
        int updated = Math.max(MIN_ANGLE, Math.min(MAX_ANGLE, current + delta));
        if (updated == current) {
            return;
        }
        jointAngles.put(jointId, updated);
        updateAngleDisplay(jointId, updated);
        sendArmCommand(jointId, updated);
    }

    private void updateAngleDisplay(int jointId, int angle) {
        String formatted = formatAngle(angle);
        switch (jointId) {
            case 0:
                baseAngleText.setText(formatted);
                break;
            case 1:
                link1AngleText.setText(formatted);
                break;
            case 2:
                link2AngleText.setText(formatted);
                break;
            default:
                break;
        }
    }

    private String formatAngle(int angle) {
        return String.format(Locale.US, "%03d deg", angle);
    }

    private void sendArmCommand(int jointId, int angle) {
        if (bluetoothController != null) {
            bluetoothController.sendCommand(
                    String.format(Locale.US, "<ARM:%d:%d>", jointId, angle)
            );
        }
    }

    private void sendGripperCommand(boolean grab) {
        if (bluetoothController != null) {
            bluetoothController.sendCommand(
                    String.format(Locale.US, "<GRP:%d>", grab ? 1 : 0)
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        armHandler.removeCallbacksAndMessages(null);
        if (bluetoothController != null) {
            bluetoothController.removeConnectionListener(connectionListener);
        }
    }
}

