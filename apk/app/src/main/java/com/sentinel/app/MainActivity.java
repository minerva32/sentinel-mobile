package com.sentinel.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Color;

public class MainActivity extends Activity {
    private WebView webView;
    private static final String TAG = "Sentinel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate start");

        // Keep screen on to prevent Samsung FreecessController from freezing WebView timers
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.d(TAG, "FLAG_KEEP_SCREEN_ON set");

        // Request battery optimization exemption
        requestBatteryExemption();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#030008"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        setContentView(webView);
        webView.requestFocus();
        WebView.setWebContentsDebuggingEnabled(true);
        Log.d(TAG, "WebView created, debugging enabled");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // Disable hardware acceleration to avoid GPU OOM with canvas
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        Log.d(TAG, "Layer set to SOFTWARE (no GPU accel)");

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void log(String msg) { Log.d("SentinelJS", msg); }
        }, "Console");
        Log.d(TAG, "JS interface added");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) {
                Log.d(TAG, "Permission request: " + r.getResources().length + " resources");
                runOnUiThread(() -> r.grant(r.getResources()));
            }
            @Override public void onConsoleMessage(String message, int lineNumber, String sourceID) {
                Log.d("SentinelJS", message + " -- line " + lineNumber + " of " + sourceID);
            }
        });
        webView.setWebViewClient(new WebViewClient(){
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded: " + url);
                view.postDelayed(() -> {
                    Log.d(TAG, "Auto-triggering speech test");
                    view.evaluateJavascript("if(typeof startListening==='function')startListening()", null);
                }, 4000);
            }
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error " + errorCode + ": " + description + " at " + failingUrl);
            }
        });
        Log.d(TAG, "Loading index.html...");
        webView.loadUrl("file:///android_asset/index.html");

        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (dx < 30 && dy < 30) {
                    Log.d(TAG, "TAP at " + event.getX() + "," + event.getY());
                    webView.evaluateJavascript("if(typeof handleTap==='function')handleTap()", null);
                }
                downX = 0; downY = 0;
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY();
                return true;
            }
            return true;
        });
        Log.d(TAG, "onCreate end");
    }
    private float downX, downY;

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Log.d(TAG, "Battery opt intent failed: " + e.getMessage());
            }
        }
    }
}
