package com.sentinel.app;

import android.app.Service;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;

public class OverlayPetService extends Service {
    private static OverlayPetService instance;
    private WindowManager windowManager;
    private FrameLayout root;
    private PetView petView;
    private TextView bubble;
    private WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float downRawX, downRawY;
    private int downX, downY;
    private long downTime;
    private long lastTapTime;
    private boolean quietMode = false;
    private boolean absorbing = false;

    public static void showBubble(String text) {
        OverlayPetService service = instance;
        if (service != null) service.setBubble(text);
    }

    public static boolean isVisible() {
        return instance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildView();
        windowManager.addView(root, params);
        petView.start();
        setBubble("standby");
    }

    private void buildView() {
        int size = dp(72);
        int bubbleWidth = dp(124);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        bubble = new TextView(this);
        bubble.setTextColor(Color.argb(220, 255, 255, 255));
        bubble.setTextSize(11);
        bubble.setMaxLines(4);
        bubble.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setColor(Color.argb(185, 10, 6, 18));
        bubbleBg.setCornerRadius(dp(12));
        bubbleBg.setStroke(1, Color.argb(50, 139, 92, 246));
        bubble.setBackground(bubbleBg);
        FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(bubbleWidth, WindowManager.LayoutParams.WRAP_CONTENT);
        bubbleParams.leftMargin = 0;
        bubbleParams.topMargin = 0;
        root.addView(bubble, bubbleParams);

        petView = new PetView(this);
        FrameLayout.LayoutParams petParams = new FrameLayout.LayoutParams(size, size);
        petParams.leftMargin = dp(26);
        petParams.topMargin = dp(62);
        root.addView(petView, petParams);

        root.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = params.x;
                    downY = params.y;
                    downTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = downX + (int) (event.getRawX() - downRawX);
                    params.y = downY + (int) (event.getRawY() - downRawY);
                    windowManager.updateViewLayout(root, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getRawX() - downRawX);
                    float dy = Math.abs(event.getRawY() - downRawY);
                    long elapsed = System.currentTimeMillis() - downTime;
                    if (dy > dp(70) && dy > dx * 1.8f) {
                        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        stopSelf();
                        return true;
                    }
                    if (dx < dp(10) && dy < dp(10)) {
                        if (elapsed < 300) {
                            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            setBubble("open sentinel");
                            openMain();
                        } else if (elapsed > 500) {
                            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            quietMode = !quietMode;
                            petView.setQuietMode(quietMode);
                            setBubble(quietMode ? "quiet" : "active");
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });

        params = new WindowManager.LayoutParams(
                dp(152),
                dp(140),
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(18);
        params.y = dp(160);
    }

    private void setBubble(String text) {
        handler.post(() -> {
            if (bubble == null) return;
            String value = text == null || text.trim().isEmpty() ? "standby" : text.trim();
            bubble.setText(value.length() > 120 ? value.substring(0, 117) + "..." : value);
            bubble.setVisibility(View.VISIBLE);
            bubble.animate().alpha(1f).setDuration(120).start();
            handler.removeCallbacks(hideBubble);
            handler.postDelayed(hideBubble, value.equals("standby") ? 1800 : 5200);
        });
    }

    private final Runnable hideBubble = () -> {
        if (bubble != null) bubble.animate().alpha(0f).setDuration(240).start();
    };

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("stop".equals(action)) {
                stopSelf();
            } else if ("absorb".equals(action)) {
                absorbIntoOrb();
            }
        }
        return START_STICKY;
    }

    private void absorbIntoOrb() {
        if (absorbing || root == null || params == null || windowManager == null) return;
        absorbing = true;
        handler.removeCallbacks(hideBubble);
        if (bubble != null) bubble.animate().alpha(0f).setDuration(120).start();

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int startX = params.x;
        int startY = params.y;
        int targetX = Math.max(0, metrics.widthPixels / 2 - dp(76));
        int targetY = Math.max(0, (int) (metrics.heightPixels * .43f) - dp(58));
        root.setPivotX(root.getWidth() * .5f);
        root.setPivotY(root.getHeight() * .68f);
        root.animate().scaleX(.12f).scaleY(.12f).alpha(0f).setDuration(460).start();

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(460);
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float eased = 1f - (float) Math.pow(1f - t, 3);
            params.x = startX + Math.round((targetX - startX) * eased);
            params.y = startY + Math.round((targetY - startY) * eased);
            try {
                windowManager.updateViewLayout(root, params);
            } catch (Exception ignored) {
            }
            if (t >= 1f) stopSelf();
        });
        animator.start();
    }

    @Override
    public void onDestroy() {
        if (petView != null) petView.stop();
        if (windowManager != null && root != null) windowManager.removeView(root);
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class PetView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final float[] nodePhase = new float[]{0f, .7f, 1.4f, 2.1f, 2.9f, 3.6f, 4.4f, 5.2f};
        private final RectF oval = new RectF();
        private float tick = 0f;
        private boolean running = false;
        private boolean quietMode = false;
        private final Runnable frame = new Runnable() {
            @Override public void run() {
                if (!running) return;
                tick += .05f;
                invalidate();
                handler.postDelayed(this, 33);
            }
        };

        PetView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        void start() {
            if (running) return;
            running = true;
            frame.run();
        }

        void stop() {
            running = false;
            handler.removeCallbacks(frame);
        }

        void setQuietMode(boolean enabled) {
            quietMode = enabled;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h) / 80f;
            float cx = w * .5f;
            float cy = h * .52f + (float) Math.sin(tick * 1.5f) * 1.6f * s;
            float dim = quietMode ? .42f : 1f;
            float pulse = (float) Math.sin(tick * 2.2f) * 2.4f * s;

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, (36f + pulse) * s,
                    new int[]{Color.argb((int) (215 * dim), 255, 255, 255), Color.argb((int) (170 * dim), 139, 92, 246), Color.TRANSPARENT},
                    new float[]{0f, .28f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, (36f + pulse) * s, paint);
            paint.setShader(null);

            float rotation = tick * .28f;
            float[][] nodes = new float[nodePhase.length][2];
            for (int i = 0; i < nodePhase.length; i++) {
                float a = rotation + nodePhase[i];
                float r = (28f + (float) Math.sin(tick * 1.1f + i) * 3f) * s;
                nodes[i][0] = cx + (float) Math.cos(a) * r;
                nodes[i][1] = cy + (float) Math.sin(a) * r * .72f;
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.05f * s);
            paint.setColor(Color.argb((int) (78 * dim), 103, 232, 249));
            for (int i = 0; i < nodes.length; i++) {
                int next = (i + 1) % nodes.length;
                canvas.drawLine(nodes[i][0], nodes[i][1], nodes[next][0], nodes[next][1], paint);
                if (i % 2 == 0) canvas.drawLine(nodes[i][0], nodes[i][1], cx, cy, paint);
            }

            canvas.save();
            canvas.rotate((float) Math.toDegrees(rotation * .7f), cx, cy);
            oval.set(cx - 37f * s, cy - 13f * s, cx + 37f * s, cy + 13f * s);
            paint.setStrokeWidth(1.6f * s);
            paint.setColor(Color.argb((int) (135 * dim), 167, 139, 250));
            canvas.drawOval(oval, paint);
            oval.set(cx - 27f * s, cy - 8f * s, cx + 27f * s, cy + 8f * s);
            paint.setStrokeWidth(.8f * s);
            paint.setColor(Color.argb((int) (90 * dim), 6, 182, 212));
            canvas.drawOval(oval, paint);
            canvas.restore();

            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < nodes.length; i++) {
                float glow = (3.2f + (float) Math.sin(tick * 2f + i) * .7f) * s;
                paint.setColor(Color.argb((int) (210 * dim), i % 3 == 0 ? 216 : 103, i % 3 == 0 ? 180 : 232, i % 3 == 0 ? 254 : 249));
                canvas.drawCircle(nodes[i][0], nodes[i][1], glow, paint);
            }

            paint.setShader(new RadialGradient(cx, cy, (16f + pulse * .4f) * s,
                    new int[]{Color.argb((int) (245 * dim), 255, 255, 255), Color.argb((int) (210 * dim), 103, 232, 249), Color.TRANSPARENT},
                    new float[]{0f, .42f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, (17f + pulse * .35f) * s, paint);
            paint.setShader(null);
        }
    }
}
