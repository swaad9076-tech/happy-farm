package com.waad.happyfarm;

import android.os.Bundle;
import android.view.accessibility.AccessibilityManager;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String PREF = "farm_voice";
    private static final String KEY_VOICE = "voice_name";
    private static final String GOOGLE_TTS = "com.google.android.tts";

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean triedFallback = false;
    private boolean screenReader = false;
    private String engineUsed = "";
    private String currentVoice = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        screenReader = am != null && am.isEnabled() && am.isTouchExplorationEnabled();

        initTts(GOOGLE_TTS);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        web.setBackgroundColor(0xFF12180F);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        web.loadUrl("file:///android_asset/index.html");

        setContentView(web);
    }

    /* ---------------- محرك النطق ---------------- */

    private void initTts(String enginePkg) {
        TextToSpeech.OnInitListener listener = status -> onTtsInit(status, enginePkg);
        try {
            if (enginePkg == null) {
                tts = new TextToSpeech(this, listener);
                engineUsed = "";
            } else {
                tts = new TextToSpeech(this, listener, enginePkg);
                engineUsed = enginePkg;
            }
        } catch (Exception e) {
            tts = null;
            ttsReady = false;
        }
    }

    private void onTtsInit(int status, String enginePkg) {
        if (status != TextToSpeech.SUCCESS) {
            // محرك جوجل غير مثبت: نرجع للمحرك الافتراضي مرة واحدة
            if (GOOGLE_TTS.equals(enginePkg) && !triedFallback) {
                triedFallback = true;
                try {
                    if (tts != null) tts.shutdown();
                } catch (Exception ignored) {
                }
                initTts(null);
            }
            return;
        }
        ttsReady = true;
        configureTts();
    }

    private void configureTts() {
        int ar = tts.setLanguage(new Locale("ar"));
        if (ar == TextToSpeech.LANG_MISSING_DATA || ar == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(new Locale("ar", "EG"));
            tts.setLanguage(Locale.getDefault());
        }
        tts.setSpeechRate(0.95f);
        tts.setPitch(1.0f);
        chooseBestVoice();
    }

    /** يختار الصوت العربي المحفوظ، أو أفضل صوت عربي محلي على الجهاز. */
    private void chooseBestVoice() {
        String saved = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_VOICE, null);
        Voice best = null;
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            if (saved != null) {
                for (Voice v : voices) {
                    if (v.getName().equals(saved)) {
                        best = v;
                        break;
                    }
                }
            }
            if (best == null) {
                int bestScore = Integer.MIN_VALUE;
                for (Voice v : voices) {
                    Locale l = v.getLocale();
                    if (!"ar".equals(l.getLanguage())) continue;
                    int score = v.getQuality();
                    if (!v.isNetworkConnectionRequired()) score += 600;
                    if ("EG".equals(l.getCountry())) score += 300;
                    if (score > bestScore) {
                        bestScore = score;
                        best = v;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (best != null) {
            try {
                tts.setVoice(best);
                currentVoice = best.getName();
            } catch (Exception ignored) {
            }
        }
    }

    private class Bridge {

        @JavascriptInterface
        public boolean hasScreenReader() {
            return screenReader;
        }

        @JavascriptInterface
        public void speak(String text) {
            if (!ttsReady || text == null) return;
            tts.stop();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "farm");
        }

        @JavascriptInterface
        public void stopSpeaking() {
            if (ttsReady) tts.stop();
        }

        /** معلومات الصوت الحالي — يعرضها التطبيق في الإعدادات. */
        @JavascriptInterface
        public String voiceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("engine", engineUsed == null ? "" : engineUsed);
                o.put("chosen", currentVoice);
                if (ttsReady) {
                    Voice v = tts.getVoice();
                    if (v != null) {
                        o.put("name", v.getName());
                        o.put("locale", v.getLocale().toString());
                        o.put("network", v.isNetworkConnectionRequired());
                    }
                }
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** كل الأصوات العربية المثبتة على الجهاز. */
        @JavascriptInterface
        public String listArabicVoices() {
            JSONArray arr = new JSONArray();
            if (!ttsReady) return arr.toString();
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices == null) return arr.toString();
                List<Voice> list = new ArrayList<>(voices);
                Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
                for (Voice v : list) {
                    if (!"ar".equals(v.getLocale().getLanguage())) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("locale", v.getLocale().toString());
                    o.put("network", v.isNetworkConnectionRequired());
                    o.put("quality", v.getQuality());
                    arr.put(o);
                }
            } catch (Exception ignored) {
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean setVoice(String name) {
            if (!ttsReady || name == null) return false;
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices == null) return false;
                for (Voice v : voices) {
                    if (v.getName().equals(name)) {
                        tts.setVoice(v);
                        currentVoice = name;
                        getSharedPreferences(PREF, MODE_PRIVATE)
                                .edit().putString(KEY_VOICE, name).apply();
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
            return false;
        }

        @JavascriptInterface
        public void testVoice() {
            speak("مرحبًا، هذا هو الصوت المختار. المزرعة السعيدة تعمل الآن بالكلام. " +
                    "رصيدك ألف وخمسمئة عملة.");
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(ms);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void finishApp() {
            runOnUiThread(MainActivity.this::finish);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
