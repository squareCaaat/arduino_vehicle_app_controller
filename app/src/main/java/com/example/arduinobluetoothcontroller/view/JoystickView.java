package com.example.arduinobluetoothcontroller.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.arduinobluetoothcontroller.R;

public class JoystickView extends View {

    public interface OnJoystickChangeListener {
        void onChanged(float normalizedX, float normalizedY);
    }

    private static final float KNOB_RADIUS_RATIO = 0.25f;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float centerX;
    private float centerY;
    private float baseRadius;
    private float knobRadius;
    private float knobX;
    private float knobY;

    private OnJoystickChangeListener listener;

    public JoystickView(Context context) {
        super(context);
        init(context);
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        int panelColor = ContextCompat.getColor(context, R.color.panel_surface);
        int strokeColor = ContextCompat.getColor(context, R.color.panel_stroke);
        int accentColor = ContextCompat.getColor(context, R.color.accent_teal);

        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(panelColor);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3));
        ringPaint.setColor(strokeColor);

        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(accentColor);
        knobPaint.setShadowLayer(dp(6), 0, dp(4), Color.argb(120, 0, 0, 0));
        setLayerType(LAYER_TYPE_SOFTWARE, knobPaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float diameter = Math.min(w, h);
        baseRadius = diameter / 2f - dp(8);
        knobRadius = baseRadius * KNOB_RADIUS_RATIO;
        centerX = w / 2f;
        centerY = h / 2f;
        resetKnob();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint);
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                updateKnob(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetKnob();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void resetKnob() {
        knobX = centerX;
        knobY = centerY;
        notifyListener(0f, 0f);
        invalidate();
    }

    private void updateKnob(float touchX, float touchY) {
        float dx = touchX - centerX;
        float dy = touchY - centerY;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (distance > baseRadius) {
            double scale = baseRadius / distance;
            dx *= scale;
            dy *= scale;
        }

        knobX = centerX + dx;
        knobY = centerY + dy;

        float normalizedX = dx / baseRadius;
        float normalizedY = -(dy / baseRadius);

        notifyListener(normalizedX, normalizedY);
        invalidate();
    }

    private void notifyListener(float normalizedX, float normalizedY) {
        if (listener != null) {
            listener.onChanged(normalizedX, normalizedY);
        }
    }

    public void setOnJoystickChangeListener(OnJoystickChangeListener listener) {
        this.listener = listener;
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}


