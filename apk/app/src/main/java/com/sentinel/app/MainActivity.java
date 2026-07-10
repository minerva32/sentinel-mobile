package com.sentinel.app;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
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
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private SpeechRecognizer wakeRecognizer;
    private Intent wakeIntent;
    private boolean ttsReady = false;
    private boolean pageReady = false;
    private boolean nativeWakeActive = false;
    private boolean nativeWakeListening = false;
    private static final int REQ_RECORD_AUDIO = 42;
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
        requestMicPermissionIfNeeded();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#030008"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        setContentView(webView);
        webView.requestFocus();
        WebView.setWebContentsDebuggingEnabled(true);
        Log.d(TAG, "WebView created, debugging enabled");
        initTts();
        initNativeWake();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        Log.d(TAG, "Layer set to HARDWARE for 60fps canvas");

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void log(String msg) { Log.d("SentinelJS", msg); }
        }, "Console");
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public boolean speak(String msg) {
                if (!ttsReady || tts == null) {
                    Log.d(TAG, "NativeTTS not ready");
                    return false;
                }
                OverlayPetService.showBubble(msg);
                runOnUiThread(() -> {
                    tts.stop();
                    tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "sentinel-utterance");
                });
                return true;
            }

            @android.webkit.JavascriptInterface
            public boolean canSpeak() {
                return ttsReady;
            }

            @android.webkit.JavascriptInterface
            public boolean isPrivateAudio() {
                return isPrivateAudioConnected();
            }

            @android.webkit.JavascriptInterface
            public void stop() {
                if (tts != null) runOnUiThread(() -> tts.stop());
            }
        }, "NativeTTS");
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public boolean start() {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(MainActivity.this)) {
                    runOnUiThread(MainActivity.this::requestOverlayPermission);
                    return false;
                }
                runOnUiThread(() -> startService(new Intent(MainActivity.this, OverlayPetService.class)));
                return true;
            }

            @android.webkit.JavascriptInterface
            public void stop() {
                runOnUiThread(() -> {
                    Intent intent = new Intent(MainActivity.this, OverlayPetService.class);
                    intent.setAction("stop");
                    startService(intent);
                });
            }

            @android.webkit.JavascriptInterface
            public boolean hasPermission() {
                return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this);
            }
        }, "NativeOverlay");
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public boolean start() {
                if (!hasMicPermission() || wakeRecognizer == null) {
                    Log.d(TAG, "NativeWake start blocked permission=" + hasMicPermission() + " recognizer=" + (wakeRecognizer != null));
                    return false;
                }
                runOnUiThread(() -> {
                    nativeWakeActive = true;
                    startNativeWakeListening();
                });
                return true;
            }

            @android.webkit.JavascriptInterface
            public void stop() {
                runOnUiThread(() -> {
                    nativeWakeActive = false;
                    nativeWakeListening = false;
                    if (wakeRecognizer != null) wakeRecognizer.cancel();
                });
            }
        }, "NativeWake");
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
                pageReady = true;
                maybeStartWakeWord();
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

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicPermissionIfNeeded() {
        if (!hasMicPermission() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    private void maybeStartWakeWord() {
        if (!pageReady || !hasMicPermission() || webView == null) return;
        webView.postDelayed(() -> webView.evaluateJavascript("if(typeof startWakeWord==='function')startWakeWord()", null), 700);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "RECORD_AUDIO permission granted=" + granted);
            maybeStartWakeWord();
        }
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.KOREAN);
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        Log.d(TAG, "NativeTTS started");
                    }
                    @Override public void onDone(String utteranceId) {
                        runOnUiThread(() -> {
                            if (webView != null) webView.evaluateJavascript("if(window.onNativeTtsDone)onNativeTtsDone()", null);
                        });
                    }
                    @Override public void onError(String utteranceId) {
                        runOnUiThread(() -> {
                            if (webView != null) webView.evaluateJavascript("if(window.onNativeTtsError)onNativeTtsError()", null);
                        });
                    }
                });
                Log.d(TAG, "NativeTTS ready=" + ttsReady);
            } else {
                Log.d(TAG, "NativeTTS init failed: " + status);
            }
        });
    }

    private void initNativeWake() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.d(TAG, "NativeWake unavailable");
            return;
        }
        wakeIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        wakeIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        wakeIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        wakeIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        wakeIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        wakeIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500);
        wakeIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500);

        createNativeWakeRecognizer();
        Log.d(TAG, "NativeWake initialized");
    }

    private void createNativeWakeRecognizer() {
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        wakeRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                nativeWakeListening = true;
                Log.d(TAG, "NativeWake ready");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                nativeWakeListening = false;
                scheduleNativeWakeRestart(250);
            }
            @Override public void onError(int error) {
                nativeWakeListening = false;
                Log.d(TAG, "NativeWake error=" + error);
                if (error == SpeechRecognizer.ERROR_CLIENT || error == 11) {
                    resetNativeWakeRecognizer();
                    nativeWakeActive = false;
                    if (webView != null) {
                        webView.evaluateJavascript("localStorage.setItem('sentinel_native_wake','off');if(window.onNativeWakeStopped)onNativeWakeStopped()", null);
                    }
                    Log.d(TAG, "NativeWake disabled for quiet standby after error=" + error);
                    return;
                }
                long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200 : 1800;
                scheduleNativeWakeRestart(delay);
            }
            @Override public void onResults(Bundle results) {
                nativeWakeListening = false;
                forwardWakeResults(results);
                scheduleNativeWakeRestart(250);
            }
            @Override public void onPartialResults(Bundle partialResults) {
                forwardWakeResults(partialResults);
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void resetNativeWakeRecognizer() {
        try {
            if (wakeRecognizer != null) {
                wakeRecognizer.cancel();
                wakeRecognizer.destroy();
            }
        } catch (Exception e) {
            Log.d(TAG, "NativeWake reset cleanup failed: " + e.getMessage());
        }
        createNativeWakeRecognizer();
        Log.d(TAG, "NativeWake reset");
    }

    private void startNativeWakeListening() {
        if (!nativeWakeActive || wakeRecognizer == null || wakeIntent == null || nativeWakeListening) return;
        try {
            wakeRecognizer.startListening(wakeIntent);
            nativeWakeListening = true;
        } catch (Exception e) {
            nativeWakeListening = false;
            Log.d(TAG, "NativeWake start failed: " + e.getMessage());
            scheduleNativeWakeRestart(900);
        }
    }

    private void scheduleNativeWakeRestart(long delayMs) {
        if (!nativeWakeActive || webView == null) return;
        webView.postDelayed(this::startNativeWakeListening, delayMs);
    }

    private void forwardWakeResults(Bundle bundle) {
        if (!nativeWakeActive || webView == null || bundle == null) return;
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        String text = matches.get(0);
        if (text == null || text.trim().isEmpty()) return;
        String js = "if(window.onNativeWakeHeard)onNativeWakeHeard(" + JSONObject.quote(text.trim()) + ")";
        webView.evaluateJavascript(js, null);
    }

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

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.d(TAG, "Overlay permission intent failed: " + e.getMessage());
        }
    }

    private void startOverlayIfAllowed() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        startService(new Intent(this, OverlayPetService.class));
    }

    private void absorbOverlayIfVisible() {
        if (!OverlayPetService.isVisible()) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        Intent intent = new Intent(this, OverlayPetService.class);
        intent.setAction("absorb");
        startService(intent);
    }

    private boolean isPrivateAudioConnected() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_HEARING_AID
                        || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    return true;
                }
            }
            return false;
        }
        return audio.isWiredHeadsetOn() || audio.isBluetoothA2dpOn() || audio.isBluetoothScoOn();
    }

    @Override
    protected void onDestroy() {
        nativeWakeActive = false;
        if (wakeRecognizer != null) {
            wakeRecognizer.cancel();
            wakeRecognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        absorbOverlayIfVisible();
    }

    @Override
    protected void onPause() {
        super.onPause();
        startOverlayIfAllowed();
    }
}
