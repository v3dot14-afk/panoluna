package com.panoluna.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * La levetta tattile del gimbal: un cerchio con un pomello.
 *
 * Il rilascio marca lo stop. Non serve un comando a ogni millimetro: il gimbal mantiene la
 * velocita' comandata finche' non arriva il vettore nullo, quindi la lente manda il vettore
 * mentre si trascina e il vettore nullo al rilascio.
 */
public final class JoystickView extends View {

    public interface Listener {
        /** horizontal e vertical sono in -1..1; vertical positivo = inclina verso l'alto. */
        void onMove(float horizontal, float vertical);

        void onRelease();
    }

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobEdge = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float knobX;
    private float knobY;
    private float radius;
    private Listener listener;
    private boolean dragging;

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f);
        ring.setColor(Color.parseColor("#3A3F45"));

        cross.setStyle(Paint.Style.STROKE);
        cross.setStrokeWidth(1.5f);
        cross.setColor(Color.parseColor("#2A2E33"));

        knob.setStyle(Paint.Style.FILL);
        knob.setColor(Color.parseColor("#FFB300"));

        knobEdge.setStyle(Paint.Style.STROKE);
        knobEdge.setStrokeWidth(2f);
        knobEdge.setColor(Color.parseColor("#121417"));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int size = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        radius = Math.min(centerX, centerY) - 6f;

        canvas.drawCircle(centerX, centerY, radius, ring);
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, cross);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, cross);

        float cx = centerX + knobX * radius * 0.8f;
        float cy = centerY + knobY * radius * 0.8f;
        canvas.drawCircle(cx, cy, radius * 0.30f, knob);
        canvas.drawCircle(cx, cy, radius * 0.30f, knobEdge);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null) {
            return false;
        }
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float max = radius > 0 ? radius : Math.min(centerX, centerY);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                updateFromTouch(event.getX(), event.getY(), centerX, centerY, max);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updateFromTouch(event.getX(), event.getY(), centerX, centerY, max);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                knobX = 0f;
                knobY = 0f;
                invalidate();
                listener.onRelease();
                return true;
            default:
                return true;
        }
    }

    private void updateFromTouch(float x, float y, float centerX, float centerY, float max) {
        float dx = (x - centerX) / max;
        float dy = (y - centerY) / max;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > 1f) {
            dx /= length;
            dy /= length;
        }
        knobX = dx;
        knobY = dy;
        invalidate();
        // verticale positivo verso l'alto: lo schermo cresce verso il basso.
        listener.onMove(dx, -dy);
    }
}
