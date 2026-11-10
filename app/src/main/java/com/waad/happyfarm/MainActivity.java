package com.waad.happyfarm;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Base64;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREF = "farm_voice";
    private static final String KEY_VOICE = "voice_name";
    private static final String KEY_ENGINE = "engine_pkg";
    private static final String GOOGLE_TTS = "com.google.android.tts";

    /* الصوت السحابي (Gemini TTS) */
    private static final String PREFS = "farm_cloud";
    private static final String KEY_CLOUD_KEY = "cloud_key";
    private static final String KEY_CLOUD_ON = "cloud_on";
    private static final String KEY_CLOUD_VOICE = "cloud_voice";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"
            + "gemini-2.5-flash-preview-tts:generateContent?key=";

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean triedFallback = false;
    private boolean screenReader = false;
    private String engineUsed = "";
    private String currentVoice = "";

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private int speakSeq = 0;
    private String lastCloudError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        screenReader = am != null && am.isEnabled() && am.isTouchExplorationEnabled();

        String savedEngine = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_ENGINE, GOOGLE_TTS);
        initTts(savedEngine == null || savedEngine.isEmpty() ? GOOGLE_TTS : savedEngine);

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
            // المحرك المطلوب غير مثبت: نرجع للمحرك الافتراضي مرة واحدة
            if (enginePkg != null && !triedFallback) {
                triedFallback = true;
                getSharedPreferences(PREF, MODE_PRIVATE).edit().remove(KEY_ENGINE).apply();
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

    /** اسم المحرك المقروء من قائمة المحركات المتسطبة. */
    private String engineLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "محرك النظام الافتراضي";
        if (tts == null) return pkg;
        try {
            for (TextToSpeech.EngineInfo e : tts.getEngines()) {
                if (e.name.equals(pkg)) {
                    String label = e.label == null ? "" : e.label.trim();
                    return label.isEmpty() ? pkg : label;
                }
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }

    /* ---------------- النطق المحلي ---------------- */

    private void localSpeak(String text) {
        if (!ttsReady || text == null) return;
        try {
            tts.stop();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "farm");
        } catch (Exception ignored) {
        }
    }

    /* ---------------- الصوت السحابي ---------------- */

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String cloudKey() {
        String k = prefs().getString(KEY_CLOUD_KEY, "");
        return k == null ? "" : k.trim();
    }

    private boolean cloudEnabled() {
        return prefs().getBoolean(KEY_CLOUD_ON, false);
    }

    private String cloudVoice() {
        return prefs().getString(KEY_CLOUD_VOICE, "Kore");
    }

    private File cacheDirTts() {
        File d = new File(getCacheDir(), "tts");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File cacheFile(String text, String voice) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((voice + "\n" + text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(cacheDirTts(), sb.toString() + ".wav");
        } catch (Exception e) {
            return new File(cacheDirTts(), "tmp.wav");
        }
    }

    /** ينطق عبر Gemini: يجلب الملف من الكاش أو يطلبه من الشبكة، ويرجع للمحلي لو فشل. */
    private void cloudSpeak(final String text) {
        if (!cloudEnabled() || cloudKey().isEmpty() || text.length() > 1500) {
            localSpeak(text);
            return;
        }
        final int seq = ++speakSeq;
        final String voice = cloudVoice();
        final File cached = cacheFile(text, voice);
        if (cached.exists() && cached.length() > 44) {
            playFile(cached, seq);
            return;
        }
        net.execute(() -> {
            try {
                byte[] wav = synth(text, voice);
                File out = cached;
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(wav);
                }
                playFile(out, seq);
                pruneCache();
            } catch (Exception e) {
                lastCloudError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> {
                    if (seq == speakSeq) localSpeak(text);
                });
            }
        });
    }

    private byte[] synth(String text, String voice) throws Exception {
        JSONObject body = new JSONObject();
        JSONObject textPart = new JSONObject().put("text", text);
        JSONObject content = new JSONObject()
                .put("parts", new JSONArray().put(textPart));
        body.put("contents", new JSONArray().put(content));

        JSONObject voiceCfg = new JSONObject()
                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice));
        JSONObject speechCfg = new JSONObject().put("voiceConfig", voiceCfg);
        body.put("generationConfig", new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", speechCfg));

        HttpURLConnection c = (HttpURLConnection) new URL(GEMINI_URL + cloudKey()).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = readAll(in);
        if (code >= 400) {
            throw new Exception("HTTP " + code + ": " + crop(resp));
        }

        JSONObject json = new JSONObject(resp);
        JSONArray cands = json.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new Exception("لا يوجد صوت في الرد");
        JSONArray parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        JSONObject inline = null;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (p.has("inlineData")) { inline = p.getJSONObject("inlineData"); break; }
        }
        if (inline == null) throw new Exception("الرد لا يحتوي صوتًا");
        byte[] pcm = Base64.decode(inline.getString("data"), Base64.DEFAULT);
        return pcmToWav(pcm, 24000, 1, 16);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String crop(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static byte[] pcmToWav(byte[] pcm, int rate, int channels, int bits) {
        int byteRate = rate * channels * bits / 8;
        int dataLen = pcm.length;
        int total = 44 + dataLen;
        byte[] w = new byte[total];
        w[0] = 'R'; w[1] = 'I'; w[2] = 'F'; w[3] = 'F';
        putInt(w, 4, total - 8);
        w[8] = 'W'; w[9] = 'A'; w[10] = 'V'; w[11] = 'E';
        w[12] = 'f'; w[13] = 'm'; w[14] = 't'; w[15] = ' ';
        putInt(w, 16, 16);
        putShort(w, 20, 1);
        putShort(w, 22, channels);
        putInt(w, 24, rate);
        putInt(w, 28, byteRate);
        putShort(w, 32, channels * bits / 8);
        putShort(w, 34, bits);
        w[36] = 'd'; w[37] = 'a'; w[38] = 't'; w[39] = 'a';
        putInt(w, 40, dataLen);
        System.arraycopy(pcm, 0, w, 44, dataLen);
        return w;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    private void playFile(final File f, final int seq) {
        runOnUiThread(() -> {
            if (seq != speakSeq) return;
            stopPlayer();
            try {
                player = new MediaPlayer();
                player.setDataSource(f.getAbsolutePath());
                player.setOnCompletionListener(mp -> stopPlayer());
                player.setOnErrorListener((mp, what, extra) -> { stopPlayer(); return true; });
                player.prepare();
                player.start();
            } catch (Exception e) {
                lastCloudError = "تعذر تشغيل الصوت: " + e.getMessage();
                stopPlayer();
            }
        });
    }

    private void stopPlayer() {
        try {
            if (player != null) {
                if (player.isPlaying()) player.stop();
                player.release();
            }
        } catch (Exception ignored) {
        }
        player = null;
    }

    private void pruneCache() {
        File[] files = cacheDirTts().listFiles();
        if (files == null || files.length <= 400) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 300; i++) files[i].delete();
    }

    private class Bridge {

        @JavascriptInterface
        public boolean hasScreenReader() {
            return screenReader;
        }

        @JavascriptInterface
        public void speak(String text) {
            if (text == null || text.trim().isEmpty()) return;
            if (cloudReady()) {
                cloudSpeak(text);
                return;
            }
            localSpeak(text);
        }

        @JavascriptInterface
        public void stopSpeaking() {
            speakSeq++;
            stopPlayer();
            if (ttsReady) tts.stop();
        }

        /* ---------- محركات النطق ---------- */

        /** كل محركات النطق المتسطبة على الجهاز — جوجل، MultiTTS، غيرها. */
        @JavascriptInterface
        public String listEngines() {
            JSONArray arr = new JSONArray();
            try {
                if (tts == null) return arr.toString();
                List<TextToSpeech.EngineInfo> engines = tts.getEngines();
                for (TextToSpeech.EngineInfo e : engines) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", e.name);
                    String label = e.label == null ? "" : e.label.trim();
                    if (label.isEmpty()) label = e.name;
                    o.put("label", label);
                    o.put("current", e.name.equals(engineUsed));
                    arr.put(o);
                }
            } catch (Exception ignored) {
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean setEngine(String pkg) {
            if (pkg == null || pkg.trim().isEmpty()) return false;
            final String target = pkg.trim();
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_ENGINE, target).apply();
            runOnUiThread(() -> {
                try {
                    if (tts != null) tts.shutdown();
                } catch (Exception ignored) {
                }
                tts = null;
                ttsReady = false;
                triedFallback = false;
                currentVoice = "";
                initTts(target);
            });
            return true;
        }

        /* ---------- الصوت السحابي ---------- */

        /** هل الصوت السحابي مفعّل وله مفتاح؟ */
        @JavascriptInterface
        public boolean cloudReady() {
            return cloudEnabled() && !cloudKey().isEmpty();
        }

        /** حالة الصوت السحابي للتطبيق: مفعّل، صوت، ومفتاح محفوظ. */
        @JavascriptInterface
        public String cloudStatus() {
            JSONObject o = new JSONObject();
            try {
                String k = cloudKey();
                o.put("enabled", cloudEnabled());
                o.put("hasKey", !k.isEmpty());
                o.put("keyHint", k.isEmpty() ? "" : ("…" + k.substring(Math.max(0, k.length() - 4))));
                o.put("voice", cloudVoice());
                o.put("error", lastCloudError);
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public boolean setCloudKey(String key) {
            String k = key == null ? "" : key.trim();
            prefs().edit().putString(KEY_CLOUD_KEY, k)
                    .putBoolean(KEY_CLOUD_ON, !k.isEmpty()).apply();
            lastCloudError = "";
            return !k.isEmpty();
        }

        @JavascriptInterface
        public void clearCloudKey() {
            prefs().edit().remove(KEY_CLOUD_KEY).putBoolean(KEY_CLOUD_ON, false).apply();
            stopSpeaking();
        }

        @JavascriptInterface
        public void setCloudEnabled(boolean on) {
            prefs().edit().putBoolean(KEY_CLOUD_ON, on).apply();
            if (!on) stopSpeaking();
        }

        @JavascriptInterface
        public boolean setCloudVoice(String name) {
            if (name == null || name.trim().isEmpty()) return false;
            prefs().edit().putString(KEY_CLOUD_VOICE, name.trim()).apply();
            return true;
        }

        @JavascriptInterface
        public void testCloud() {
            speak("مرحبًا وعد. هذا هو الصوت السحابي الجديد. المزرعة السعيدة بتتكلم بصوت أنقى.");
        }

        @JavascriptInterface
        public String cloudError() {
            return lastCloudError;
        }

        /** معلومات الصوت الحالي — يعرضها التطبيق في الإعدادات. */
        @JavascriptInterface
        public String voiceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("engine", engineUsed == null ? "" : engineUsed);
                o.put("engineLabel", engineLabel(engineUsed));
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
        stopPlayer();
        net.shutdownNow();
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
