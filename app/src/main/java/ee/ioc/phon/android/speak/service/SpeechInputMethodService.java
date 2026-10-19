package ee.ioc.phon.android.speak.service;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ee.ioc.phon.android.speak.Log;
import ee.ioc.phon.android.speak.R;
import ee.ioc.phon.android.speak.ServiceLanguageChooser;
import ee.ioc.phon.android.speak.activity.PermissionsRequesterActivity;
import ee.ioc.phon.android.speak.model.CallerInfo;
import ee.ioc.phon.android.speak.model.Rewrites;
import ee.ioc.phon.android.speak.utils.Utils;
import ee.ioc.phon.android.speak.view.AbstractSpeechInputViewListener;
import ee.ioc.phon.android.speak.view.SpeechInputView;
import ee.ioc.phon.android.speechutils.Extras;
import ee.ioc.phon.android.speechutils.editor.CommandEditor;
import ee.ioc.phon.android.speechutils.editor.CommandEditorResult;
import ee.ioc.phon.android.speechutils.editor.InputConnectionCommandEditor;
import ee.ioc.phon.android.speechutils.editor.Op;
import ee.ioc.phon.android.speechutils.editor.RuleManager;
import ee.ioc.phon.android.speechutils.editor.UtteranceRewriter;
import ee.ioc.phon.android.speechutils.utils.PreferenceUtils;

public class SpeechInputMethodService extends InputMethodService {

    // TODO: move somewhere else and make end-user configurable
    private static final String REWRITES_NAME_RECENT = "#r";
    private static final String REWRITES_NAME_FREQUENT = "#f";
    private static final String REWRITES_NAME_CLIP = "#c";

    // Push-to-talk: start listening on PTT_START, stop listening on PTT_STOP, and once the
    // transcription has been committed, switch back to the previous IME.
    // If the keyboard panel is not shown, the take runs without it (a headless take): the text is
    // typed into the focused field, or put on the clipboard if no field is focused. A headless take
    // does not switch the IME back; the helper that sent the broadcasts does that.
    public static final String ACTION_PTT_START = "ee.ioc.phon.android.speak.action.PTT_START";
    public static final String ACTION_PTT_STOP = "ee.ioc.phon.android.speak.action.PTT_STOP";
    // PTT_START can arrive just before the input view is shown, e.g. right after the IME was
    // selected. If the view has not started after this long, the take runs headless.
    private static final long PTT_VIEW_WAIT_MS = 400;
    // Same retry as in SpeechInputView: a new recognizer can be refused while Android still closes
    // the previous connection to the service.
    private static final int HEADLESS_RETRY_MAX = 3;
    private static final int HEADLESS_RETRY_DELAY_MS = 400;
    private static final long HEADLESS_RETRY_WINDOW_MS = 2000;
    // Logged whatever Log.DEBUG says, so that a failed take can be read from logcat.
    private static final String PTT_TAG = "k6nele-ptt";

