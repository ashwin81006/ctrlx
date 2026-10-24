package com.ctrlx.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * High-performance virtual analog thumbstick with multi-touch isolation,
 * smooth spring-back physics, and console-grade visual aesthetics.
 */
public class JoystickView extends View {
    public interface OnJoystickMoveListener {
        void onMove(float x, float y);
    }

    private Paint basePaint;
    private Paint baseRingPaint;
    private Paint hatPaint;

    private float centerX = 0f;
    private float centerY = 0f;
    private float baseRadius = 0f;
    private float hatRadius = 0f;
    private float hatX = 0f;
    private float hatY = 0f;

    private int activePointerId = -1;
    private OnJoystickMoveListener listener;
    private String label = "L";

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.parseColor("#070E1A"));
        basePaint.setStyle(Paint.Style.FILL);

        baseRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseRingPaint.setColor(Color.parseColor("#155E75"));
        baseRingPaint.setStyle(Paint.Style.STROKE);
        baseRingPaint.setStrokeWidth(4.5f);

        hatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hatPaint.setColor(Color.parseColor("#00E5FF"));
        hatPaint.setStyle(Paint.Style.FILL);
    }

    public void setLabel(String label) {
        this.label = label != null ? label : "";
        invalidate();
    }

    public void setOnJoystickMoveListener(OnJoystickMoveListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) * 0.46f;
        hatRadius = baseRadius * 0.35f;
        hatX = centerX;
        hatY = centerY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Outer base circle with thin cyan border
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, baseRingPaint);

        // Solid vibrant cyan thumbstick hat
        canvas.drawCircle(hatX, hatY, hatRadius, hatPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pointerId = event.getPointerId(actionIndex);
                float x = event.getX(actionIndex);
                float y = event.getY(actionIndex);
                float dist = (float) Math.hypot(x - centerX, y - centerY);

                if (dist <= baseRadius * 1.4f && activePointerId == -1) {
                    activePointerId = pointerId;
                    updateHatPosition(x, y);
                    return true;
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (activePointerId != -1) {
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex != -1) {
                        float x = event.getX(pointerIndex);
                        float y = event.getY(pointerIndex);
                        updateHatPosition(x, y);
                        return true;
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerId = event.getPointerId(actionIndex);
                if (pointerId == activePointerId) {
                    activePointerId = -1;
                    resetHat();
                    return true;
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                activePointerId = -1;
                resetHat();
                return true;
            }
        }

        return super.onTouchEvent(event);
    }

    private void updateHatPosition(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.hypot(dx, dy);
        float maxTravel = baseRadius - hatRadius;

        if (distance <= maxTravel) {
            hatX = x;
            hatY = y;
        } else {
            hatX = centerX + (dx / distance) * maxTravel;
            hatY = centerY + (dy / distance) * maxTravel;
        }

        invalidate();

        if (listener != null) {
            float normX = (hatX - centerX) / maxTravel;
            float normY = (hatY - centerY) / maxTravel;
            normX = Math.max(-1.0f, Math.min(1.0f, normX));
            normY = Math.max(-1.0f, Math.min(1.0f, normY));
            listener.onMove(normX, normY);
        }
    }

    private void resetHat() {
        hatX = centerX;
        hatY = centerY;
        invalidate();
        if (listener != null) {
            listener.onMove(0f, 0f);
        }
    }
}
