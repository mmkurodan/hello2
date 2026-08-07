package com.micklab.llamachat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.pm.ServiceInfo;
import androidx.core.app.ServiceCompat;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FloatOverlayService extends Service {

    public static final String ACTION_SHOW_OVERLAY = "com.micklab.llamachat.action.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.micklab.llamachat.action.HIDE_OVERLAY";
    public static final String ACTION_OVERLAY_SYNC_UPDATED = "com.micklab.llamachat.action.OVERLAY_SYNC_UPDATED";
    private static final String ACTION_NOTIFICATION_REPLY = "com.micklab.llamachat.action.NOTIFICATION_REPLY";
    private static final String KEY_NOTIFICATION_REPLY = "notification_reply_text";

    private static final String SETTINGS_FILE = "chat_settings.json";
    private static final String OVERLAY_SYNC_LOG_FILE = "overlay_sync_log.jsonl";
    private static final String FLOAT_DISPLAY_MODE_AVATAR = "avatar";
    private static final String FLOAT_DISPLAY_MODE_ICON = "icon";
    private static final String AVATAR_C0_FILE = "avatar_c0.jpg";
    private static final String AVATAR_C1_FILE = "avatar_c1.jpg";
    private static final String AVATAR_C2_FILE = "avatar_c2.jpg";
    private static final String AVATAR_C3_FILE = "avatar_c3.jpg";
    private static final String DEFAULT_BASE_NAME_JA = "藍";
    private static final String DEFAULT_BASE_NAME_EN = "Ai";
    private static final String DEFAULT_SYSTEM_PROMPT_JA = "あなたはユーザの若い女性秘書です";
    private static final String DEFAULT_SYSTEM_PROMPT_EN = "You are the user's young female secretary.";
    private static final String SEARCH_SYSTEM_PROMPT =
            "You are a search-augmented assistant. When the user provides SEARCH_RESULTS, "
                    + "you must read them and base your answer strictly on that information.";
    private static final String NOTIFICATION_CHANNEL_ID = "llamachat_float_overlay";
    private static final int NOTIFICATION_ID = 2001;
    private static final int AVATAR_TALK_FRAME_MS = 120;
    private static final int AVATAR_BLINK_MIN_MS = 3000;
    private static final int AVATAR_BLINK_MAX_MS = 7000;
    private static final int AVATAR_BLINK_DURATION_MS = 120;
    private static final int THINKING_ANIMATION_INTERVAL_MS = 360;
    private static final int OVERLAY_SCROLL_SETTLE_MS = 72;
    private static final int OVERLAY_SCROLL_MIN_INTERVAL_MS = 96;
    private static final float FLOAT_AVATAR_HEIGHT_RATIO = 0.33f;
    private static final int FLOAT_BUBBLE_MARGIN_DP = 12;
    private static final int MIN_RETAINED_HISTORY_MESSAGES = 24;
    private static final int MAX_SYNC_LOG_LINES = 80;
    private static final int MAX_NOTIFICATION_TEXT_LENGTH = 240;
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int DEFAULT_NUM_CTX = 8192;
    private static final int DEFAULT_NUM_PREDICT = -1;
    private int numCtx = DEFAULT_NUM_CTX;
    private int numPredict = DEFAULT_NUM_PREDICT;
    private static final String CHAT_KEEP_ALIVE = "30m";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object historyLock = new Object();
    private final List<JSONObject> conversationHistory = new ArrayList<>();
    private final ExpertSelector expertSelector = new ExpertSelector();
    private final WebSearchExpertHandler webSearchExpertHandler = new WebSearchExpertHandler();
    private final ChatFlowController chatFlowController =
            new ChatFlowController(expertSelector, webSearchExpertHandler);
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(3600, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(3600, TimeUnit.SECONDS)
            .build();

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams layoutParams;
    private ImageView floatVisualBackground;
    private ImageView floatVisual;
    private LinearLayout bubblePanel;
    private ScrollView messageScrollView;
    private LinearLayout messageContainer;
    private EditText inputView;
    private Button sendButton;
    private Button hideButton;
    private TextView bubbleTitleView;
    private GestureDetector gestureDetector;
    private MarkdownRenderer markdownRenderer;
    private TextView currentResponseBubble;
    private Bitmap avatarC0Bitmap;
    private Bitmap avatarC1Bitmap;
    private Bitmap avatarC2Bitmap;
    private Bitmap avatarC3Bitmap;
    private Call currentCall;
    private final Random avatarRandom = new Random();
    private int talkFrameIndex = 0;
    private final int[] talkFrames = new int[]{R.drawable.c1, R.drawable.c3};

    private String ollamaBaseUrl = "http://127.0.0.1:11434";
    private String selectedModel = "default";
    private String systemPromptText = DEFAULT_SYSTEM_PROMPT_JA;
    private String baseName = DEFAULT_BASE_NAME_JA;
    private String userName = "";
    private String appLanguage = Locale.getDefault().getLanguage().startsWith("ja") ? "ja" : "en";
    private String floatDisplayMode = FLOAT_DISPLAY_MODE_AVATAR;
    private String webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
    private String webSearchApiKey = "";
    private boolean braveBoostEnabled = false;
    private String webSearchMode = "WIKIPEDIA";
    private String embeddingModel = "default";
    private String expertModel = "default";
    private String speechLang = "ja-JP";
    private int historyLimit = 10;
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private boolean streamingEnabled = true;
    private boolean ttsEnabled = false;
    private boolean voiceInputEnabled = true;
    private boolean autoVoiceInputEnabled = false;
    private boolean webSearchEnabled = false;
    // お知らせ機能（予定/ニュースのポップアップ通知）。
    private boolean proactiveNotifyEnabled = false;
    private String morningBriefingTime = "07:00";
    private boolean newsModeEnabled = false;
    private String newsBriefingTimes = "08:00,12:00,18:00";
    private String structuredOutputMode = "OFF";
    // お知らせスケジューラ（サービス内で毎分時刻を評価）。
    private static final long PROACTIVE_TICK_MS = 60_000L;
    // 毎時の直近予定リマインダの先読み幅（分）。次の毎時tickまでを見込む。
    private static final int UPCOMING_WINDOW_MINUTES = 60;
    private static final String PROACTIVE_PREFS = "proactive_state";
    private boolean proactiveStarted = false;
    private final AtomicBoolean proactiveBusy = new AtomicBoolean(false);
    private final Runnable proactiveTickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                onProactiveTick();
            } catch (Exception e) {
                DebugLogger.log(FloatOverlayService.this, "proactiveTick error: " + e.getMessage());
            }
            mainHandler.postDelayed(this, PROACTIVE_TICK_MS);
        }
    };
    private boolean isProcessing = false;
    private boolean isListening = false;
    // 音声認識は MainActivity と共有する VoiceInputController に委譲（完全同一仕様）。
    private VoiceInputController voiceController;
    // 会話ログ共有ストア（MainActivity と同一プロセスで共有）。
    private ConversationStore conversationStore;
    private int storeRenderedCount = 0;
    private final ConversationStore.Listener storeListener = new ConversationStore.Listener() {
        @Override
        public void onEntryAppended(int newSize) {
            mainHandler.post(() -> renderStoreDelta());
        }

        @Override
        public void onCleared() {
            mainHandler.post(() -> {
                // 読み上げを即中断し、進行中の応答が消去直後のストアへ再追記しないよう停止する。
                if (tts != null) {
                    tts.stop();
                }
                ttsSpeaking.set(false);
                activeResponseToken++;
                if (currentCall != null) {
                    currentCall.cancel();
                    currentCall = null;
                }
                isProcessing = false;
                isStreamingResponse = false;
                stopThinkingIndicator(activeResponseToken);
                updateAvatarAnimation();
                clearOverlayMessages();
                initConversationHistory();
                storeRenderedCount = 0;
                updateSendButton();
            });
        }
    };
    private boolean isStreamingResponse = false;
    private int messageMaxHeightPx = 0;
    private int avatarPosX = 0;
    private int avatarPosY = 0;
    private int bubblePosX = 0;
    private int bubblePosY = 0;
    private int avatarWidthPx = 0;
    private int avatarHeightPx = 0;
    private int activeResponseToken = 0;
    private boolean foregroundStarted = false;
    private boolean overlayInitialized = false;
    private boolean openingAppFromOverlay = false;
    // クラッシュ回復: ユーザが意図的に停止した場合はtrueにしてonDestroyでの再起動を抑制する。
    private boolean intentionalStop = false;
    private static final long RESTART_DELAY_MS = 6_000L;
    private MemoryRepository memoryRepository;
    private EmbeddingClient embeddingClient;
    private WebSearchRagHelper webSearchRagHelper;
    private boolean memoryEnabled = false;
    private String latestNotificationResponse = "";
    private int thinkingDotStep = 0;
    private String thinkingLabel = "";
    private int thinkingToken = 0;
    private boolean scrollToBottomScheduled = false;
    private boolean pendingScrollToBottom = false;
    private long lastScrollToBottomAtMs = 0L;
    private final Runnable thinkingAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentResponseBubble == null || thinkingToken != activeResponseToken) return;
            thinkingDotStep = (thinkingDotStep + 1) % 3;
            applyThinkingText();
            mainHandler.postDelayed(this, THINKING_ANIMATION_INTERVAL_MS);
        }
    };

    private float touchStartX;
    private float touchStartY;
    private int initialX;
    private int initialY;
    private boolean dragging = false;
    private boolean draggingBubble = false;
    private int touchSlop = 0;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final AtomicBoolean ttsSpeaking = new AtomicBoolean(false);
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            setAvatarFrame(R.drawable.c2);
            mainHandler.postDelayed(blinkResetRunnable, AVATAR_BLINK_DURATION_MS);
        }
    };
    private final Runnable blinkResetRunnable = new Runnable() {
        @Override
        public void run() {
            setAvatarFrame(R.drawable.c1);
            scheduleNextBlink();
        }
    };
    private final Runnable talkRunnable = new Runnable() {
        @Override
        public void run() {
            setAvatarFrame(talkFrames[talkFrameIndex]);
            talkFrameIndex = (talkFrameIndex + 1) % talkFrames.length;
            mainHandler.postDelayed(this, AVATAR_TALK_FRAME_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLogger.log(this, "=== FloatOverlayService onCreate START ===");
        try {
            DebugLogger.log(this, "Getting ViewConfiguration");
            touchSlop = ViewConfigurationHolder.get(this);
            DebugLogger.log(this, "touchSlop=" + touchSlop);
            
            DebugLogger.log(this, "Loading settings");
            loadSettings();
            DebugLogger.log(this, "Settings loaded. floatDisplayMode=" + floatDisplayMode);
            conversationStore = ConversationStore.get(this);
            
            DebugLogger.log(this, "Loading avatar bitmaps");
            loadAvatarBitmaps();
            DebugLogger.log(this, "Avatar bitmaps loaded");

            initTts();
            
            DebugLogger.log(this, "Initializing conversation history");
            initConversationHistory();
            DebugLogger.log(this, "Conversation history initialized");
            
            DebugLogger.log(this, "Preparing notification service");
            ensureForegroundNotification();

            memoryRepository = new MemoryRepository(this);
            // embeddingClient / webSearchRagHelper は loadSettings() 内で生成済み

            startProactiveScheduler();

            DebugLogger.log(this, "=== FloatOverlayService onCreate SUCCESS ===");
        } catch (Throwable e) {
            // Throwable で OOM 等の Error も捕捉する。
            // 初期化失敗は再起動ループを防ぐため意図的停止とみなす。
            intentionalStop = true;
            DebugLogger.log(this, "=== FloatOverlayService onCreate FAILED ===");
            DebugLogger.log(this, "Exception: " + e.getMessage());
            DebugLogger.log(this, "StackTrace: " + android.util.Log.getStackTraceString(e));
            android.util.Log.e("FloatOverlay", "Failed to initialize overlay service", e);
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLogger.log(this, "onStartCommand called");
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SHOW_OVERLAY.equals(action)) {
            ensureOverlayInitialized();
        } else if (ACTION_HIDE_OVERLAY.equals(action)) {
            teardownOverlay();
        } else if (ACTION_NOTIFICATION_REPLY.equals(action)) {
            handleNotificationReply(intent);
        }
        ensureForegroundNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        DebugLogger.log(this, "onDestroy: intentionalStop=" + intentionalStop);
        stopProactiveScheduler();
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        if (voiceController != null) {
            voiceController.destroy();
            voiceController = null;
        }
        if (conversationStore != null) {
            conversationStore.removeListener(storeListener);
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        teardownOverlay();
        stopAvatarAnimation();
        ttsReady = false;
        ttsSpeaking.set(false);
        recycleBitmap(avatarC0Bitmap);
        recycleBitmap(avatarC1Bitmap);
        recycleBitmap(avatarC2Bitmap);
        recycleBitmap(avatarC3Bitmap);
        avatarC0Bitmap = null;
        avatarC1Bitmap = null;
        avatarC2Bitmap = null;
        avatarC3Bitmap = null;
        if (!intentionalStop) {
            scheduleServiceRestart();
        }
        super.onDestroy();
    }

    private void scheduleServiceRestart() {
        try {
            Intent restartIntent = new Intent(this, ServiceRestartReceiver.class);
            restartIntent.setAction(ServiceRestartReceiver.ACTION_RESTART);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, 9901, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pendingIntent);
                DebugLogger.log(this, "scheduleServiceRestart: restart scheduled in " + RESTART_DELAY_MS + "ms");
            }
        } catch (Exception e) {
            DebugLogger.log(this, "scheduleServiceRestart failed: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initOverlay() {
        DebugLogger.log(this, "initOverlay: Starting");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            DebugLogger.log(this, "ERROR: initOverlay: WindowManager is null!");
            throw new RuntimeException("WindowManager is not available");
        }
        DebugLogger.log(this, "initOverlay: WindowManager obtained");

        try {
            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_float, null);
            if (overlayView == null) {
                DebugLogger.log(this, "ERROR: initOverlay: overlayView inflation failed");
                throw new RuntimeException("Failed to inflate overlay layout");
            }
            DebugLogger.log(this, "initOverlay: Overlay layout inflated");
            
            floatVisualBackground = overlayView.findViewById(R.id.floatVisualBackground);
            floatVisual = overlayView.findViewById(R.id.floatVisual);
            bubblePanel = overlayView.findViewById(R.id.bubblePanel);
            messageScrollView = overlayView.findViewById(R.id.svFloatMessages);
            messageContainer = overlayView.findViewById(R.id.floatMessageContainer);
            inputView = overlayView.findViewById(R.id.etFloatInput);
            sendButton = overlayView.findViewById(R.id.btnFloatSend);
            hideButton = overlayView.findViewById(R.id.btnHideBubble);
            bubbleTitleView = overlayView.findViewById(R.id.tvBubbleTitle);
            
            if (floatVisual == null || bubblePanel == null || messageScrollView == null || messageContainer == null || inputView == null) {
                DebugLogger.log(this, "ERROR: initOverlay: Some views are null after findViewById");
                throw new RuntimeException("Required views not found in layout");
            }
            DebugLogger.log(this, "initOverlay: All views found");
    
            layoutParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            layoutParams.gravity = Gravity.TOP | Gravity.START;
            layoutParams.x = 0;
            layoutParams.y = dpToPx(160);
            DebugLogger.log(this, "initOverlay: LayoutParams configured");
            avatarPosY = layoutParams.y;
    
            gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    toggleBubble();
                    return true;
                }
    
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    openApp();
                    return true;
                }
            });
    
            floatVisual.setOnTouchListener((v, event) -> {
                GestureDetector detector = gestureDetector;
                if (detector == null) {
                    return true;
                }
                detector.onTouchEvent(event);
                if (openingAppFromOverlay || layoutParams == null || floatVisual == null) {
                    return true;
                }
                boolean iconMode = FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode);
                boolean bubbleVisible = bubblePanel != null && bubblePanel.getVisibility() == View.VISIBLE;
                boolean directWindowDrag = iconMode || !bubbleVisible;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        if (directWindowDrag) {
                            initialX = layoutParams.x;
                            initialY = layoutParams.y;
                        } else {
                            initialX = avatarPosX;
                            initialY = avatarPosY;
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchStartX;
                        float dy = event.getRawY() - touchStartY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            if (directWindowDrag) {
                                int nextX = initialX + (int) dx;
                                int nextY = initialY + (int) dy;
                                if (!iconMode) {
                                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                                    int maxX = Math.max(0, screenWidth - avatarWidthPx);
                                    int maxY = Math.max(0, screenHeight - avatarHeightPx);
                                    nextX = Math.max(0, Math.min(nextX, maxX));
                                    nextY = Math.max(0, Math.min(nextY, maxY));
                                }
                                layoutParams.x = nextX;
                                layoutParams.y = nextY;
                                avatarPosX = layoutParams.x;
                                avatarPosY = layoutParams.y;
                                updateOverlayLayout();
                            } else {
                                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                                int maxX = Math.max(0, screenWidth - avatarWidthPx);
                                int maxY = Math.max(0, screenHeight - avatarHeightPx);
                                avatarPosX = Math.max(0, Math.min(initialX + (int) dx, maxX));
                                avatarPosY = Math.max(0, Math.min(initialY + (int) dy, maxY));
                                applyAvatarPosition();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            });
    
            sendButton.setOnClickListener(v -> {
                if (isListening) {
                    cancelVoiceRecognition();
                    return;
                }
                if (isProcessing) {
                    cancelCurrentRequest();
                    return;
                }
                String text = inputView.getText().toString().trim();
                if (text.isEmpty()) {
                    if (voiceInputEnabled) {
                        startVoiceRecognition(true);
                        return;
                    }
                    Toast.makeText(this, t("Please enter a message", "メッセージを入力してください"), Toast.LENGTH_SHORT).show();
                    return;
                }
                submitUserMessage(text);
            });
            hideButton.setOnClickListener(v -> showBubble(false));

            attachBubbleDragListener(bubblePanel, true);
            if (bubblePanel != null && bubblePanel.getChildCount() > 0) {
                attachBubbleDragListener(bubblePanel.getChildAt(0), false);
            }

            updateFloatVisual();
            DebugLogger.log(this, "initOverlay: Float visual updated");
            updateBubbleHeader();
            updateSendButton();
            
            DebugLogger.log(this, "initOverlay: Adding real overlay view to window manager");
            windowManager.addView(overlayView, layoutParams);
            DebugLogger.log(this, "initOverlay: Real overlay view added successfully");
        } catch (Exception e) {
            android.util.Log.e("FloatOverlay", "Failed during overlay initialization", e);
            e.printStackTrace();
            throw new RuntimeException("Cannot initialize overlay", e);
        }
    }

    private void openApp() {
        if (openingAppFromOverlay) {
            return;
        }
        openingAppFromOverlay = true;
        mainHandler.post(() -> {
            try {
                startActivity(createMainActivityIntent());
                teardownOverlay();
                ensureForegroundNotification();
            } finally {
                openingAppFromOverlay = false;
            }
        });
    }

    private Intent createMainActivityIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("disableFloatOverlay", true);
        return intent;
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "LlamaChat Floating Overlay",
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = createMainActivityIntent();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        String statusText = !TextUtils.isEmpty(latestNotificationResponse)
                ? latestNotificationResponse
                : (isProcessing
                ? t("Generating response...", "応答を生成中...")
                : t("Tap Send Message to use quick chat.", "メッセージ送信をタップすると簡易チャットができます。"));
        builder
                .setContentTitle("Dual AI Chat")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        if (!TextUtils.isEmpty(statusText)) {
            builder.setStyle(new Notification.BigTextStyle().bigText(statusText));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent replyIntent = new Intent(this, FloatOverlayService.class);
            replyIntent.setAction(ACTION_NOTIFICATION_REPLY);
            PendingIntent replyPendingIntent = PendingIntent.getService(
                    this,
                    1001,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_NOTIFICATION_REPLY)
                    .setLabel(t("Type a message", "メッセージを入力"))
                    .build();
            Notification.Action replyAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    t("Send Message", "メッセージ送信"),
                    replyPendingIntent
            ).addRemoteInput(remoteInput).build();
            builder.addAction(replyAction);
        }
        return builder.build();
    }

    private Notification buildFallbackNotification() {
        Intent openIntent = createMainActivityIntent();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Dual AI Chat")
                .setContentText(t("Tap Send Message to use quick chat.", "メッセージ送信をタップすると簡易チャットができます。"))
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void ensureForegroundNotification() {
        logNotificationState("ensureForegroundNotification:start");
        try {
            Notification notification = buildNotification();
            if (!foregroundStarted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    DebugLogger.log(this, "notification: ServiceCompat.startForeground with DATA_SYNC type success");
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startForeground(NOTIFICATION_ID, notification);
                    DebugLogger.log(this, "notification: startForeground (API 31+) success");
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                    DebugLogger.log(this, "notification: startForeground (legacy) success");
                }
                foregroundStarted = true;
            } else {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, notification);
                    DebugLogger.log(this, "notification: manager.notify success");
                } else {
                    DebugLogger.log(this, "notification: manager is null on update");
                }
            }
        } catch (Exception e) {
            DebugLogger.log(this, "start/update foreground failed: " + e.getMessage());
            try {
                Notification fallback = buildFallbackNotification();
                if (!foregroundStarted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            ServiceCompat.startForeground(this, NOTIFICATION_ID, fallback,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                            DebugLogger.log(this, "notification: fallback ServiceCompat.startForeground with DATA_SYNC type success");
                        } catch (Exception typeError) {
                            DebugLogger.log(this, "fallback with DATA_SYNC type failed: " + typeError.getMessage() + ", retrying without type");
                            startForeground(NOTIFICATION_ID, fallback);
                            DebugLogger.log(this, "notification: fallback startForeground (no type) success");
                        }
                    } else {
                        startForeground(NOTIFICATION_ID, fallback);
                        DebugLogger.log(this, "notification: fallback startForeground (legacy) success");
                    }
                    foregroundStarted = true;
                } else {
                    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (manager != null) {
                        manager.notify(NOTIFICATION_ID, fallback);
                        DebugLogger.log(this, "notification: fallback manager.notify success");
                    } else {
                        DebugLogger.log(this, "notification: manager is null on fallback update");
                    }
                }
            } catch (Exception retryError) {
                DebugLogger.log(this, "fallback foreground failed: " + retryError.getMessage());
            }
        }
        logNotificationState("ensureForegroundNotification:end");
    }

    /**
     * 音声認識中だけ前景サービス種別に microphone を追加し、終了時は dataSync のみへ戻す。
     *
     * Android 11+ では前景サービスからマイクを使うのに microphone 種別が必須で、無いと
     * フロート（他アプリ上＝アプリ非前景）でのマイク入力が無音になり ~8秒後に NO_MATCH となる
     * （MainActivity は Activity が前景なので種別に依らずマイクが通り、問題が出ない）。
     * 常時 microphone を付けると RECORD_AUDIO 未許可やバックグラウンド起動時に Android 14 で
     * 例外になり得るため、ユーザー操作で認識を開始する瞬間にだけ昇格させる。
     */
    private void setMicrophoneForegroundType(boolean micActive) {
        if (!foregroundStarted) return;                       // 基盤の前景化前は ensureForegroundNotification に委ねる
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;  // API28-: FGS 種別の概念が無い
        try {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (micActive) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type);
            DebugLogger.log(this, "FGS type updated micActive=" + micActive);
        } catch (Exception e) {
            // RECORD_AUDIO 未許可や状態的に昇格不可な場合など。認識自体は続行する（無音の可能性あり）。
            DebugLogger.log(this, "setMicrophoneForegroundType failed micActive=" + micActive + ": " + e.getMessage());
        }
    }

    private void logNotificationState(String stage) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            boolean enabled = manager != null
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || manager.areNotificationsEnabled());
            boolean postNotifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            DebugLogger.log(this, "notification state [" + stage + "] enabled=" + enabled
                    + ", postPermission=" + postNotifGranted
                    + ", foregroundStarted=" + foregroundStarted
                    + ", latestText=" + latestNotificationResponse);
        } catch (Exception e) {
            DebugLogger.log(this, "notification state log failed: " + e.getMessage());
        }
    }

    private void handleNotificationReply(Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence reply = results.getCharSequence(KEY_NOTIFICATION_REPLY);
        if (reply == null) return;
        String text = reply.toString().trim();
        if (text.isEmpty()) return;
        submitUserMessage(text);
        if (overlayInitialized && bubblePanel != null) {
            showBubble(true);
        }
    }

    private void toggleBubble() {
        if (bubblePanel == null) return;
        showBubble(bubblePanel.getVisibility() != View.VISIBLE);
    }

    private void showBubble(boolean show) {
        if (bubblePanel == null || layoutParams == null) return;
        bubblePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        layoutParams.flags = show
                ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        updateFloatVisual();
        if (show) {
            updateBubblePanelPosition();
            adjustMessageAreaHeight();
            scrollMessagesToBottom();
            inputView.requestFocus();
            mainHandler.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 80L);
        } else {
            hideKeyboard();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && inputView != null) {
            imm.hideSoftInputFromWindow(inputView.getWindowToken(), 0);
        }
        if (inputView != null) {
            inputView.clearFocus();
        }
    }

    private void updateOverlayLayout() {
        if (windowManager == null || overlayView == null) return;
        try {
            windowManager.updateViewLayout(overlayView, layoutParams);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void updateFloatVisual() {
        if (floatVisual == null || layoutParams == null) return;
        boolean iconMode = FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode);
        boolean bubbleVisible = bubblePanel != null && bubblePanel.getVisibility() == View.VISIBLE;
        boolean compactWindow = iconMode || !bubbleVisible;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        avatarHeightPx = Math.max(1, Math.round(screenH * FLOAT_AVATAR_HEIGHT_RATIO));
        avatarWidthPx = Math.max(
                1,
                Math.min(screenW, Math.round(avatarHeightPx * getAvatarAspectRatio()))
        );
        layoutParams.width = compactWindow ? WindowManager.LayoutParams.WRAP_CONTENT : screenW;
        layoutParams.height = compactWindow ? WindowManager.LayoutParams.WRAP_CONTENT : screenH;
        if (compactWindow) {
            layoutParams.x = avatarPosX;
            layoutParams.y = avatarPosY;
        } else {
            layoutParams.x = 0;
            layoutParams.y = 0;
        }
        FrameLayout.LayoutParams params = floatVisual.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) floatVisual.getLayoutParams()
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (iconMode) {
            int sizePx = dpToPx(56);
            params.width = sizePx;
            params.height = sizePx;
            params.leftMargin = 0;
            params.topMargin = 0;
        } else {
            int maxX = Math.max(0, screenW - avatarWidthPx);
            int maxY = Math.max(0, screenH - avatarHeightPx);
            avatarPosX = Math.max(0, Math.min(avatarPosX, maxX));
            avatarPosY = Math.max(0, Math.min(avatarPosY, maxY));
            params.width = avatarWidthPx;
            params.height = avatarHeightPx;
            if (compactWindow) {
                params.leftMargin = 0;
                params.topMargin = 0;
            } else {
                params.leftMargin = avatarPosX;
                params.topMargin = avatarPosY;
            }
        }
        floatVisual.setLayoutParams(params);
        if (bubblePanel != null) {
            FrameLayout.LayoutParams bubbleParams = bubblePanel.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) bubblePanel.getLayoutParams()
                    : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubbleParams.width = Math.min(dpToPx(300), Math.max(dpToPx(200), screenW - dpToPx(FLOAT_BUBBLE_MARGIN_DP * 2)));
            bubbleParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bubblePanel.setLayoutParams(bubbleParams);
            updateBubblePanelPosition();
        }
        if (floatVisualBackground != null) {
            floatVisualBackground.setVisibility(View.GONE);
        }
        floatVisual.setBackgroundColor(0x00000000);
        floatVisual.setContentDescription(t(
                iconMode ? "Floating icon" : "Floating avatar",
                iconMode ? "フロートアイコン" : "フロートアバター"
        ));
        if (iconMode) {
            stopAvatarAnimation();
            floatVisual.setImageResource(R.drawable.icon);
        } else {
            setAvatarFrame(R.drawable.c1);
            updateAvatarAnimation();
        }
        updateOverlayLayout();
        mainHandler.post(this::adjustMessageAreaHeight);
    }

    private void updateAvatarAnimation() {
        if (FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode)) return;
        if (isProcessing || ttsSpeaking.get()) {
            startTalkAnimation();
        } else {
            startIdleAnimation();
        }
    }

    private void updateBubbleHeader() {
        if (bubbleTitleView != null) {
            bubbleTitleView.setText(TextUtils.isEmpty(baseName)
                    ? t("Quick Chat", "フロートチャット")
                    : baseName);
        }
    }

    private void updateSendButton() {
        mainHandler.post(() -> {
            if (sendButton != null) {
                sendButton.setText((isProcessing || isListening) ? "STOP" : t("Send", "送信"));
            }
        });
    }

    private void submitUserMessage(String userMessage) {
        int requestToken = ++activeResponseToken;
        if (inputView != null) {
            inputView.setText("");
        }
        // 共有ログを残すため、従来の clearOverlayMessages() は行わない。
        appendUserMessageBubble(userMessage);
        addToHistory("user", userMessage);
        // 共有ストアへ発行（MainActivity と同期）。自分は描画済みのため renderedCount を進める。
        conversationStore.append("user", userMessage);
        storeRenderedCount = conversationStore.size();
        isProcessing = true;
        startThinkingIndicator(t("Thinking", "思考中"), requestToken);
        updateNotificationResponse(t("Generating response...", "応答を生成中..."));
        updateAvatarAnimation();
        currentResponseBubble = null;
        updateSendButton();
        ChatFlowController.ChatFlowResult flowResult = chatFlowController.route(
                userMessage,
                isWebSearchExpertAvailable(),
                memoryEnabled
        );
        if (flowResult.getExpertType() == ExpertType.WEB) {
            performWebSearchFlow(userMessage, flowResult.getWebSearchQuery(), requestToken);
        } else if (flowResult.getExpertType() == ExpertType.MEMORY_SAVE) {
            performMemorySaveFlow(userMessage, requestToken);
        } else if (flowResult.getExpertType() == ExpertType.MEMORY_RECALL) {
            performMemoryRecallFlow(userMessage, requestToken);
        } else {
            sendChat(null, false, requestToken);
        }
    }

    private boolean isWebSearchExpertAvailable() {
        if (!webSearchEnabled) return false;
        if ("WIKIPEDIA".equals(webSearchMode)) return true;
        return !webSearchApiKey.isEmpty();
    }

    private void applyOllamaOptions(JSONObject body) throws org.json.JSONException {
        JSONObject options = new JSONObject();
        options.put("num_ctx", numCtx);
        options.put("num_predict", numPredict);
        body.put("options", options);
        body.put("keep_alive", CHAT_KEEP_ALIVE);
    }

    private void cancelCurrentRequest() {
        activeResponseToken++;
        stopThinkingIndicator(activeResponseToken);
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        isProcessing = false;
        cancelVoiceRecognition();
        updateAvatarAnimation();
        updateSendButton();
        currentResponseBubble = null;
        setResponseText(t("Request cancelled", "リクエストをキャンセルしました"), activeResponseToken);
        updateNotificationResponse(t("Request cancelled", "リクエストをキャンセルしました"));
    }

    private void sendChat(String transientUserMessage, boolean replaceLastUserMessage, int requestToken) {
        try {
            JSONArray messages = new JSONArray();
            boolean shouldReplaceLastUser = replaceLastUserMessage && transientUserMessage != null;
            synchronized (historyLock) {
                if (!conversationHistory.isEmpty()) {
                    JSONObject first = conversationHistory.get(0);
                    if ("system".equals(first.optString("role"))) {
                        String systemContent = first.optString("content", "");
                        String searchSystemPrompt = ExpertPromptStore.getSearchSystemPrompt(this);
                        if (webSearchEnabled && !searchSystemPrompt.isEmpty() && !systemContent.contains(searchSystemPrompt)) {
                            systemContent = systemContent + "\n" + searchSystemPrompt;
                        }
                        JSONObject sys = new JSONObject();
                        sys.put("role", "system");
                        sys.put("content", systemContent);
                        messages.put(sys);
                    }
                }
                int size = conversationHistory.size();
                int start = Math.max(1, size - Math.max(0, historyLimit));
                for (int i = start; i < size; i++) {
                    JSONObject msg = conversationHistory.get(i);
                    if (shouldReplaceLastUser && i == size - 1 && "user".equals(msg.optString("role"))) {
                        JSONObject replaced = new JSONObject(msg.toString());
                        replaced.put("content", transientUserMessage);
                        messages.put(replaced);
                    } else {
                        messages.put(msg);
                    }
                }
            }
            if (transientUserMessage != null && !shouldReplaceLastUser) {
                JSONObject extra = new JSONObject();
                extra.put("role", "user");
                extra.put("content", transientUserMessage);
                messages.put(extra);
            }

            JSONObject body = new JSONObject();
            body.put("model", selectedModel);
            body.put("messages", messages);
            body.put("stream", streamingEnabled);

            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/chat")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();

            if (streamingEnabled) {
                sendStreaming(request, requestToken);
            } else {
                sendNonStreaming(request, requestToken);
            }
        } catch (Exception e) {
            isProcessing = false;
            updateSendButton();
            setResponseText(errorText(e.getMessage()), requestToken);
        }
    }

    private void performWebSearchFlow(String userMsg, String searchKeywords, int requestToken) {
        updateThinkingLabel(t("Web searching", "Web検索中"), requestToken);
        new Thread(() -> {
            String augmentedMessage = null;
            try {
                String keywords = searchKeywords == null ? null : searchKeywords.trim();
                if (!TextUtils.isEmpty(keywords)) {
                    updateThinkingLabel(t("Extracting keywords...", "キーワードを抽出中..."), requestToken);
                    String extracted = WikipediaSearchHelper.extractKeywords(
                            client, ollamaBaseUrl, expertModel, userMsg);
                    String keywordText = extracted.length() > 80 ? extracted.substring(0, 80) + "..." : extracted;
                    updateThinkingLabel(t("Web searching: ", "Web検索中: ") + keywordText, requestToken);
                    String searchResults = callWebSearchApi(extracted);
                    if (!TextUtils.isEmpty(searchResults)) {
                        // RAG: 埋め込みでクエリ関連度の高いチャンクのみ抽出
                        if (webSearchRagHelper != null) {
                            updateThinkingLabel(t("Ranking results...", "結果を絞り込み中..."), requestToken);
                            searchResults = webSearchRagHelper.rerankSearchResults(userMsg, searchResults);
                        }
                        augmentedMessage = buildSearchAugmentedUserMessage(userMsg, searchResults);
                    }
                }
            } catch (Exception e) {
                DebugLogger.log(this, "performWebSearchFlow error: " + e.getMessage());
            }
            String finalAugmented = augmentedMessage;
            mainHandler.post(() -> sendChat(finalAugmented, finalAugmented != null, requestToken));
        }).start();
    }

    private MemoryFlowHelper newMemoryHelper() {
        return new MemoryFlowHelper(memoryRepository, embeddingClient, client, ollamaBaseUrl,
                selectedModel, StructuredOutput.Mode.fromString(structuredOutputMode), appLanguage);
    }

    private void performMemorySaveFlow(String userMsg, int requestToken) {
        if (memoryRepository == null) {
            finishResponse(t("Memory is unavailable.", "記憶機能が利用できません。"), requestToken);
            return;
        }
        updateThinkingLabel(t("Saving to memory...", "記録を保存中..."), requestToken);
        new Thread(() -> {
            MemoryFlowHelper helper = newMemoryHelper();
            MemoryFlowHelper.SaveResult saved = helper.extractAndSave(userMsg);
            final String message;
            if (saved == null) {
                message = t("What would you like me to remember?", "何を覚えておけば良いですか？");
            } else {
                DebugLogger.log(this, "memory save: id=" + saved.record.id
                        + " cat=" + saved.record.category
                        + " updated=" + saved.wasUpdated
                        + " content=\"" + saved.record.content + "\"");
                message = helper.confirmationText(saved);
            }
            mainHandler.post(() -> finishResponse(message, requestToken));
        }).start();
    }

    private void performMemoryRecallFlow(String userMsg, int requestToken) {
        if (memoryRepository == null) {
            sendChat(null, false, requestToken);
            return;
        }
        updateThinkingLabel(t("Searching memory...", "記録を検索中..."), requestToken);
        new Thread(() -> {
            try {
                String memoryContext = newMemoryHelper().buildRecallContext(userMsg);
                String augmentedMessage = TextUtils.isEmpty(memoryContext) ? null
                        : "MEMORIES:\n" + memoryContext + "\n\nUser: " + userMsg;
                mainHandler.post(() -> sendChat(augmentedMessage, !TextUtils.isEmpty(memoryContext), requestToken));
            } catch (Exception e) {
                DebugLogger.log(this, "performMemoryRecallFlow error: " + e.getMessage());
                mainHandler.post(() -> sendChat(null, false, requestToken));
            }
        }).start();
    }

    private String callWebSearchApi(String keywords) {
        if ("WIKIPEDIA".equals(webSearchMode)) {
            return WikipediaSearchHelper.search(client, keywords);
        }
        if (isBraveWebSearchUrl(webSearchUrl)) {
            return callBraveWebSearchApi(keywords);
        }
        try {
            String url = webSearchUrl + "?q=" + java.net.URLEncoder.encode(keywords, "UTF-8");
            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "ja-JP");
            if (!webSearchApiKey.isEmpty()) {
                reqBuilder.addHeader("X-Subscription-Token", webSearchApiKey);
                reqBuilder.addHeader("Authorization", "Bearer " + webSearchApiKey);
                reqBuilder.addHeader("X-Api-Key", webSearchApiKey);
            }
            Response response = client.newCall(reqBuilder.build()).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) return null;
            JSONObject json = new JSONObject(respBody);
            StringBuilder extracted = new StringBuilder();
            extractAllStringValues(json, extracted, 0);
            String extractedText = extracted.toString().trim();
            if (extractedText.isEmpty()) return null;
            return "SEARCH_RESULTS:\n" + extractedText;
        } catch (Exception e) {
            DebugLogger.log(this, "callWebSearchApi error: " + e.getMessage());
            return null;
        }
    }

    private boolean isBraveWebSearchUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("search.brave.com/res/v1/web/search")) return true;
        Uri uri = Uri.parse(url.trim());
        String host = uri.getHost();
        return host != null && host.toLowerCase(Locale.ROOT).contains("search.brave.com");
    }

    private String callBraveWebSearchApi(String keywords) {
        try {
            String url = webSearchUrl + "?q=" + java.net.URLEncoder.encode(keywords, "UTF-8") + "&count=8";
            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "ja-JP");
            if (!webSearchApiKey.isEmpty()) {
                reqBuilder.addHeader("X-Subscription-Token", webSearchApiKey);
            }
            Response response = client.newCall(reqBuilder.build()).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) return null;
            JSONObject json = new JSONObject(respBody);
            JSONObject web = json.optJSONObject("web");
            JSONArray results = web != null ? web.optJSONArray("results") : null;
            if (results == null || results.length() == 0) return null;

            int limit = Math.min(results.length(), 8);
            StringBuilder extracted = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title", "").trim();
                String description = item.optString("description", "").trim();
                String resultUrl = item.optString("url", "").trim();
                if (title.isEmpty() && description.isEmpty() && resultUrl.isEmpty()) continue;
                if (extracted.length() > 0) extracted.append("\n\n");
                extracted.append("[").append(i + 1).append("]");
                if (!title.isEmpty()) extracted.append(" ").append(title);
                extracted.append("\n");
                if (!resultUrl.isEmpty()) extracted.append(resultUrl).append("\n");

                if (braveBoostEnabled && !resultUrl.isEmpty()) {
                    String pageText = fetchUrlContent(resultUrl, 1500);
                    if (pageText != null) {
                        extracted.append(pageText);
                    } else {
                        if (!description.isEmpty()) extracted.append(description);
                        JSONArray snippets = item.optJSONArray("extra_snippets");
                        if (snippets != null) {
                            for (int j = 0; j < snippets.length() && j < 2; j++) {
                                String s = snippets.optString(j, "").trim();
                                if (!s.isEmpty()) extracted.append("\n").append(s);
                            }
                        }
                    }
                } else {
                    if (!description.isEmpty()) extracted.append(description);
                    JSONArray snippets = item.optJSONArray("extra_snippets");
                    if (snippets != null) {
                        for (int j = 0; j < snippets.length() && j < 2; j++) {
                            String s = snippets.optString(j, "").trim();
                            if (!s.isEmpty()) extracted.append("\n").append(s);
                        }
                    }
                }
            }
            String extractedText = extracted.toString().trim();
            if (extractedText.isEmpty()) return null;
            return "SEARCH_RESULTS:\n" + extractedText;
        } catch (Exception e) {
            DebugLogger.log(this, "callBraveWebSearchApi error: " + e.getMessage());
            return null;
        }
    }

    private String fetchUrlContent(String url, int maxChars) {
        try {
            okhttp3.OkHttpClient fetchClient = client.newBuilder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            Request req = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36")
                    .addHeader("Accept", "text/html,application/xhtml+xml")
                    .build();
            try (Response resp = fetchClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) return null;
                String contentType = resp.header("Content-Type", "");
                if (!contentType.contains("text/html") && !contentType.contains("text/plain")
                        && !contentType.contains("application/xhtml")) return null;
                String html = resp.body() != null ? resp.body().string() : "";
                if (html.length() > 300_000) html = html.substring(0, 300_000);
                String text = stripHtml(html);
                if (text.isEmpty()) return null;
                return text.length() > maxChars ? text.substring(0, maxChars) : text;
            }
        } catch (Exception e) {
            DebugLogger.log(this, "fetchUrlContent failed: " + url + " - " + e.getMessage());
            return null;
        }
    }

    private String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String pre = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ");
        String text;
        try {
            text = android.text.Html.fromHtml(pre, android.text.Html.FROM_HTML_MODE_COMPACT).toString();
        } catch (Exception e) {
            text = pre.replaceAll("<[^>]+>", " ")
                      .replace("&nbsp;", " ").replace("&amp;", "&")
                      .replace("&lt;", "<").replace("&gt;", ">")
                      .replace("&quot;", "\"").replace("&#39;", "'")
                      .replaceAll("&#\\d+;", "");
        }
        return text.replaceAll(" {2,}", " ").replaceAll("\n{3,}", "\n\n").trim();
    }

    private void extractAllStringValues(Object obj, StringBuilder sb, int depth) {
        if (depth > 10) return;
        try {
            if (obj instanceof JSONObject) {
                JSONObject json = (JSONObject) obj;
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    extractAllStringValues(json.get(key), sb, depth + 1);
                }
            } else if (obj instanceof JSONArray) {
                JSONArray arr = (JSONArray) obj;
                for (int i = 0; i < arr.length() && i < 10; i++) {
                    extractAllStringValues(arr.get(i), sb, depth + 1);
                }
            } else if (obj instanceof String) {
                String str = ((String) obj).trim();
                if (!str.isEmpty() && str.length() > 10 && !str.startsWith("http")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(str);
                }
            }
        } catch (Exception e) {
            DebugLogger.log(this, "extractAllStringValues error: " + e.getMessage());
        }
    }

    private String buildSearchAugmentedUserMessage(String userMsg, String searchResultsBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下はWeb検索結果です。SEARCH_RESULTSとして扱ってください。\n");
        sb.append(searchResultsBlock);
        sb.append("\n\n質問: ").append(userMsg);
        return sb.toString();
    }

    private void ensureVoiceController() {
        if (voiceController != null) {
            return;
        }
        voiceController = new VoiceInputController(this, new VoiceInputController.Callback() {
            @Override
            public void onListeningChanged(boolean listening) {
                isListening = listening;
                // 認識開始の瞬間に microphone 種別へ昇格し、終了で dataSync のみへ戻す。
                // start() は onListeningChanged(true)→startListeningInternal の順で呼ぶため、
                // レコグナイザがマイクを開く直前に種別が有効化される。
                setMicrophoneForegroundType(listening);
                updateSendButton();
            }

            @Override
            public void onResult(String text) {
                if (bubblePanel != null && bubblePanel.getVisibility() != View.VISIBLE) {
                    showBubble(true);
                }
                submitUserMessage(text);
            }

            @Override
            public boolean ensureRecordPermission() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(FloatOverlayService.this,
                            t("Microphone permission is required", "マイク権限が必要です"),
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void toast(String enText, String jaText) {
                Toast.makeText(FloatOverlayService.this, t(enText, jaText), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onIdle() {
                // フロートには auto-chatter が無いため何もしない。
            }
        });
    }

    private void startVoiceRecognition(boolean preferOffline) {
        if (!voiceInputEnabled || isProcessing || isListening) {
            return;
        }
        ensureVoiceController();
        voiceController.setLanguage(speechLang);
        voiceController.start(preferOffline);
    }

    private void cancelVoiceRecognition() {
        if (voiceController != null) {
            voiceController.cancel();
        }
        isListening = false;
        updateSendButton();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                applyTtsSettings();
            }
        });
    }

    private void applyTtsSettings() {
        if (!ttsReady || tts == null) return;
        try {
            String[] parts = speechLang.split("-");
            Locale locale = parts.length >= 2 ? new Locale(parts[0], parts[1]) : new Locale(parts[0]);
            tts.setLanguage(locale);
        } catch (Exception e) {
            tts.setLanguage(Locale.JAPAN);
        }
        tts.setSpeechRate(speechRate);
        tts.setPitch(speechPitch);
    }

    private void speakText(String text) {
        if (!ttsEnabled || !ttsReady || tts == null || TextUtils.isEmpty(text)) return;
        // Markdown / HTML の装飾はタグ名ごと先に除去（句読点正規化で語として残さない）。
        String source = TtsTextSanitizer.sanitize(text.replaceAll("<think>[\\s\\S]*?</think>", " "));
        String clean = source
                .replaceAll("[\\n\\r\\t]", "、")
                .replaceAll("[!@#$%^&*()_+={}\\[\\]|\\\\:;<>.?/]", "、")
                .replaceAll("[,\u201c\u201d]", " ")
                .replaceAll("、+", "、")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isEmpty()) return;
        applyTtsSettings();
        ttsSpeaking.set(true);
        updateAvatarAnimation();
        int result = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "float_utt_" + System.currentTimeMillis());
        if (result != TextToSpeech.SUCCESS) {
            ttsSpeaking.set(false);
            updateAvatarAnimation();
        } else {
            long estimateMs = Math.max(500L, Math.min(12000L, clean.length() * 60L));
            mainHandler.postDelayed(() -> {
                ttsSpeaking.set(false);
                updateAvatarAnimation();
            }, estimateMs);
        }
    }

    private void sendStreaming(Request request, int requestToken) {
        Call call = client.newCall(request);
        currentCall = call;
        isStreamingResponse = true;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                if (call.isCanceled()) {
                    return;
                }
                isStreamingResponse = false;
                finishResponse(errorText(getApiUnavailableMessage()), requestToken);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    currentCall = null;
                    isStreamingResponse = false;
                    finishResponse(errorText("HTTP error: " + response.code()), requestToken);
                    return;
                }

                String visibleResponse = "";
                try (InputStream is = response.body() != null ? response.body().byteStream() : null;
                     BufferedReader reader = is != null
                             ? new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                             : null) {
                    if (reader == null) {
                        isStreamingResponse = false;
                        finishResponse(t("(No response)", "（応答なし）"), requestToken);
                        return;
                    }

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        JSONObject json = new JSONObject(line);
                        if (json.has("message")) {
                            visibleResponse += json.getJSONObject("message").optString("content", "");
                            setResponseText(visibleResponse, requestToken);
                            updateNotificationResponse(visibleResponse);
                        }
                        if (json.optBoolean("done", false)) {
                            break;
                        }
                    }
                    finishResponse(visibleResponse.trim().isEmpty()
                            ? t("(No response)", "（応答なし）")
                            : visibleResponse, requestToken);
                } catch (Exception e) {
                    if (!call.isCanceled()) {
                        isStreamingResponse = false;
                        finishResponse(errorText("Stream error: " + e.getMessage()), requestToken);
                    }
                } finally {
                    currentCall = null;
                }
            }
        });
    }

    private void sendNonStreaming(Request request, int requestToken) {
        Call call = client.newCall(request);
        currentCall = call;
        isStreamingResponse = false;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                if (call.isCanceled()) {
                    return;
                }
                finishResponse(errorText(getApiUnavailableMessage()), requestToken);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        finishResponse(errorText("HTTP error: " + response.code()), requestToken);
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    String text = json.optJSONObject("message") != null
                            ? json.optJSONObject("message").optString("content", "")
                            : "";
                    finishResponse(text.trim().isEmpty()
                            ? t("(No response)", "（応答なし）")
                            : text, requestToken);
                } catch (Exception e) {
                    finishResponse(errorText(e.getMessage()), requestToken);
                } finally {
                    currentCall = null;
                }
            }
        });
    }

    private void finishResponse(String responseText, int requestToken) {
        if (requestToken != activeResponseToken) return;
        stopThinkingIndicator(requestToken);
        boolean hasAssistantContent = !TextUtils.isEmpty(responseText) && !responseText.startsWith(t("Error: ", "エラー: "));
        if (hasAssistantContent) {
            addToHistory("assistant", responseText);
            // 共有ストアへ発行（MainActivity と同期）。バブルは setResponseText で描画済みのため renderedCount を進める。
            conversationStore.append("assistant", responseText);
            storeRenderedCount = conversationStore.size();
        }
        isProcessing = false;
        updateAvatarAnimation();
        updateSendButton();
        setResponseText(responseText, requestToken);
        isStreamingResponse = false;
        updateNotificationResponse(responseText);
        if (hasAssistantContent) {
            speakText(responseText);
            if (autoVoiceInputEnabled) {
                mainHandler.postDelayed(() -> startVoiceRecognition(true), 200L);
            }
        }
    }

    private String errorText(String message) {
        String detail = TextUtils.isEmpty(message) ? t("Unknown error", "不明なエラー") : message;
        return t("Error: ", "エラー: ") + detail;
    }

    private void setResponseText(String text, int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            if (TextUtils.isEmpty(text)) return;
            stopThinkingIndicator(requestToken);
            if (currentResponseBubble == null) {
                currentResponseBubble = appendMessageBubble(baseName, text, false);
            } else {
                renderMessageBubble(currentResponseBubble, baseName, text);
                adjustMessageAreaHeight();
                scrollMessagesToBottom();
            }
            latestNotificationResponse = compactNotificationText(text);
            ensureForegroundNotification();
        });
    }

    private void startThinkingIndicator(String label, int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            thinkingToken = requestToken;
            thinkingDotStep = 0;
            thinkingLabel = TextUtils.isEmpty(label) ? t("Thinking", "思考中") : label;
            if (currentResponseBubble == null) {
                currentResponseBubble = appendAssistantMessageBubble("");
            }
            applyThinkingText();
            mainHandler.removeCallbacks(thinkingAnimationRunnable);
            mainHandler.postDelayed(thinkingAnimationRunnable, THINKING_ANIMATION_INTERVAL_MS);
            ensureForegroundNotification();
        });
    }

    private void updateThinkingLabel(String label, int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            thinkingToken = requestToken;
            thinkingLabel = TextUtils.isEmpty(label) ? t("Thinking", "思考中") : label;
            applyThinkingText();
            ensureForegroundNotification();
        });
    }

    private void stopThinkingIndicator(int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            mainHandler.removeCallbacks(thinkingAnimationRunnable);
            thinkingLabel = "";
            thinkingToken = 0;
            thinkingDotStep = 0;
        });
    }

    private void applyThinkingText() {
        if (currentResponseBubble == null || TextUtils.isEmpty(thinkingLabel)) return;
        int dots = (thinkingDotStep % 3) + 1;
        StringBuilder body = new StringBuilder(thinkingLabel);
        for (int i = 0; i < dots; i++) {
            body.append('.');
        }
        renderPlainMessageBubble(currentResponseBubble, baseName, body.toString());
        adjustMessageAreaHeight();
        scrollMessagesToBottom();
        latestNotificationResponse = compactNotificationText(body.toString());
        ensureForegroundNotification();
    }

    private void updateNotificationResponse(String responseText) {
        mainHandler.post(() -> {
            latestNotificationResponse = compactNotificationText(responseText);
            ensureForegroundNotification();
        });
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void ensureOverlayInitialized() {
        loadSettings();
        if (overlayInitialized) {
            // 再表示時に残留した処理・認識状態をリセット
            if (isProcessing && currentCall == null) {
                isProcessing = false;
            }
            if (isListening) {
                cancelVoiceRecognition();
            }
            // 非表示中に MainActivity 側で追記された差分を取り込む。
            renderStoreDelta();
            updateBubbleHeader();
            updateSendButton();
            updateFloatVisual();
            return;
        }
        if (!hasOverlayPermission()) {
            DebugLogger.log(this, "Overlay initialization skipped: permission missing");
            return;
        }
        initOverlay();
        overlayInitialized = true;
        // 共有ログ全体を再構築して表示し、ストアの更新を購読する。
        rebuildFromStore();
        if (conversationStore == null) {
            conversationStore = ConversationStore.get(this);
        }
        conversationStore.addListener(storeListener);
        DebugLogger.log(this, "Overlay initialized successfully");
    }

    /** 共有ストアの内容で会話履歴とバブルを丸ごと再構築する（オーバーレイ生成時）。 */
    private void rebuildFromStore() {
        if (conversationStore == null) {
            conversationStore = ConversationStore.get(this);
        }
        clearOverlayMessages();
        initConversationHistory();
        storeRenderedCount = 0;
        renderStoreDelta();
    }

    /** 共有ストアの未描画分（storeRenderedCount 以降）だけをバブル化し会話履歴へ追記する。 */
    private void renderStoreDelta() {
        if (conversationStore == null || messageContainer == null) {
            return;
        }
        java.util.List<ConversationStore.Entry> snap = conversationStore.snapshot();
        for (int i = storeRenderedCount; i < snap.size(); i++) {
            ConversationStore.Entry e = snap.get(i);
            if ("user".equals(e.role)) {
                appendUserMessageBubble(e.content);
                addToHistory("user", e.content);
            } else {
                appendAssistantMessageBubble(e.content);
                addToHistory("assistant", e.content);
            }
        }
        storeRenderedCount = snap.size();
        scrollMessagesToBottom();
    }

    private void teardownOverlay() {
        hideKeyboard();
        if (conversationStore != null) {
            conversationStore.removeListener(storeListener);
        }
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                DebugLogger.log(this, "Overlay teardown failed: " + e.getMessage());
            }
        }
        overlayView = null;
        layoutParams = null;
        floatVisualBackground = null;
        floatVisual = null;
        bubblePanel = null;
        messageScrollView = null;
        messageContainer = null;
        inputView = null;
        sendButton = null;
        hideButton = null;
        bubbleTitleView = null;
        gestureDetector = null;
        currentResponseBubble = null;
        overlayInitialized = false;
        openingAppFromOverlay = false;
    }

    private void clearOverlayMessages() {
        if (messageContainer == null) return;
        messageContainer.removeAllViews();
        currentResponseBubble = null;
        adjustMessageAreaHeight();
    }

    private void appendUserMessageBubble(String text) {
        String name = TextUtils.isEmpty(userName) ? t("User", "ユーザ") : userName;
        appendMessageBubble(name, text, true);
    }

    private TextView appendAssistantMessageBubble(String text) {
        return appendMessageBubble(baseName, text, false);
    }

    private void appendSystemMessage(String text) {
        appendMessageBubble("System", text, false);
    }

    private TextView appendMessageBubble(String name, String text, boolean isUserSide) {
        if (messageContainer == null) return null;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(isUserSide ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dpToPx(4);
        rowParams.bottomMargin = dpToPx(4);
        row.setLayoutParams(rowParams);

        TextView bubble = new TextView(this);
        bubble.setBackgroundResource(isUserSide ? R.drawable.chat_bubble_user : R.drawable.chat_bubble_assistant);
        bubble.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));
        bubble.setTextSize(14);
        bubble.setTextColor(0xFF000000);
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.7f);
        bubble.setMaxWidth(maxWidth);
        getMarkdownRenderer().prepare(bubble);
        renderMessageBubble(bubble, name, text);

        row.addView(bubble);
        messageContainer.addView(row);
        trimVisibleOverlayRows();
        adjustMessageAreaHeight();
        scrollMessagesToBottom();
        return bubble;
    }

    private MarkdownRenderer getMarkdownRenderer() {
        if (markdownRenderer == null) {
            markdownRenderer = MarkdownRenderer.create(this);
        }
        return markdownRenderer;
    }

    private void renderMessageBubble(TextView bubble, String name, String text) {
        getMarkdownRenderer().render(bubble, name, text);
    }

    private void renderPlainMessageBubble(TextView bubble, String name, String text) {
        getMarkdownRenderer().renderPlain(bubble, name, text);
    }

    private void adjustMessageAreaHeight() {
        if (messageScrollView == null || messageContainer == null) return;
        if (messageMaxHeightPx <= 0) {
            messageMaxHeightPx = Math.max(1, getResources().getDisplayMetrics().heightPixels / 4);
        }
        messageContainer.post(() -> {
            ViewGroup.LayoutParams lp = messageScrollView.getLayoutParams();
            if (lp == null) return;
            int desired = messageContainer.getHeight() + messageScrollView.getPaddingTop() + messageScrollView.getPaddingBottom();
            int clamped = Math.min(Math.max(desired, dpToPx(56)), messageMaxHeightPx);
            if (lp.height != clamped) {
                lp.height = clamped;
                messageScrollView.setLayoutParams(lp);
            }
            updateBubblePanelPosition();
        });
    }

    private void scrollMessagesToBottom() {
        if (messageScrollView == null) return;
        pendingScrollToBottom = true;
        if (scrollToBottomScheduled) return;
        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastScrollToBottomAtMs;
        long delay = Math.max(OVERLAY_SCROLL_SETTLE_MS, OVERLAY_SCROLL_MIN_INTERVAL_MS - Math.max(0L, elapsed));
        scrollToBottomScheduled = true;
        messageScrollView.postDelayed(() -> {
            scrollToBottomScheduled = false;
            if (messageScrollView == null || !pendingScrollToBottom) return;
            pendingScrollToBottom = false;
            View child = messageScrollView.getChildCount() > 0 ? messageScrollView.getChildAt(0) : null;
            if (child == null) return;
            int targetY = Math.max(0, child.getBottom() - messageScrollView.getHeight());
            messageScrollView.scrollTo(0, targetY);
            lastScrollToBottomAtMs = SystemClock.uptimeMillis();
        }, delay);
    }

    private void initConversationHistory() {
        synchronized (historyLock) {
            conversationHistory.clear();
            addToHistoryLocked("system", buildSystemPromptWithName(systemPromptText, baseName));
        }
    }

    private void addToHistory(String role, String content) {
        synchronized (historyLock) {
            addToHistoryLocked(role, content);
        }
    }

    private void addToHistoryLocked(String role, String content) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            conversationHistory.add(msg);
            trimConversationHistoryLocked();
        } catch (Exception ignored) {
        }
    }

    private void trimConversationHistoryLocked() {
        int keepStart = !conversationHistory.isEmpty()
                && "system".equals(conversationHistory.get(0).optString("role"))
                ? 1 : 0;
        int maxMessages = Math.max(MIN_RETAINED_HISTORY_MESSAGES, historyLimit * 4);
        while (conversationHistory.size() - keepStart > maxMessages) {
            conversationHistory.remove(keepStart);
        }
    }

    private void trimVisibleOverlayRows() {
        if (messageContainer == null) {
            return;
        }
        int maxRows = Math.max(24, historyLimit * 4);
        while (messageContainer.getChildCount() > maxRows) {
            messageContainer.removeViewAt(0);
        }
    }

    private String compactNotificationText(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String normalized = text.replace('\n', ' ').trim();
        if (normalized.length() <= MAX_NOTIFICATION_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_NOTIFICATION_TEXT_LENGTH - 3) + "...";
    }

    private String buildSystemPromptWithName(String basePrompt, String name) {
        String trimmedPrompt = basePrompt == null ? "" : basePrompt.trim();
        String trimmedName = name == null ? "" : name.trim();
        String trimmedUserName = userName == null ? "" : userName.trim();
        StringBuilder result = new StringBuilder();
        boolean isJapanese = "ja".equals(appLanguage);
        if (!trimmedName.isEmpty()) {
            if (isJapanese) {
                result.append("あなたは").append(trimmedName).append("という名前です。");
            } else {
                result.append("Your name is ").append(trimmedName).append(".");
            }
        }
        if (!trimmedPrompt.isEmpty()) {
            if (result.length() > 0) result.append("\n");
            result.append(trimmedPrompt);
        }
        if (!trimmedUserName.isEmpty()) {
            if (result.length() > 0) result.append("\n");
            if (isJapanese) {
                result.append("ユーザの名前は").append(trimmedUserName).append("です。");
            } else {
                result.append("The user's name is ").append(trimmedUserName).append(".");
            }
        }
        return result.toString();
    }

    private void loadSettings() {
        try (FileInputStream fis = openFileInput(SETTINGS_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            applySettingsJson(new JSONObject(sb.toString()));
        } catch (FileNotFoundException ignored) {
        } catch (Exception ignored) {
        }
    }

    private void saveSettings() {
        try {
            JSONObject settings = new JSONObject();
            try (FileInputStream fis = openFileInput(SETTINGS_FILE);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                if (sb.length() > 0) {
                    settings = new JSONObject(sb.toString());
                }
            } catch (FileNotFoundException ignored) {
            }
            settings.put("bubblePosX", bubblePosX);
            settings.put("bubblePosY", bubblePosY);
            settings.put("floatDisplayMode", normalizeFloatDisplayMode(floatDisplayMode));
            try (FileOutputStream fos = openFileOutput(SETTINGS_FILE, MODE_PRIVATE)) {
                fos.write(settings.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception e) {
            DebugLogger.log(this, "saveSettings failed: " + e.getMessage());
        }
    }

    private void applySettingsJson(JSONObject settings) {
        appLanguage = settings.optString("appLanguage", appLanguage);
        ollamaBaseUrl = settings.optString("ollamaBaseUrl", ollamaBaseUrl);
        selectedModel = settings.optString("selectedModel", selectedModel);
        streamingEnabled = settings.optBoolean("streamingEnabled", streamingEnabled);
        ttsEnabled = settings.optBoolean("ttsEnabled", ttsEnabled);
        voiceInputEnabled = settings.optBoolean("voiceInputEnabled", voiceInputEnabled);
        autoVoiceInputEnabled = settings.optBoolean("autoVoiceInputEnabled", autoVoiceInputEnabled);
        speechLang = settings.optString("speechLang", speechLang);
        speechRate = (float) settings.optDouble("speechRate", speechRate);
        speechPitch = (float) settings.optDouble("speechPitch", speechPitch);
        webSearchEnabled = settings.optBoolean("webSearchEnabled", webSearchEnabled);
        webSearchUrl = settings.optString("webSearchUrl", webSearchUrl);
        webSearchApiKey = settings.optString("webSearchApiKey", webSearchApiKey);
        braveBoostEnabled = settings.optBoolean("braveBoostEnabled", braveBoostEnabled);
        String savedMode = settings.optString("webSearchMode", "WIKIPEDIA");
        webSearchMode = "DDG".equals(savedMode) ? "WIKIPEDIA" : savedMode;
        expertModel = settings.optString("expertModel", settings.optString("webSearchModel", expertModel));
        memoryEnabled = settings.optBoolean("memoryEnabled", memoryEnabled);
        proactiveNotifyEnabled = settings.optBoolean("proactiveNotifyEnabled", proactiveNotifyEnabled);
        morningBriefingTime = settings.optString("morningBriefingTime", morningBriefingTime);
        newsModeEnabled = settings.optBoolean("newsModeEnabled", newsModeEnabled);
        newsBriefingTimes = settings.optString("newsBriefingTimes", newsBriefingTimes);
        structuredOutputMode = StructuredOutput.Mode.fromString(
                settings.optString("structuredOutputMode", structuredOutputMode)).name();
        systemPromptText = settings.optString("systemPrompt", systemPromptText);
        numCtx = settings.optInt("numCtx", DEFAULT_NUM_CTX);
        numPredict = settings.optInt("numPredict", DEFAULT_NUM_PREDICT);
        if (numCtx <= 0) numCtx = DEFAULT_NUM_CTX;
        baseName = settings.optString("baseName", baseName);
        userName = settings.optString("userName", userName);
        historyLimit = Math.max(0, settings.optInt("historyLimit", historyLimit));
        floatDisplayMode = normalizeFloatDisplayMode(settings.optString("floatDisplayMode", floatDisplayMode));
        bubblePosX = Math.max(0, settings.optInt("bubblePosX", bubblePosX));
        bubblePosY = Math.max(0, settings.optInt("bubblePosY", bubblePosY));
        if (TextUtils.isEmpty(baseName)) {
            baseName = defaultBaseName();
        }
        if (TextUtils.isEmpty(systemPromptText)) {
            systemPromptText = defaultSystemPrompt();
        }
        if (TextUtils.isEmpty(selectedModel)) {
            selectedModel = "default";
        }
        if (TextUtils.isEmpty(expertModel)) {
            expertModel = "default";
        }
        if (TextUtils.isEmpty(webSearchUrl)) {
            webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
        }
        if (TextUtils.isEmpty(speechLang)) {
            speechLang = "ja-JP";
        }
        // 埋め込みモデルを設定から読み込み（MainActivity と同じ解決ロジック）
        embeddingModel = settings.optString("embeddingModel", "default");
        String resolvedEmbed = (embeddingModel == null || embeddingModel.trim().isEmpty()
                || "default".equals(embeddingModel.trim())) ? "default" : embeddingModel.trim();
        if (embeddingClient == null || !resolvedEmbed.equals(embeddingClient.getModel())) {
            embeddingClient = new EmbeddingClient(client, ollamaBaseUrl, resolvedEmbed);
            webSearchRagHelper = new WebSearchRagHelper(embeddingClient, client, ollamaBaseUrl, selectedModel);
        }
        applyTtsSettings();
    }

    // ========== お知らせ（予定 / ニュース）スケジューラ ==========

    private void startProactiveScheduler() {
        if (proactiveStarted) return;
        proactiveStarted = true;
        mainHandler.postDelayed(proactiveTickRunnable, 5_000L);
    }

    private void stopProactiveScheduler() {
        proactiveStarted = false;
        mainHandler.removeCallbacks(proactiveTickRunnable);
    }

    /** MainActivity 側で更新され得るお知らせ設定をファイルから読み直す（毎tick）。 */
    private void refreshProactiveSettingsFromFile() {
        try (FileInputStream fis = openFileInput(SETTINGS_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            if (sb.length() == 0) return;
            JSONObject s = new JSONObject(sb.toString());
            proactiveNotifyEnabled = s.optBoolean("proactiveNotifyEnabled", proactiveNotifyEnabled);
            morningBriefingTime = s.optString("morningBriefingTime", morningBriefingTime);
            newsModeEnabled = s.optBoolean("newsModeEnabled", newsModeEnabled);
            newsBriefingTimes = s.optString("newsBriefingTimes", newsBriefingTimes);
            ttsEnabled = s.optBoolean("ttsEnabled", ttsEnabled);
            memoryEnabled = s.optBoolean("memoryEnabled", memoryEnabled);
        } catch (Exception ignored) {
        }
    }

    private void onProactiveTick() {
        refreshProactiveSettingsFromFile();
        if (!proactiveNotifyEnabled && !newsModeEnabled) return;
        // ユーザ操作中・読み上げ中・別のお知らせ生成中は次tickへ見送る（マーカー未更新で再試行）。
        if (isProcessing || ttsSpeaking.get() || proactiveBusy.get()) return;

        LocalDateTime now = LocalDateTime.now();
        String today = now.toLocalDate().toString();
        SharedPreferences st = getSharedPreferences(PROACTIVE_PREFS, MODE_PRIVATE);

        // 朝のブリーフィング（指定時刻から2時間以内、1日1回）。
        if (proactiveNotifyEnabled) {
            LocalTime morning = parseLocalTime(morningBriefingTime, LocalTime.of(7, 0));
            LocalTime nowT = now.toLocalTime();
            boolean inWindow = !nowT.isBefore(morning) && nowT.isBefore(morning.plusHours(2));
            if (inWindow && !today.equals(st.getString("lastMorningDate", ""))) {
                st.edit().putString("lastMorningDate", today).apply();
                fireMorningBriefing();
                return;
            }
        }

        // 毎時の直近予定（時スロットごとに1回チェック）。
        if (proactiveNotifyEnabled) {
            String hourSlot = today + "-" + String.format(Locale.US, "%02d", now.getHour());
            if (!hourSlot.equals(st.getString("lastUpcomingHour", ""))) {
                st.edit().putString("lastUpcomingHour", hourSlot).apply();
                fireUpcomingReminder();
                return;
            }
        }

        // ニュース（指定時刻ごとに1日1回）。
        if (newsModeEnabled) {
            String slot = matchNewsSlot(now.toLocalTime(), newsBriefingTimes);
            if (slot != null && !today.equals(st.getString("lastNewsSlot:" + slot, ""))) {
                st.edit().putString("lastNewsSlot:" + slot, today).apply();
                fireNewsBriefing();
                return;
            }
        }
    }

    private LocalTime parseLocalTime(String hhmm, LocalTime fallback) {
        try {
            if (hhmm != null && hhmm.matches("\\d{1,2}:\\d{2}")) {
                String[] p = hhmm.split(":");
                int h = Integer.parseInt(p[0]);
                int m = Integer.parseInt(p[1]);
                if (h >= 0 && h < 24 && m >= 0 && m < 60) return LocalTime.of(h, m);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** now が設定ニュース時刻のいずれか（±2分）に該当すれば "HH:mm" を返す。 */
    private String matchNewsSlot(LocalTime now, String csv) {
        if (csv == null) return null;
        for (String raw : csv.split(",")) {
            LocalTime tt = parseLocalTime(raw.trim(), null);
            if (tt == null) continue;
            if (!now.isBefore(tt) && now.isBefore(tt.plusMinutes(2))) {
                return String.format(Locale.US, "%02d:%02d", tt.getHour(), tt.getMinute());
            }
        }
        return null;
    }

    private void fireMorningBriefing() {
        proactiveBusy.set(true);
        new Thread(() -> {
            try {
                boolean ja = "ja".equals(appLanguage);
                String agenda = "";
                String overdue = "";
                if (memoryEnabled && memoryRepository != null) {
                    MemoryFlowHelper helper = newMemoryHelper();
                    LocalDate today = LocalDate.now();
                    agenda = helper.formatAgenda(helper.agendaForDay(today));
                    overdue = helper.formatAgenda(helper.overdueTodos(today));
                }
                StringBuilder instruction = new StringBuilder(ja
                        ? "おはようございます。ユーザに朝のあいさつをし、今日の予定とやることを手短に伝えてください。"
                        : "Good morning. Greet the user and briefly tell them today's plans and to-dos.");
                if (!TextUtils.isEmpty(agenda)) {
                    instruction.append(ja ? "\n【今日の予定・期限】\n" : "\n[Today]\n").append(agenda);
                }
                if (!TextUtils.isEmpty(overdue)) {
                    instruction.append(ja ? "\n【やり残し】\n" : "\n[Overdue]\n").append(overdue);
                }
                if (TextUtils.isEmpty(agenda) && TextUtils.isEmpty(overdue)) {
                    instruction.append(ja ? "\n今日の予定は特にありません。" : "\nThere are no plans today.");
                }
                String fallback;
                if (TextUtils.isEmpty(agenda) && TextUtils.isEmpty(overdue)) {
                    fallback = ja ? "おはようございます！今日も一日よろしくお願いします。"
                                  : "Good morning! Have a great day!";
                } else {
                    StringBuilder fb = new StringBuilder(ja ? "おはようございます。" : "Good morning.");
                    if (!TextUtils.isEmpty(agenda)) fb.append(ja ? "\n今日の予定:\n" : "\nToday:\n").append(agenda);
                    if (!TextUtils.isEmpty(overdue)) fb.append(ja ? "\nやり残し:\n" : "\nOverdue:\n").append(overdue);
                    fallback = fb.toString();
                }
                finishProactive(generatePersonaText(instruction.toString(), fallback));
            } catch (Exception e) {
                DebugLogger.log(this, "fireMorningBriefing error: " + e.getMessage());
                finishProactive(null);
            }
        }).start();
    }

    private void fireUpcomingReminder() {
        if (!memoryEnabled || memoryRepository == null) {
            finishProactive(null);
            return;
        }
        proactiveBusy.set(true);
        new Thread(() -> {
            try {
                boolean ja = "ja".equals(appLanguage);
                MemoryFlowHelper helper = newMemoryHelper();
                List<MemoryRecord> upcoming =
                        helper.upcomingWithin(LocalDateTime.now(), UPCOMING_WINDOW_MINUTES);
                if (upcoming.isEmpty()) {
                    finishProactive(null);
                    return;
                }
                String list = helper.formatAgenda(upcoming);
                String instruction = ja
                        ? ("まもなくの予定をユーザへ手短に知らせてください。\n" + list)
                        : ("Briefly remind the user of these upcoming items.\n" + list);
                String fallback = (ja ? "まもなくの予定:\n" : "Upcoming:\n") + list;
                finishProactive(generatePersonaText(instruction, fallback));
            } catch (Exception e) {
                DebugLogger.log(this, "fireUpcomingReminder error: " + e.getMessage());
                finishProactive(null);
            }
        }).start();
    }

    private void fireNewsBriefing() {
        proactiveBusy.set(true);
        new Thread(() -> {
            try {
                if (!isWebSearchExpertAvailable()) {
                    finishProactive(null);
                    return;
                }
                String query = "ja".equals(appLanguage)
                        ? "最新ニュース 今日 主要 ヘッドライン"
                        : "today's top news headlines";
                String results = callWebSearchApi(query);
                if (TextUtils.isEmpty(results)) {
                    finishProactive(null);
                    return;
                }
                String instruction = "ja".equals(appLanguage)
                        ? ("次の検索結果から最新ニュースの要点を3つほど、あなたの口調でユーザへ簡潔に知らせてください。憶測は避け、検索結果に基づいてください。\n" + results)
                        : ("From the following search results, tell the user about three key latest news items, concisely and in your own voice. Stay grounded in the results.\n" + results);
                String headlines = formatNewsHeadlines(results);
                String fallback = ("ja".equals(appLanguage)
                        ? "最新ニュースをお伝えします。\n"
                        : "Here are the latest headlines.\n") + headlines;
                finishProactive(generatePersonaText(instruction, fallback.trim()));
            } catch (Exception e) {
                DebugLogger.log(this, "fireNewsBriefing error: " + e.getMessage());
                finishProactive(null);
            }
        }).start();
    }

    /** ペルソナ（システムプロンプト＋名前）で指示文を会話調に言い換える。失敗時は fallback を返す。 */
    private String generatePersonaText(String instruction, String fallback) {
        try {
            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", buildSystemPromptWithName(systemPromptText, baseName));
            messages.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", instruction);
            messages.put(user);
            JSONObject body = new JSONObject();
            body.put("model", TextUtils.isEmpty(selectedModel) ? "default" : selectedModel);
            body.put("messages", messages);
            body.put("stream", false);
            applyOllamaOptions(body);
            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/chat")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();
            Response response = client.newCall(request).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) return fallback;
            JSONObject json = new JSONObject(respBody);
            String content = json.has("message")
                    ? json.getJSONObject("message").optString("content", "")
                    : "";
            content = content.replaceAll("<think>[\\s\\S]*?</think>", " ").trim();
            return TextUtils.isEmpty(content) ? fallback : content;
        } catch (Exception e) {
            DebugLogger.log(this, "generatePersonaText error: " + e.getMessage());
            return fallback;
        }
    }

    private String formatNewsHeadlines(String searchResults) {
        if (searchResults == null) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : searchResults.split("\n")) {
            String s = line.trim();
            if (s.matches("\\[\\d+\\].*")) {
                String title = s.replaceFirst("^\\[\\d+\\]\\s*", "").trim();
                if (!title.isEmpty()) {
                    sb.append("- ").append(title).append("\n");
                    if (++n >= 3) break;
                }
            }
        }
        return sb.toString().trim();
    }

    private void finishProactive(String text) {
        mainHandler.post(() -> {
            try {
                if (!TextUtils.isEmpty(text)) {
                    pushProactiveMessage(text);
                }
            } finally {
                proactiveBusy.set(false);
            }
        });
    }

    /** お知らせをポップアップチャットへ表示し（吹き出しを自動オープン）、読み上げ有効なら音声でも知らせる。 */
    private void pushProactiveMessage(String text) {
        if (TextUtils.isEmpty(text)) return;
        ensureOverlayInitialized();
        showBubble(true);
        currentResponseBubble = null;
        appendAssistantMessageBubble(text);
        addToHistory("assistant", text);
        conversationStore.append("assistant", text);
        storeRenderedCount = conversationStore.size();
        updateNotificationResponse(text);
        speakText(text);
        adjustMessageAreaHeight();
        scrollMessagesToBottom();
    }

    private String resolveExpertModel() {
        String model = expertModel == null ? "" : expertModel.trim();
        if (model.isEmpty()) {
            model = "default";
        }
        return model;
    }

    private String normalizeFloatDisplayMode(String value) {
        return FLOAT_DISPLAY_MODE_ICON.equals(value) ? FLOAT_DISPLAY_MODE_ICON : FLOAT_DISPLAY_MODE_AVATAR;
    }

    private void loadAvatarBitmaps() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        avatarC0Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C0_FILE));
        avatarC1Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C1_FILE));
        avatarC2Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C2_FILE));
        avatarC3Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C3_FILE));
    }

    private Bitmap loadAvatarBitmap(File file) {
        if (file == null || !file.exists()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void setAvatarBackground(int resId) {
        if (floatVisualBackground == null) return;
        Bitmap custom = resId == R.drawable.c0 ? avatarC0Bitmap : null;
        if (custom != null) {
            floatVisualBackground.setImageBitmap(custom);
        } else {
            floatVisualBackground.setImageResource(resId);
        }
    }

    private void setAvatarFrame(int resId) {
        if (floatVisual == null) return;
        Bitmap custom = null;
        if (resId == R.drawable.c1) custom = avatarC1Bitmap;
        else if (resId == R.drawable.c2) custom = avatarC2Bitmap;
        else if (resId == R.drawable.c3) custom = avatarC3Bitmap;
        if (custom != null) {
            floatVisual.setImageBitmap(custom);
        } else {
            floatVisual.setImageResource(resId);
        }
    }

    private float getAvatarAspectRatio() {
        if (avatarC1Bitmap != null && !avatarC1Bitmap.isRecycled()
                && avatarC1Bitmap.getWidth() > 0 && avatarC1Bitmap.getHeight() > 0) {
            return (float) avatarC1Bitmap.getWidth() / avatarC1Bitmap.getHeight();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), R.drawable.c1, options);
        if (options.outWidth > 0 && options.outHeight > 0) {
            return (float) options.outWidth / options.outHeight;
        }
        return 1f;
    }

    private void applyAvatarPosition() {
        if (floatVisual == null) return;
        ViewGroup.LayoutParams rawParams = floatVisual.getLayoutParams();
        if (!(rawParams instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
        boolean iconMode = FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode);
        boolean bubbleVisible = bubblePanel != null && bubblePanel.getVisibility() == View.VISIBLE;
        boolean compactWindow = iconMode || !bubbleVisible;
        if (compactWindow) {
            params.leftMargin = 0;
            params.topMargin = 0;
            if (layoutParams != null) {
                layoutParams.x = avatarPosX;
                layoutParams.y = avatarPosY;
                updateOverlayLayout();
            }
        } else {
            params.leftMargin = avatarPosX;
            params.topMargin = avatarPosY;
        }
        floatVisual.setLayoutParams(params);
        updateBubblePanelPosition();
    }

    private void attachBubbleDragListener(View target, boolean ignoreInteractiveHit) {
        if (target == null) return;
        target.setOnTouchListener((v, event) -> {
            if (ignoreInteractiveHit && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && isBubbleInteractiveHit(event.getRawX(), event.getRawY())) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    draggingBubble = false;
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    initialX = bubblePosX;
                    initialY = bubblePosY;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchStartX;
                    float dy = event.getRawY() - touchStartY;
                    if (!draggingBubble && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        draggingBubble = true;
                    }
                    if (draggingBubble) {
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        int screenH = getResources().getDisplayMetrics().heightPixels;
                        int bubbleW = (bubblePanel.getLayoutParams() instanceof FrameLayout.LayoutParams)
                                ? ((FrameLayout.LayoutParams) bubblePanel.getLayoutParams()).width
                                : dpToPx(250);
                        if (bubbleW <= 0) bubbleW = dpToPx(250);
                        int bubbleH = bubblePanel.getHeight();
                        if (bubbleH <= 0) bubbleH = dpToPx(300);
                        int nextX = initialX + (int) dx;
                        int nextY = initialY + (int) dy;
                        int maxX = Math.max(0, screenW - bubbleW);
                        int maxY = Math.max(0, screenH - bubbleH);
                        bubblePosX = Math.max(0, Math.min(nextX, maxX));
                        bubblePosY = Math.max(0, Math.min(nextY, maxY));
                        updateBubblePanelPosition();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (draggingBubble) {
                        draggingBubble = false;
                        saveSettings();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private boolean isBubbleInteractiveHit(float rawX, float rawY) {
        return isPointInsideView(hideButton, rawX, rawY)
                || isPointInsideView(inputView, rawX, rawY)
                || isPointInsideView(sendButton, rawX, rawY)
                || isPointInsideView(messageScrollView, rawX, rawY);
    }

    private boolean isPointInsideView(View target, float rawX, float rawY) {
        if (target == null || target.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        int left = location[0];
        int top = location[1];
        int right = left + target.getWidth();
        int bottom = top + target.getHeight();
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }

    private void updateBubblePanelPosition() {
        if (bubblePanel == null || floatVisual == null) return;
        if (!(bubblePanel.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams bubbleParams = (FrameLayout.LayoutParams) bubblePanel.getLayoutParams();
        int marginPx = dpToPx(FLOAT_BUBBLE_MARGIN_DP);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int bubbleW = bubbleParams.width > 0 ? bubbleParams.width : bubblePanel.getWidth();
        if (bubbleW <= 0) bubbleW = Math.min(dpToPx(300), Math.max(dpToPx(200), screenW - marginPx * 2));
        
        // If bubble has been manually positioned, use that position
        if (bubblePosX != 0 || bubblePosY != 0) {
            bubbleParams.gravity = Gravity.TOP | Gravity.START;
            bubbleParams.rightMargin = 0;
            bubbleParams.bottomMargin = 0;
            bubbleParams.leftMargin = bubblePosX;
            bubbleParams.topMargin = bubblePosY;
            bubblePanel.setLayoutParams(bubbleParams);
            return;
        }
        
        if (FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode)) {
            bubbleParams.gravity = Gravity.BOTTOM | Gravity.START;
            bubbleParams.leftMargin = marginPx;
            bubbleParams.topMargin = 0;
            bubbleParams.rightMargin = marginPx;
            bubbleParams.bottomMargin = marginPx;
            bubblePanel.setLayoutParams(bubbleParams);
            return;
        }
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.rightMargin = 0;
        bubbleParams.bottomMargin = 0;
        int desiredLeft = avatarPosX + ((avatarWidthPx - bubbleW) / 2);
        int maxLeft = Math.max(0, screenW - bubbleW);
        int left = Math.max(0, Math.min(desiredLeft, maxLeft));
        if (bubblePanel.getVisibility() != View.VISIBLE) {
            bubbleParams.leftMargin = left;
            bubbleParams.topMargin = avatarPosY + avatarHeightPx;
            bubblePanel.setLayoutParams(bubbleParams);
            return;
        }
        int bubbleH = bubblePanel.getHeight();
        if (bubbleH <= 0) {
            bubblePanel.measure(
                    View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
            );
            bubbleH = bubblePanel.getMeasuredHeight();
        }
        int belowTop = avatarPosY + avatarHeightPx;
        int maxTop = Math.max(0, screenH - bubbleH);
        int top = Math.min(belowTop, maxTop);
        bubbleParams.leftMargin = left;
        bubbleParams.topMargin = Math.max(0, top);
        bubblePanel.setLayoutParams(bubbleParams);
    }

    private void scheduleNextBlink() {
        mainHandler.removeCallbacks(blinkRunnable);
        mainHandler.removeCallbacks(blinkResetRunnable);
        int delay = AVATAR_BLINK_MIN_MS + avatarRandom.nextInt(AVATAR_BLINK_MAX_MS - AVATAR_BLINK_MIN_MS + 1);
        mainHandler.postDelayed(blinkRunnable, delay);
    }

    private void startIdleAnimation() {
        stopTalkAnimation();
        setAvatarFrame(R.drawable.c1);
        scheduleNextBlink();
    }

    private void startTalkAnimation() {
        stopIdleAnimation();
        talkFrameIndex = 0;
        mainHandler.removeCallbacks(talkRunnable);
        mainHandler.post(talkRunnable);
    }

    private void stopIdleAnimation() {
        mainHandler.removeCallbacks(blinkRunnable);
        mainHandler.removeCallbacks(blinkResetRunnable);
    }

    private void stopTalkAnimation() {
        mainHandler.removeCallbacks(talkRunnable);
    }

    private void stopAvatarAnimation() {
        stopIdleAnimation();
        stopTalkAnimation();
    }

    private String defaultBaseName() {
        return "ja".equals(appLanguage) ? DEFAULT_BASE_NAME_JA : DEFAULT_BASE_NAME_EN;
    }

    private String defaultSystemPrompt() {
        return "ja".equals(appLanguage) ? DEFAULT_SYSTEM_PROMPT_JA : DEFAULT_SYSTEM_PROMPT_EN;
    }

    private String getApiUnavailableMessage() {
        return t(
                "I'm very sorry, but the LLM API is not available. Please return to the app and check the API settings.",
                "大変申し訳ありませんが、LLM APIが有効になっていません。アプリ画面へ戻ってAPI設定を確認してください。"
        );
    }

    private String t(String en, String ja) {
        return "ja".equals(appLanguage) ? ja : en;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static final class ViewConfigurationHolder {
        private static int get(Service service) {
            return android.view.ViewConfiguration.get(service).getScaledTouchSlop();
        }
    }
}