    private final BroadcastReceiver mPttReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("onReceive: " + intent.getAction());
            if (ACTION_PTT_START.equals(intent.getAction())) {
                if (mIsInputViewStarted) {
                    startPtt();
                } else {
                    mIsPttPending = true;
                    mHandler.removeCallbacks(mStartHeadless);
                    mHandler.postDelayed(mStartHeadless, PTT_VIEW_WAIT_MS);
                }
            } else if (ACTION_PTT_STOP.equals(intent.getAction())) {
                mIsPttPending = false;
                mHandler.removeCallbacks(mStartHeadless);
                if (mIsPtt && mInputView != null) {
                    mInputView.stopListening();
                }
                stopHeadless();
            }
        }
    };
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStartHeadless = this::startHeadless;
    private final Runnable mRetryHeadless = this::listenHeadless;
    private boolean mIsInputViewStarted;
    private boolean mIsPtt;
    private boolean mIsPttPending;

    // The headless take. The recognizer is kept between takes, like the keyboard panel keeps its own.
    private SpeechRecognizer mHeadlessRecognizer;
    private ComponentName mHeadlessService;
    private String mHeadlessLanguage;
    private ComponentName mHeadlessApp;
    private Intent mHeadlessIntent;
    private boolean mIsHeadless;
    private boolean mIsHeadlessStopped;
    private boolean mIsHeadlessRetryAllowed;
    private int mHeadlessRetryCount;
    private long mHeadlessStartTime;

    private boolean mFlagPersonalizedLearning = true;
    private InputMethodManager mInputMethodManager;
    private SpeechInputView mInputView;
    private CommandEditor mCommandEditor;
    private boolean mShowPartialResults;
    private SharedPreferences mPrefs;
    private Resources mRes;
    private RuleManager mRuleManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("onCreate");
        mInputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mCommandEditor = new InputConnectionCommandEditor(getApplicationContext());
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        mRes = getResources();
        mRuleManager = new RuleManager();

        Rewrites rewritesClip = new Rewrites(mPrefs, mRes, REWRITES_NAME_CLIP);
        if (rewritesClip.isSelected()) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // TODO: remove the listener onFinish
            clipboard.addPrimaryClipChangedListener(() -> {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData != null) {
                    String clip = clipData.getItemAt(0).getText().toString();
                    // Empty strings make less sense as clips
                    if (!clip.isEmpty()) {
                        UtteranceRewriter ur = mRuleManager.addRecent(clip, rewritesClip.getRewrites());
                        PreferenceUtils.putPrefMapEntry(mPrefs, mRes, R.string.keyRewritesMap, REWRITES_NAME_CLIP, ur.toTsv());
                        mCommandEditor.setRewriters(
                                Utils.makeList(
                                        Utils.genRewriters(mPrefs, mRes, null, mRuleManager.getCommandMatcher())));
                    }
                }
            });
        }

        // The receiver must be exported because the PTT broadcasts come from the shell (am broadcast),
        // run by a root helper that watches the volume key. Requiring the sender to hold
        // WRITE_SECURE_SETTINGS limits the senders to the shell, root and system apps: normal apps
        // cannot get this permission, so they cannot start the microphone through this receiver.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PTT_START);
        filter.addAction(ACTION_PTT_STOP);
        ContextCompat.registerReceiver(this, mPttReceiver, filter, Manifest.permission.WRITE_SECURE_SETTINGS, null,
                ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        Log.i("onDestroy");
        unregisterReceiver(mPttReceiver);
        mHandler.removeCallbacks(mStartHeadless);
        mHandler.removeCallbacks(mRetryHeadless);
        if (mIsHeadless) {
            android.util.Log.i(PTT_TAG, "headless take cancelled: keyboard service destroyed");
        }
        mIsHeadless = false;
        destroyHeadlessRecognizer();
        super.onDestroy();
    }

    private void startPtt() {
        mIsPttPending = false;
        mHandler.removeCallbacks(mStartHeadless);
        mIsPtt = true;
        mInputView.start();
    }

    /**
     * Starts a push-to-talk take without the keyboard panel, with the service, language and
     * settings of the combo that the panel would use for the focused app.
     */
    private void startHeadless() {
        if (!mIsPttPending) {
            return;
        }
        mIsPttPending = false;
        if (mIsHeadless) {
            android.util.Log.i(PTT_TAG, "headless take already running");
            return;
        }
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        String packageName = editorInfo == null ? null : editorInfo.packageName;
        mHeadlessApp = packageName == null ? null : new ComponentName(packageName, packageName);
        CallerInfo callerInfo = editorInfo == null
                ? new CallerInfo(makeExtras(), (ComponentName) null)
                : new CallerInfo(makeExtras(), editorInfo, getPackageName());
        ServiceLanguageChooser slc = new ServiceLanguageChooser(this, mPrefs, R.array.keysIme, callerInfo,
                mHeadlessApp == null ? "" : mHeadlessApp.flattenToShortString());
        ComponentName service = slc.getService();
        if (mHeadlessRecognizer != null && !Objects.equals(service, mHeadlessService)) {
            destroyHeadlessRecognizer();
        }
        mHeadlessService = service;
        mHeadlessLanguage = slc.getLanguage();
        mHeadlessIntent = slc.getIntent();
        mIsHeadless = true;
        mIsHeadlessStopped = false;
        mHeadlessRetryCount = 0;
        mHeadlessStartTime = System.currentTimeMillis();
        android.util.Log.i(PTT_TAG, "headless take: " + slc.getCombo() + " for " + packageName);
        listenHeadless();
    }

    private void listenHeadless() {
        if (!mIsHeadless) {
            return;
        }
        if (mIsHeadlessStopped) {
            // The key came up while a retry was pending, so nothing was recorded.
            endHeadless();
            return;
        }
        if (mHeadlessRecognizer == null) {
            mHeadlessRecognizer = SpeechRecognizer.createSpeechRecognizer(this, mHeadlessService);
            mHeadlessRecognizer.setRecognitionListener(new HeadlessRecognitionListener());
        }
        mIsHeadlessRetryAllowed = true;
        try {
            mHeadlessRecognizer.startListening(mHeadlessIntent);
        } catch (SecurityException e) {
            android.util.Log.i(PTT_TAG, "headless take refused: " + e);
            endHeadless();
        }
    }

    /**
     * Stops recording; the recorded audio is still transcribed.
     */
    private void stopHeadless() {
        if (!mIsHeadless || mIsHeadlessStopped) {
            return;
        }
        mIsHeadlessStopped = true;
        mIsHeadlessRetryAllowed = false;
        if (mHeadlessRecognizer == null) {
            // A retry is pending, nothing is recording.
            endHeadless();
        } else {
            mHeadlessRecognizer.stopListening();
        }
    }

    private void endHeadless() {
        mIsHeadless = false;
        mIsHeadlessRetryAllowed = false;
        mHandler.removeCallbacks(mRetryHeadless);
    }

    private void destroyHeadlessRecognizer() {
        if (mHeadlessRecognizer != null) {
            mHeadlessRecognizer.destroy();
            mHeadlessRecognizer = null;
        }
    }

    /**
     * Types the text of a headless take into the focused field, the way the keyboard panel does.
     * If no field is focused (or it is a password field), puts the text on the clipboard instead.
     */
    private void deliverHeadless(List<String> results) {
        String text = getText(results);
        if (text.isEmpty()) {
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        if (getCurrentInputStarted() && ic != null && editorInfo != null
                && editorInfo.inputType != InputType.TYPE_NULL && !isPassword(editorInfo)) {
            ((InputConnectionCommandEditor) mCommandEditor).setInputConnection(ic);
            mRuleManager.setMatchers(mHeadlessLanguage, mHeadlessService, mHeadlessApp);
            // Also resets the editor, which reads the text before the cursor.
            mCommandEditor.setRewriters(
                    Utils.makeList(
                            Utils.genRewriters(mPrefs, mRes, null, mRuleManager.getCommandMatcher())));
            commitResults(results);
            android.util.Log.i(PTT_TAG, "headless take typed " + text.length() + " chars");
        } else {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.labelApp), text));
            android.util.Log.i(PTT_TAG, "headless take copied " + text.length() + " chars");
        }
    }

    private static boolean isPassword(EditorInfo editorInfo) {
        if ((editorInfo.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        int variation = editorInfo.inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    private class HeadlessRecognitionListener implements RecognitionListener {

        @Override
        public void onReadyForSpeech(Bundle params) {
            mIsHeadlessRetryAllowed = false;
        }

        @Override
        public void onBeginningOfSpeech() {
            mIsHeadlessRetryAllowed = false;
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            mIsHeadlessRetryAllowed = false;
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int errorCode) {
            android.util.Log.i(PTT_TAG, "headless take error " + errorCode);
            boolean isClientOrBusy = errorCode == SpeechRecognizer.ERROR_CLIENT
                    || errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
            if (isClientOrBusy) {
                // Do not reuse a recognizer whose connection might be unusable.
                destroyHeadlessRecognizer();
            }
            if (isClientOrBusy && mIsHeadless && mIsHeadlessRetryAllowed
                    && mHeadlessRetryCount < HEADLESS_RETRY_MAX
                    && System.currentTimeMillis() - mHeadlessStartTime <= HEADLESS_RETRY_WINDOW_MS) {
                mIsHeadlessRetryAllowed = false;
                mHeadlessRetryCount++;
                android.util.Log.i(PTT_TAG, "headless take retry " + mHeadlessRetryCount);
                mHandler.postDelayed(mRetryHeadless, HEADLESS_RETRY_DELAY_MS);
                return;
            }
            endHeadless();
        }

        @Override
        public void onPartialResults(Bundle bundle) {
            mIsHeadlessRetryAllowed = false;
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }

        @Override
        public void onResults(Bundle bundle) {
            ArrayList<String> results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (results != null && !results.isEmpty()) {
                deliverHeadless(results);
            } else {
                android.util.Log.i(PTT_TAG, "headless take: no results");
            }
            endHeadless();
        }
    }

    /**
     * This is called at configuration change. We just kill the running session.
     * TODO: better handle configuration changes
     */
    @Override
    public void onInitializeInterface() {
        Log.i("onInitializeInterface");
        closeSession();
    }

    @Override
    public View onCreateInputView() {
        Log.i("onCreateInputView");
        //ViewGroup view = (ViewGroup) findViewById(android.R.id.content);
        ViewGroup view = (ViewGroup) getMyWindow().getDecorView().getRootView();
        mInputView = (SpeechInputView) getLayoutInflater().inflate(R.layout.voice_ime_view, view, false);
        return mInputView;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        // Returning true only if Wear
        return getResources().getBoolean(R.bool.isWatch);
    }

    /**
     * We check the type of editor control and if we probably cannot handle it (e.g. dates)
     * or do not want to (e.g. passwords) then we hand the editing over to another keyboard.
     * TODO: handle inputType = 0
     */
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        String type = "UNKNOWN";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mFlagPersonalizedLearning = (attribute.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        }

        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
                type = "NUMBER";
                break;
            case InputType.TYPE_CLASS_DATETIME:
                type = "DATETIME";
                switchToLastIme();
                break;
            case InputType.TYPE_CLASS_PHONE:
                type = "PHONE";
                break;
            case InputType.TYPE_CLASS_TEXT:
                int variation = attribute.inputType & InputType.TYPE_MASK_VARIATION;
                type = "TEXT/";
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                    // We refuse to recognize passwords for privacy reasons.
                    type += "PASSWORD || VISIBLE_PASSWORD";
                    switchToLastIme();
                } else if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                    type += "EMAIL_ADDRESS";
                } else if (variation == InputType.TYPE_TEXT_VARIATION_URI) {
                    // URI bar of Chrome and Firefox, can also handle search queries, thus supported
                    type += "URI";
                } else if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
                    // List filtering? Used in the Dialer search bar, thus supported
                    type += "FILTER";
                } else {
                    type += variation;
                }

                // This is used in the standard search bar (e.g. in Google Play).
                if ((attribute.inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                    type += "FLAG_AUTO_COMPLETE";
                }
                break;

            default:
        }
        Log.i("onStartInput: " + type + ", " + attribute.inputType + ", " + attribute.imeOptions + ", " + restarting + ", learning: " + mFlagPersonalizedLearning);
    }

    /**
     * Note that when editing a HTML page, then switching between form fields might fail to call
     * this method with restarting=false, we thus always update the editor info (incl. inputType).
     */
    @Override
    public void onStartInputView(EditorInfo editorInfo, boolean restarting) {
        super.onStartInputView(editorInfo, restarting);
        Log.i("onStartInputView: " + editorInfo.inputType + "/" + editorInfo.imeOptions + "/" + restarting);

        InputConnection ic = getCurrentInputConnection();
        // InputConnectionCommandEditor cannot be called with a null InputConnection.
        // We do not expect this to happen, but Google Play crash reports show that it does.
        // TODO: review, e.g. move to after restarting-check
        if (ic == null) {
            toast(R.string.errorFailedGetCurrentInputConnection);
            //switchToLastIme();
            return;
        }
        ((InputConnectionCommandEditor) mCommandEditor).setInputConnection(ic);

        // TODO: quick hack to add app to the matcher, not sure if we can access the class name of the app
        // TODO: use getPackageName() or editorInfo.packageName, but not both
        String packageName = editorInfo.packageName;
        ComponentName app = new ComponentName(packageName, packageName);

        mInputView.init(
                R.array.keysIme,
                new CallerInfo(makeExtras(), editorInfo, getPackageName()),
                true,
                app
        );

        // TODO: update this less often (in onStart)
        closeSession();

        if (restarting) {
            startPendingPtt();
            return;
        }

        mInputView.setListener(getSpeechInputViewListener(getMyWindow(), app, mRuleManager), editorInfo);
        mShowPartialResults = PreferenceUtils.getPrefBoolean(mPrefs, mRes, R.string.keyImeShowPartialResults, R.bool.defaultImeShowPartialResults);

        // Launch recognition immediately (if set so)
        if (PreferenceUtils.getPrefBoolean(mPrefs, mRes, R.string.keyImeAutoStart, R.bool.defaultImeAutoStart)) {
            Log.i("Auto-starting");
            mInputView.start();
        }
        startPendingPtt();
    }

    private void startPendingPtt() {
        mIsInputViewStarted = true;
        if (mIsPttPending) {
            startPtt();
        }
    }

    /**
     * Called when the input view is being hidden from the user.
     * This will be called either prior to hiding the window,
     * or prior to switching to another target for editing.
     *
     * @param finishingInput If true, onFinishInput() will be called immediately after.
     */
    @Override
    public void onFinishInputView(boolean finishingInput) {
        // TODO: maybe do not call super
        super.onFinishInputView(finishingInput);
        Log.i("onFinishInputView: " + finishingInput);
        mIsInputViewStarted = false;
        if (!finishingInput) {
            closeSession();
        }
    }

    /**
     * Called to inform the input method that text input has finished in the last editor.
     * At this point there may be a call to onStartInput(EditorInfo, boolean) to perform input in a new editor,
     * or the input method may be left idle.
     * This method is not called when input restarts in the same editor.
     */
    @Override
    public void onFinishInput() {
        // TODO: maybe do not call super
        super.onFinishInput();
        Log.i("onFinishInput");
        mIsInputViewStarted = false;
        closeSession();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
        Log.i("onCurrentInputMethodSubtypeChanged: " + subtype + ": " + subtype.getExtraValue());
        closeSession();
    }

    private void toast(int res) {
        Toast.makeText(getApplicationContext(), res, Toast.LENGTH_LONG).show();
    }

    private void closeSession() {
        mIsPtt = false;
        if (mInputView != null) {
            mInputView.cancel();
        }
        Window window = getMyWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private IBinder getToken() {
        Window window = getMyWindow();
        if (window == null) {
            return null;
        }
        return window.getAttributes().token;
    }

    private Window getMyWindow() {
        final Dialog dialog = getWindow();
        if (dialog == null) {
            return null;
        }
        return dialog.getWindow();
    }

    /**
     * Switch to another IME by selecting it from the list of all active IMEs (isAskUser==true), or
     * by taking the next IME in the IME rotation (isAskUser==false).
     */
    private void switchIme(boolean isAskUser) {
        closeSession();
        if (isAskUser) {
            mInputMethodManager.showInputMethodPicker();
        } else {
            boolean result = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                result = switchToNextInputMethod(false);
            } else {
                try {
                    result = mInputMethodManager.switchToNextInputMethod(getToken(), false /* not onlyCurrentIme */);
                } catch (NoSuchMethodError e) {
                    Log.e("IME switch failed", e);
                }
            }
            if (!result) {
                toast(R.string.toastNoNextIme);
            }
        }
    }

    /**
     * Switch to the previous IME, either when the user tries to edit an unsupported field (e.g. password),
     * or when they explicitly want to be taken back to the previous IME e.g. in case of a one-shot
     * speech input.
     */
    private void switchToLastIme() {
        closeSession();
        boolean result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result = switchToPreviousInputMethod();
        } else {
            result = mInputMethodManager.switchToLastInputMethod(getToken());
        }
        if (!result) {
            toast(R.string.toastNoPreviousIme);
        }
    }

    private static String getText(@NonNull List<String> results) {
        if (results.size() > 0) {
            return results.get(0);
        }
        return "";
    }

    private static Bundle makeExtras() {
        Bundle extras = new Bundle();
        extras.putBoolean(Extras.EXTRA_UNLIMITED_DURATION, true);
        extras.putBoolean(Extras.EXTRA_DICTATION_MODE, true);
        return extras;
    }

    /**
     * Types the final result into the field, or runs it as a command, and updates the tables of
     * recent and frequent utterances.
     */
    private void commitResults(List<String> results) {
        String text = getText(results);
        CommandEditorResult editorResult = mCommandEditor.commitFinalResult(text);
        if (editorResult != null && mInputView != null && editorResult.isCommand()) {
            mInputView.showMessage(editorResult.getRewrite().ppCommand(), editorResult.isSuccess());
        }
        if (editorResult != null && mFlagPersonalizedLearning) {
            boolean isSelected = false;
            Rewrites rewritesRec = new Rewrites(mPrefs, mRes, REWRITES_NAME_RECENT);
            if (rewritesRec.isSelected()) {
                isSelected = true;
                UtteranceRewriter ur = mRuleManager.addRecent(editorResult, rewritesRec.getRewrites());
                PreferenceUtils.putPrefMapEntry(mPrefs, mRes, R.string.keyRewritesMap, REWRITES_NAME_RECENT, ur.toTsv());
            }
            Rewrites rewritesFreq = new Rewrites(mPrefs, mRes, REWRITES_NAME_FREQUENT);
            if (rewritesFreq.isSelected()) {
                isSelected = true;
                UtteranceRewriter ur = mRuleManager.addFrequent(editorResult, rewritesFreq.getRewrites());
                PreferenceUtils.putPrefMapEntry(mPrefs, mRes, R.string.keyRewritesMap, REWRITES_NAME_FREQUENT, ur.toTsv());
            }
            // Update rewriters because the tables have changed
            if (isSelected) {
                mCommandEditor.setRewriters(
                        Utils.makeList(
                                Utils.genRewriters(mPrefs, mRes, null, mRuleManager.getCommandMatcher())));
            }
        }
    }

    private SpeechInputView.SpeechInputViewListener getSpeechInputViewListener(final Window window, final ComponentName app, RuleManager ruleManager) {
        return new AbstractSpeechInputViewListener() {

            final ComponentName mApp = app;
            final RuleManager mRuleManager = ruleManager;

            private void runOp(Op op) {
                mCommandEditor.runOp(op, false);
            }

            private void setKeepScreenOn(boolean b) {
                if (window != null) {
                    if (b) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                }
            }

            @Override
            public void onComboChange(String language, ComponentName service) {
                mRuleManager.setMatchers(language, service, mApp);
                // TODO: name of the rewrites table configurable
                mCommandEditor.setRewriters(
                        Utils.makeList(
                                Utils.genRewriters(mPrefs, mRes, null, mRuleManager.getCommandMatcher())));
            }

            @Override
            public void onPartialResult(List<String> results, boolean isSemiFinal) {
                if (isSemiFinal) {
                    commitResults(results);
                } else {
                    if (mShowPartialResults) {
                        mCommandEditor.commitPartialResult(getText(results));
                    }
                }
            }

            @Override
            public void onFinalResult(List<String> results, Bundle bundle) {
                commitResults(results);
                setKeepScreenOn(false);
            }

            @Override
            public void onCommand(String text) {
                Op op = mCommandEditor.getOpOrNull(text, false);
                if (op == null) {
                    // Mic button swipe did not match any rule, so we show the swipe crossed out.
                    // TODO: maybe just clear the status bar
                    if (mInputView != null) {
                        mInputView.showMessage(text, false);
                    }
                } else {
                    boolean success = mCommandEditor.runOp(op);
                    if (mInputView != null) {
                        // TODO: show executed command or replacement text, not op.toString()
                        mInputView.showMessage(op.toString(), success);
                    }
                    setKeepScreenOn(false);
                }
            }

            @Override
            public void onSwitchIme(boolean isAskUser) {
                switchIme(isAskUser);
            }

            @Override
            public void onSwitchToLastIme() {
                switchToLastIme();
            }

            @Override
            public void onAction(int editorAction, boolean hide) {
                if (hide) {
                    closeSession();
                }
                if (editorAction > 0) {
                    runOp(mCommandEditor.imeAction(editorAction));
                }
                if (hide) {
                    requestHideSelf(0);
                }
            }

            @Override
            public void onDeleteLeftChar() {
                // TODO: indicate somehow (e.g. vibration, different background color) that the Op failed
                runOp(mCommandEditor.deleteChars(-1));
                // TODO: might be better, i.e. able to delete non-text (checkboxes), but not undoable
                //runOp(mCommandEditor.keyCode(KeyEvent.KEYCODE_DEL));
            }

            @Override
            public void onDeleteLastWord() {
                runOp(mCommandEditor.deleteLeftWord());
            }

            @Override
            public void onAddNewline() {
                runOp(mCommandEditor.replaceSel("\n"));
            }

            @Override
            public void goUp() {
                runOp(mCommandEditor.keyUp());
            }

            @Override
            public void goDown() {
                runOp(mCommandEditor.keyDown());
            }

            @Override
            public void moveRel(int numOfChars) {
                runOp(mCommandEditor.moveRel(numOfChars));
            }

            @Override
            public void moveRelSel(int numOfChars, int type) {
                runOp(mCommandEditor.moveRelSel(numOfChars, type));
            }

            @Override
            public void onExtendSel(String regex) {
                runOp(mCommandEditor.selectRe(regex, false));
            }

            @Override
            public void onAddSpace() {
                runOp(mCommandEditor.replaceSel(" "));
            }

            @Override
            public void onSelectAll() {
                // TODO: show ContextMenu
                runOp(mCommandEditor.selectAll());
            }

            @Override
            public void onReset() {
                // TODO: hide ContextMenu (if visible)
                runOp(mCommandEditor.moveRel(0));
            }

            @Override
            public void onStartListening() {
                Log.i("IME: onStartListening");
                mCommandEditor.reset();
                setKeepScreenOn(true);
            }

            @Override
            public void onStopListening() {
                Log.i("IME: onStopListening");
                setKeepScreenOn(false);
            }

            // TODO: add onCancel()

            @Override
            public void onError(int errorCode) {
                setKeepScreenOn(false);
                Log.i("IME: onError: " + errorCode);
                if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Intent intent = new Intent(SpeechInputMethodService.this, PermissionsRequesterActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    SpeechInputMethodService.this.startActivity(intent);
                }
            }

            @Override
            public void onEndOfSession() {
                // A push-to-talk take ends by returning to the previous IME.
                if (mIsPtt) {
                    mIsPtt = false;
                    switchToLastIme();
                }
            }
        };
    }
}
