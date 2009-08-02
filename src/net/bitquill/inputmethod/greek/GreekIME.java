/*
 * Copyright (C) 2008-2009 Google Inc.
 * Copyright (C) 2009 Spiros Papadimitriou <spapadim@cs.cmu.edu>
 * 
 * This file is part of GreekIME, a Greek input method for Android 1.5.
 * Code is directly derived from Android's LatinIME, and also incorporates
 * some ideas from PinyinIME.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.bitquill.inputmethod.greek;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.media.AudioManager;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.text.AutoText;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class GreekIME extends InputMethodService 
        implements KeyboardView.OnKeyboardActionListener {
    private static final String TAG = "GreekIME"; 
    private static final boolean DEBUG = false;
    private static final boolean TRACE = false;
    
    private static final String PREF_VIBRATE_ON = "vibrate_on";
    private static final String PREF_SOUND_ON = "sound_on";
    private static final String PREF_AUTO_CAP = "auto_cap";
    private static final String PREF_QUICK_FIXES = "quick_fixes";
    private static final String PREF_SHOW_SUGGESTIONS = "show_suggestions";
    private static final String PREF_AUTO_COMPLETE = "auto_complete";
    private static final String PREF_SMS_7BIT = "sms_7bit";
    private static final String PREF_AUTO_FINAL_SIGMA = "auto_final_sigma";
    
    private static final int MSG_UPDATE_SUGGESTIONS = 0;

    // How many continuous deletes at which to start deleting at a higher speed.
    private static final int DELETE_ACCELERATE_AT = 20;
    // Key events coming any faster than this are long-presses.
    private static final int QUICK_PRESS = 200; 
    
    private static final int KEYCODE_ENTER = 10;
    private static final int KEYCODE_SPACE = ' ';
    private static final int KEYCODE_ACUTE = '\u0384';
    private static final int KEYCODE_DIAERESIS = '\u00a8';
    private static final int KEYCODE_ACUTE_DIAERESIS = '\u0385';
    private static final int KEYCODE_LC_SIGMA = '\u03c3';
    private static final int KEYCODE_LC_SIGMA_FINAL = '\u03c2';
    
    // Contextual menu positions
    private static final int POS_SETTINGS = 0;
    private static final int POS_METHOD = 1;
    
    private SoftKeyboardView mInputView;
    private CandidateViewContainer mCandidateViewContainer;
    private CandidateView mCandidateView;
    private Suggest mSuggest;
    private CompletionInfo[] mCompletions;
    
    private AlertDialog mOptionsDialog;
    
    KeyboardSwitcher mKeyboardSwitcher;
    HardKeyboardState mHardKeyboard;

    private UserDictionary mUserDictionary;

    private String mLocale;
    
    private StringBuilder mComposing = new StringBuilder();
    private WordComposer mWord = new WordComposer();
    private int mCommittedLength;
    private boolean mPredicting;
    private CharSequence mBestWord;
    private boolean mPredictionOn;
    private boolean mCompletionOn;
    private boolean mAutoSpace;
    private boolean mAutoCorrectOn;
    private boolean mCapsLock;
    private boolean mVibrateOn;
    private boolean mSMS7bitMode;
    private boolean mSoundOn;
    private boolean mQuickFixes;
    private boolean mShowSuggestions;
    private boolean mAutoComplete;
    private int     mCorrectionMode;
    private boolean mAutoCap;
    private boolean mAutoFinalSigma;
    // Indicates whether the suggestion strip is to be on in landscape
    private boolean mJustAccepted;
    private CharSequence mJustRevertedSeparator;
    private int mDeleteCount;
    private long mLastKeyTime;
    
    private static final int ACCENT_STATE_NONE = 0;
    private static final int ACCENT_STATE_ACUTE = 1;
    private static final int ACCENT_STATE_DIAERESIS = 2;
    private static final int ACCENT_STATE_BOTH = 3; 
    
    private boolean mIMMode;
    private int mAccentShiftState;
    
    private Vibrator mVibrator;
    private long mVibrateDuration;

    private AudioManager mAudioManager;
    private final float FX_VOLUME = 1.0f;
    private boolean mSilentMode;

    private String mWordSeparators;
    private String mSentenceSeparators;
    
    private boolean mHasVisibleHardwareKeyboard;

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_SUGGESTIONS:
                    updateSuggestions();
                    break;
            }
        }
    };
    
    @Override public void onCreate() {
        super.onCreate();
        //setStatusIcon(R.drawable.ime_qwerty);
        mKeyboardSwitcher = new KeyboardSwitcher(this);
        mHardKeyboard = new HardKeyboardState(this);

        initSuggest(getResources().getConfiguration().locale.toString());
        
        mVibrateDuration = getResources().getInteger(R.integer.vibrate_duration_ms);
        
        // register to receive ringer mode changes for silent mode
        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, filter);
        
        if (TRACE) Debug.startMethodTracing("greekime");
    }    

    private void initSuggest(String locale) {
        mLocale = locale;
        mSuggest = new Suggest(this);
        mSuggest.setCorrectionMode(mCorrectionMode);
        mUserDictionary = new UserDictionary(this);
        mSuggest.setUserDictionary(mUserDictionary);
        mWordSeparators = getResources().getString(R.string.word_separators);
        mSentenceSeparators = getResources().getString(R.string.sentence_separators);
    }

    
    @Override
    public void onDestroy() {
        mUserDictionary.close();
        unregisterReceiver(mReceiver);
        if (TRACE) Debug.stopMethodTracing();
        super.onDestroy();
    }
    
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);

        if (!TextUtils.equals(config.locale.toString(), mLocale)) {
            initSuggest(config.locale.toString());
        }

        mHardKeyboard.clearAllMetaStates();
        mHasVisibleHardwareKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS
            && config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
	}

    @Override
    public View onCreateInputView() {
        Log.i(TAG, "onCreateInputView");
        mInputView = (SoftKeyboardView) getLayoutInflater().inflate(
                R.layout.input, null);
        mKeyboardSwitcher.setInputView(mInputView);
        mKeyboardSwitcher.makeKeyboards();
        mInputView.setOnKeyboardActionListener(this);
        mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_TEXT, KeyboardSwitcher.LANGUAGE_EN, 0);
        if (mInputView == null)  Log.e(TAG, "Input view is null!");
        return mInputView;
    }

    @Override
    public View onCreateCandidatesView() {
        mKeyboardSwitcher.makeKeyboards();  // XXX - check patch
        mCandidateViewContainer = (CandidateViewContainer) getLayoutInflater().inflate(
                R.layout.candidates, null);
        mCandidateViewContainer.initViews();
        mCandidateView = (CandidateView) mCandidateViewContainer.findViewById(R.id.candidates);
        mCandidateView.setService(this);
        setCandidatesViewShown(true);
        return mCandidateViewContainer;
    }
    
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        showStatusIcon(mKeyboardSwitcher.getLanguageIcon());
    	loadSettings();
    	mHardKeyboard.clearAllMetaStates();
    	mIMMode = ((attribute.inputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT)
    		&& ((attribute.inputType & EditorInfo.TYPE_MASK_VARIATION) == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
		//super.onStartInput(attribute, restarting);  // XXX - check
	}

	@Override 
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        // In landscape mode, this method gets called without the input view being created.
        if (mInputView == null) {
            return;
        }

        mKeyboardSwitcher.makeKeyboards();
        mKeyboardSwitcher.set7bitMode(mSMS7bitMode);
        
        TextEntryState.newSession(this);
        
        mPredictionOn = false;
        mCompletionOn = false;
        mCompletions = null;
        mCapsLock = false;
        mAccentShiftState = ACCENT_STATE_NONE;
        switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
            case EditorInfo.TYPE_CLASS_NUMBER:
            case EditorInfo.TYPE_CLASS_DATETIME:
                mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_TEXT,
                		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                mKeyboardSwitcher.toggleSymbols();
                break;
            case EditorInfo.TYPE_CLASS_PHONE:
                mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_PHONE,
                        KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                break;
            case EditorInfo.TYPE_CLASS_TEXT:
                mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_TEXT,
                        KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                //startPrediction();
                mPredictionOn = true;
                // Make sure that passwords are not displayed in candidate view
                int variation = attribute.inputType &  EditorInfo.TYPE_MASK_VARIATION;
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ) {
                    mPredictionOn = false;
                }
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME) {
                    mAutoSpace = false;
                } else {
                    mAutoSpace = true;
                }
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
                    mPredictionOn = false;
                    mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_EMAIL,
                    		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI) {
                    mPredictionOn = false;
                    mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_URL,
                    		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
                    mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_IM,
                    		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_FILTER) {
                    mPredictionOn = false;
                }

                if ((attribute.inputType&EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                    mPredictionOn = false;
                    mCompletionOn = true && isFullscreenMode();
                }

                updateSoftShiftKeyState(attribute);
                break;
            default:
                mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_TEXT,
                		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                updateSoftShiftKeyState(attribute);
        }
        mInputView.closing();
        mComposing.setLength(0);
        mDeleteCount = 0;
        mPredicting = false;
        setCandidatesViewShown(false);
        if (mCandidateView != null) mCandidateView.setSuggestions(null, false, false, false);
        loadSettings();  // XXX check patch
        mInputView.setProximityCorrectionEnabled(true);
        if (mSuggest != null) {
            mSuggest.setCorrectionMode(mCorrectionMode);
        }
        mPredictionOn = mPredictionOn && mCorrectionMode > 0;
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();

        if (mInputView != null) {
            mInputView.closing();
        }
        hideStatusIcon();
    }

    @Override
    public boolean onEvaluateInputViewShown() { // XXX
        // This is really a kludge, but it's the only place I found to clear
        // the hardware keyboard meta states when the editor focus is lost 
        // and then regained, so that the tracked meta states match
        // the cursor caret indicator.
        mHardKeyboard.clearAllMetaStates();
        return super.onEvaluateInputViewShown();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        // If the current selection in the text view changes, we should
        // clear whatever candidate text we have.
        if (mComposing.length() > 0 && mPredicting && (newSelStart != candidatesEnd
                || newSelEnd != candidatesEnd)) {
            mComposing.setLength(0);
            mPredicting = false;
            updateSuggestions();
            TextEntryState.reset();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        } else if (!mPredicting && !mJustAccepted
                && TextEntryState.getState() == TextEntryState.STATE_ACCEPTED_DEFAULT) {
            TextEntryState.reset();
        }
        mJustAccepted = false;
    }

    @Override
    public void hideWindow() {
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
        TextEntryState.endSession();
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] completions) {
        if (false) {
            Log.i(TAG, "Received completions:");
            for (int i=0; i<(completions != null ? completions.length : 0); i++) {
                Log.i(TAG, "  #" + i + ": " + completions[i]);
            }
        }
        if (mCompletionOn) {
            mCompletions = completions;
            if (completions == null) {
                mCandidateView.setSuggestions(null, false, false, false);
                return;
            }

            List<CharSequence> stringList = new ArrayList<CharSequence>();
            for (int i=0; i<(completions != null ? completions.length : 0); i++) {
                CompletionInfo ci = completions[i];
                if (ci != null) stringList.add(ci.getText());
            }
            //CharSequence typedWord = mWord.getTypedWord();
            mCandidateView.setSuggestions(stringList, true, true, true);
            mBestWord = null;
            setCandidatesViewShown(isCandidateStripVisible() || mCompletionOn);
        }
    }



    @Override
    public void setCandidatesViewShown(boolean shown) {
        // TODO: Remove this if we support candidates with hard keyboard
        if (onEvaluateInputViewShown()) {
            super.setCandidatesViewShown(shown);
        }
    }
    
    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0 && mInputView != null) {
                    if (mInputView.handleBack()) {
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                break;
        }
    	// SHIFT-SPACE is used to switch between Greek and English
        if (KeyEvent.KEYCODE_SPACE == keyCode && event.isShiftPressed()) {
        	changeKeyboardLanguage();
        	mHardKeyboard.updateMetaStateAfterKeypress(HardKeyboardState.META_SHIFT, true);
        	return true;
        }
        // Do translation into Greek if necessary
        if (KeyboardSwitcher.LANGUAGE_EL == mKeyboardSwitcher.getKeyboardLanguage()
        		&& handleHardKeyGreek(keyCode, event)) {
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Enable shift key and DPAD to do selections
                if (mInputView != null && mInputView.isShown() && mInputView.isShifted()) {
                    event = new KeyEvent(event.getDownTime(), event.getEventTime(), 
                            event.getAction(), event.getKeyCode(), event.getRepeatCount(),
                            event.getDeviceId(), event.getScanCode(),
                            KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON);
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.sendKeyEvent(event);
                    return true;
                }
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void commitTyped(InputConnection inputConnection) {
        if (mPredicting) {
            mPredicting = false;
            if (mComposing.length() > 0) {
                if (inputConnection != null) {
                    inputConnection.commitText(mComposing, 1);
                }
                mCommittedLength = mComposing.length();
                TextEntryState.acceptedTyped(mComposing);
            }
            updateSuggestions();
        }
    }
    
    private int getCursorCapsMode () {
		int cursorCaps = 0;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
                cursorCaps = ic.getCursorCapsMode(ei.inputType);
            }
        }
        return cursorCaps;
    }

    // XXX - original version, from LatinIME (see questions below)
//    private void updateSoftShiftKeyState(EditorInfo attr) {
//        InputConnection ic = getCurrentInputConnection();
//        if (attr != null && mInputView != null && mKeyboardSwitcher.isAlphabetMode()
//                && ic != null) {
//            int caps = 0;
//            EditorInfo ei = getCurrentInputEditorInfo();  // XXX - why not use attr ?
//            if (mAutoCap && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
//                caps = ic.getCursorCapsMode(attr.inputType);  // XXX - why use attr, not ei
//            }
//            mInputView.setShifted(mCapsLock || caps != 0);
//        }
//    }
    
    private void updateSoftShiftKeyState (EditorInfo attr) {
    	if (attr != null && mInputView != null && mKeyboardSwitcher.isAlphabetMode()) {
    		int caps = getCursorCapsMode();
    		mInputView.setShifted(mCapsLock || (mAutoCap && (caps != 0)));
    	}
    }
        
    private void swapPunctuationAndSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
        if (lastTwo != null && lastTwo.length() == 2
                && lastTwo.charAt(0) == KEYCODE_SPACE && isSentenceSeparator(lastTwo.charAt(1))) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(lastTwo.charAt(1) + " ", 1);
            ic.endBatchEdit();
            updateSoftShiftKeyState(getCurrentInputEditorInfo());
        }
    }
    
    private void finalizeSigma() {
    	if (!mAutoFinalSigma) {
    		return;
    	}
    	final InputConnection ic = getCurrentInputConnection();
    	CharSequence lastChar = ic.getTextBeforeCursor(1, 0);
    	if (lastChar != null && lastChar.length() == 1
    			&& lastChar.charAt(0) == KEYCODE_LC_SIGMA) {
    		ic.beginBatchEdit();
    		ic.deleteSurroundingText(1, 0);
    		ic.commitText("\u03c2", 1);
    		ic.endBatchEdit();
    	}
    }
    
    private void doubleSpace() {
        //if (!mAutoPunctuate) return;
        if (mCorrectionMode == Suggest.CORRECTION_NONE) return;
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && Character.isLetterOrDigit(lastThree.charAt(0))
                && lastThree.charAt(1) == KEYCODE_SPACE && lastThree.charAt(2) == KEYCODE_SPACE) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(". ", 1);
            ic.endBatchEdit();
            updateSoftShiftKeyState(getCurrentInputEditorInfo());
        }
    }
    
    public boolean addWordToDictionary(String word) {
        mUserDictionary.addWord(word, 128);
        return true;
    }

    private boolean isAlphabet(int code) {
        if (Character.isLetter(code)) {
            return true;
        } else {
            return false;
        }
    }
    
    // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes) {
        long when = SystemClock.uptimeMillis();
        if (primaryCode != Keyboard.KEYCODE_DELETE || 
                when > mLastKeyTime + QUICK_PRESS) {
            mDeleteCount = 0;
        }
        mLastKeyTime = when;
        switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                handleBackspace();
                mDeleteCount++;
                break;
            case Keyboard.KEYCODE_SHIFT:
                handleShift();
                // Don't reset accent shift here
                break;
            case Keyboard.KEYCODE_CANCEL:
                if (mOptionsDialog == null || !mOptionsDialog.isShowing()) {
                    handleClose();
                }
                break;
            case SoftKeyboard.KEYCODE_OPTIONS:
                showOptionsMenu();
                accentStateClear();
                break;
            case SoftKeyboard.KEYCODE_SHIFT_LONGPRESS:
                if (mCapsLock) {
                    handleShift();
                } else {
                    toggleCapsLock();
                }
                // Don't reset accent shift here
                break;
            case SoftKeyboard.KEYCODE_LANGUAGE_CHANGE:
                changeKeyboardLanguage();
                accentStateClear();
                break;
            case Keyboard.KEYCODE_MODE_CHANGE:
            	changeKeyboardSymbols();
            	accentStateClear();
            	break;
            case SoftKeyboard.KEYCODE_ACCENT:
            	accentStateShift();
            	break;
            default:
                if (isWordSeparator(primaryCode)) {
                    handleSeparator(primaryCode);
                } else {
                    handleCharacter(primaryCode, keyCodes);
                }
                // Cancel the just reverted state
                mJustRevertedSeparator = null;
        }
    }
    
    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.beginBatchEdit();
        if (mPredicting) {
            commitTyped(ic);
        }
        ic.commitText(text, 1);
        ic.endBatchEdit();
        updateSoftShiftKeyState(getCurrentInputEditorInfo());
        mJustRevertedSeparator = null;
    }

    private void handleBackspace() {
        boolean deleteChar = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (mPredicting) {
            final int length = mComposing.length();
            if (length > 0) {
                mComposing.delete(length - 1, length);
                mWord.deleteLast();
                ic.setComposingText(mComposing, 1);
                if (mComposing.length() == 0) {
                    mPredicting = false;
                }
                postUpdateSuggestions();
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        } else {
            deleteChar = true;
        }
        updateSoftShiftKeyState(getCurrentInputEditorInfo());
        TextEntryState.backspace();
        if (TextEntryState.getState() == TextEntryState.STATE_UNDO_COMMIT) {
            revertLastWord(deleteChar);
            return;
        } else if (deleteChar) {
            if (mAccentShiftState != ACCENT_STATE_NONE) {
                accentStateClear();
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                if (mDeleteCount > DELETE_ACCELERATE_AT) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                }
            }
        }
        mJustRevertedSeparator = null;
    }
    
    private boolean isAccentShifted () {
    	return (mAccentShiftState != ACCENT_STATE_NONE)
    	    && (mKeyboardSwitcher.getKeyboardLanguage() == KeyboardSwitcher.LANGUAGE_EL);
    }
    
    private void handleShift() {
        Keyboard currentKeyboard = mInputView.getKeyboard();
        if (mKeyboardSwitcher.isAlphabetMode()) {
            // Alphabet keyboard
            checkToggleCapsLock();
            mInputView.setShifted(mCapsLock || !mInputView.isShifted());
        } else {
            mKeyboardSwitcher.toggleSymbolShift();
        }
    }
    
    private void handleCharacter(int primaryCode, int[] keyCodes) {
        // XXX check patch
        if (isAlphabet(primaryCode) && isPredictionOn() && !isCursorTouchingWord()) {
            if (!mPredicting) {
                mPredicting = true;
                mComposing.setLength(0);
                mWord.reset();
            }
        }

        if (mInputView.isShifted()) {
            primaryCode = Character.toUpperCase(primaryCode);
        }
        if (isAccentShifted()) {
        	primaryCode = addAccent(primaryCode, mAccentShiftState);
        }
        accentStateClear();
        
        // XXX check patch
        if (mPredicting) {
            if (mInputView.isShifted() && mComposing.length() == 0) {
                mWord.setCapitalized(true);
            }
            mComposing.append((char) primaryCode);
            mWord.add(primaryCode, keyCodes);
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.setComposingText(mComposing, 1);
            }
            postUpdateSuggestions();
        } else {
            sendKeyChar((char)primaryCode);
        }

        updateSoftShiftKeyState(getCurrentInputEditorInfo());
        measureCps();
        TextEntryState.typedCharacter((char) primaryCode, isWordSeparator(primaryCode));
    }
    
    /**
     * 
     */
    private void accentStateShift () {
    	InputConnection ic = getCurrentInputConnection();
    	char composingChar = 0;
    	int prevAccentShiftState = mAccentShiftState;
    	switch (mAccentShiftState) {
    	case ACCENT_STATE_NONE:
    		mAccentShiftState = ACCENT_STATE_ACUTE;
    		composingChar = '\u0384';
    		break;
    	case ACCENT_STATE_ACUTE:
    		mAccentShiftState = ACCENT_STATE_DIAERESIS;
    		composingChar = '\u00a8';
    		break;
    	case ACCENT_STATE_DIAERESIS:
    		mAccentShiftState = ACCENT_STATE_BOTH;
    		composingChar = '\u0385';
    		break;
    	default:
    		mAccentShiftState = ACCENT_STATE_NONE;
    		composingChar = 0;
    		break;
    	}

    	if (mPredicting) {
    	    StringBuilder composing = mComposing;
    	    int length = composing.length();
    	    if (prevAccentShiftState != ACCENT_STATE_NONE && length > 0) {
    	        composing.deleteCharAt(length-1);
    	    }
    	    if (composingChar != 0) {
    	        composing.append(composingChar);
    	    }
    	    ic.setComposingText(composing, 1);
    	} else {
    	    //ic.setComposingText(composingChar, 1);  // FIXME TODO
    	}
    }
    
    private void accentStateClear () {
    	InputConnection ic = getCurrentInputConnection();
    	if (mPredicting && mAccentShiftState != ACCENT_STATE_NONE) {
    	    int length = mComposing.length();
    	    if (length > 0) {
    	        mComposing.deleteCharAt(length-1);
    	        ic.setComposingText(mComposing, 1);
    	    }
    	}
    	mAccentShiftState = ACCENT_STATE_NONE;
    	//ic.setComposingText("", 1);  // FIXME TODO
    }
    
    /**
     * Add an acute accent to a Greek character, if applicable.
     * @param code  Unicode character code
     * @return      Corresponding accented character; returns code itself if character cannot be accented
     */
    private int addAccent (int code, int accentState) {
    	switch (accentState) {
    	case ACCENT_STATE_NONE:
    		return code;
    	case ACCENT_STATE_ACUTE:
    		return sAcuteAccentTable.get(code, code);
    	case ACCENT_STATE_DIAERESIS:
    		return sDiaeresisAccentTable.get(code, code);
    	case ACCENT_STATE_BOTH:
    		return sBothAccentTable.get(code, code);
    	default:
    		return code;
    	}
    }
    
    /**
     * Convert a hardware key code to the corresponding Greek character code (in Unicode)
     * @param keyCode   Hardware key code
     * @param upperCase Whether character should be upper case or not
     * @param accented  Whether character should have an acute accent or not
     * @return          Corresponding character code, or -1 if none exists (no translation)
     */
    private int keyCodeToChar (int keyCode, boolean upperCase, int accentState) {
        int code;
        if (mIMMode && mSMS7bitMode) {
        	code = sSMSKeyCodeTable.get(keyCode, -1);
        } else {
        	code = sKeyCodeTable.get(keyCode, -1);
        } 
        if (accentState != ACCENT_STATE_NONE) {
        	code = addAccent(code, accentState);
        }
        if (upperCase) {
        	code = Character.toUpperCase(code);
        }
        return code;
    }
    
    /**
     * Handle a hardware key press; should only be called when in greek language mode.
     * @param keyCode  Hardware keycode
     * @param event    Key event
     * @return         True if the event was consumed, false if it should be propagated
     */
    private boolean handleHardKeyGreek(int keyCode, KeyEvent event) {
    	if (KeyEvent.KEYCODE_SHIFT_LEFT == keyCode
    			|| KeyEvent.KEYCODE_SHIFT_RIGHT == keyCode) {
    		// FIXME - Cannot figure out how to access hardware keyboard caps state,
    		// so faking it, like PinyinIME does
    		mHardKeyboard.shiftMetaState(HardKeyboardState.META_SHIFT);
    		return false;
    	} else if (KeyEvent.KEYCODE_ALT_LEFT == keyCode
    			|| KeyEvent.KEYCODE_ALT_RIGHT == keyCode) {
    		mHardKeyboard.shiftMetaState(HardKeyboardState.META_ALT);
    		return false;
    	} else if (KeyEvent.KEYCODE_SYM == keyCode) {
    		// TODO
    		return true;
    	} else if (KeyEvent.KEYCODE_DEL == keyCode) {
    		boolean consume;
			if (mAccentShiftState != ACCENT_STATE_NONE) {
				accentStateClear();
				consume = true;
			} else {
				consume = false;
			}
			mHardKeyboard.updateAllMetaStatesAfterKeypress(consume);
			return consume;
    	} else {
    		if (mHardKeyboard.isMetaOn(HardKeyboardState.META_ALT)) {
    			mHardKeyboard.updateMetaStateAfterKeypress(HardKeyboardState.META_ALT, false);
    			return false;
    		} else {
    			boolean caps = event.isShiftPressed() || mHardKeyboard.isMetaOn(HardKeyboardState.META_SHIFT)
    				|| getCursorCapsMode() != 0;
				int greekCode = keyCodeToChar (keyCode, caps, mAccentShiftState);
				if (isWordSeparator(greekCode)) {
					finalizeSigma();
				}
				boolean consume;
				if (greekCode >= 0) {
					sendKeyChar((char)greekCode);
					accentStateClear();
					consume = true;
				} else if (greekCode == SoftKeyboard.KEYCODE_ACCENT) {
					accentStateShift();
					consume = true;
				} else {
					accentStateClear(); // XXX - check?
					consume = false;
				}
				mHardKeyboard.updateMetaStateAfterKeypress(HardKeyboardState.META_SHIFT, consume);
				return consume;
			}
    	}
    }

    private void handleSeparator(int primaryCode) {
        boolean pickedDefault = false;
        // Handle separator
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        if (primaryCode == KEYCODE_SPACE && isAccentShifted()) {  // XXX - fixme, don't hardcode space code?
        	primaryCode = addAccent(primaryCode, mAccentShiftState);
        }
        finalizeSigma();

        // XXX check patch
        if (mPredicting) {
            // In certain languages where single quote is a separator, it's better
            // not to auto correct, but accept the typed word. For instance,
            // in Italian dov' should not be expanded to dove' because the elision
            // requires the last vowel to be removed.
            if (mAutoCorrectOn && primaryCode != '\'' &&
                    (mJustRevertedSeparator == null
                            || mJustRevertedSeparator.length() == 0
                            || mJustRevertedSeparator.charAt(0) != primaryCode)) {
                pickDefaultSuggestion();
                pickedDefault = true;
            } else {
                commitTyped(ic);
            }
        }

        sendKeyChar((char)primaryCode);
        TextEntryState.typedCharacter((char) primaryCode, true);
        if (TextEntryState.getState() == TextEntryState.STATE_PUNCTUATION_AFTER_ACCEPTED 
                && primaryCode != KEYCODE_ENTER) {
            swapPunctuationAndSpace();
        } else if (isPredictionOn() && primaryCode == ' ') {
        //else if (TextEntryState.STATE_SPACE_AFTER_ACCEPTED) {
            doubleSpace();
        }
        if (pickedDefault && mBestWord != null) {
            TextEntryState.acceptedDefault(mWord.getTypedWord(), mBestWord);
        }
        updateSoftShiftKeyState(getCurrentInputEditorInfo());
        accentStateClear();
        if (ic != null) {
            ic.endBatchEdit();
        }
    }
    
    private void handleClose() {
        commitTyped(getCurrentInputConnection());
        requestHideSelf(0);
        mInputView.closing();
        TextEntryState.endSession();
    }

    private void checkToggleCapsLock() {
        if (mInputView.getKeyboard().isShifted()) {
            toggleCapsLock();
        }
    }
    
    private void toggleCapsLock() {
        mCapsLock = !mCapsLock;
        if (mKeyboardSwitcher.isAlphabetMode()) {
            ((SoftKeyboard) mInputView.getKeyboard()).setShiftLocked(mCapsLock);
        }
    }

    private void postUpdateSuggestions() {
        mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UPDATE_SUGGESTIONS), 100);
    }

    private boolean isPredictionOn() {
        boolean predictionOn = mPredictionOn;
        //if (isFullscreenMode()) predictionOn &= mPredictionLandscape;
        return predictionOn;
    }

    private boolean isCandidateStripVisible() {
        return isPredictionOn() && mShowSuggestions;
    }

    private void updateSuggestions() {
        // Check if we have a suggestion engine attached.
        if (mSuggest == null || !isPredictionOn()) {
            return;
        }

        if (!mPredicting) {
            mCandidateView.setSuggestions(null, false, false, false);
            return;
        }

        List<CharSequence> stringList = mSuggest.getSuggestions(mInputView, mWord, false);
        boolean correctionAvailable = mSuggest.hasMinimalCorrection();
        //|| mCorrectionMode == mSuggest.CORRECTION_FULL;
        CharSequence typedWord = mWord.getTypedWord();
        // If we're in basic correct
        boolean typedWordValid = mSuggest.isValidWord(typedWord);
        if (mCorrectionMode == Suggest.CORRECTION_FULL) {
            correctionAvailable |= typedWordValid;
        }
        mCandidateView.setSuggestions(stringList, false, typedWordValid, correctionAvailable);
        if (stringList.size() > 0) {
            if (correctionAvailable && !typedWordValid && stringList.size() > 1) {
                mBestWord = stringList.get(1);
            } else {
                mBestWord = typedWord;
            }
        } else {
            mBestWord = null;
        }
        setCandidatesViewShown(isCandidateStripVisible() || mCompletionOn);
    }

    private void pickDefaultSuggestion() {
        // Complete any pending candidate query first
        if (mHandler.hasMessages(MSG_UPDATE_SUGGESTIONS)) {
            mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
            updateSuggestions();
        }
        if (mBestWord != null) {
            TextEntryState.acceptedDefault(mWord.getTypedWord(), mBestWord);
            mJustAccepted = true;
            pickSuggestion(mBestWord);
        }
    }

    public void pickSuggestionManually(int index, CharSequence suggestion) {
        if (mCompletionOn && mCompletions != null && index >= 0
                && index < mCompletions.length) {
            CompletionInfo ci = mCompletions[index];
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitCompletion(ci);
            }
            mCommittedLength = suggestion.length();
            if (mCandidateView != null) {
                mCandidateView.clear();
            }
            updateSoftShiftKeyState(getCurrentInputEditorInfo());
            return;
        }
        pickSuggestion(suggestion);
        TextEntryState.acceptedSuggestion(mComposing.toString(), suggestion);
        // Follow it with a space
        if (mAutoSpace) {
            sendSpace();
        }
        // Fool the state watcher so that a subsequent backspace will not do a revert
        TextEntryState.typedCharacter((char) KEYCODE_SPACE, true);
    }

    private void pickSuggestion(CharSequence suggestion) {
        if (mCapsLock) {
            suggestion = suggestion.toString().toUpperCase();
        } else if (preferCapitalization()
                || (mKeyboardSwitcher.isAlphabetMode() && mInputView.isShifted())) {
            suggestion = Character.toUpperCase(suggestion.charAt(0))
                    + suggestion.subSequence(1, suggestion.length()).toString();
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(suggestion, 1);
        }
        mPredicting = false;
        mCommittedLength = suggestion.length();
        if (mCandidateView != null) {
            mCandidateView.setSuggestions(null, false, false, false);
        }
        updateSoftShiftKeyState(getCurrentInputEditorInfo());
    }

    private boolean isCursorTouchingWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence toLeft = ic.getTextBeforeCursor(1, 0);
        CharSequence toRight = ic.getTextAfterCursor(1, 0);
        if (!TextUtils.isEmpty(toLeft)
                && !isWordSeparator(toLeft.charAt(0))) {
            return true;
        }
        if (!TextUtils.isEmpty(toRight)
                && !isWordSeparator(toRight.charAt(0))) {
            return true;
        }
        return false;
    }

    public void revertLastWord(boolean deleteChar) {
        final int length = mComposing.length();
        if (!mPredicting && length > 0) {
            final InputConnection ic = getCurrentInputConnection();
            mPredicting = true;
            ic.beginBatchEdit();
            mJustRevertedSeparator = ic.getTextBeforeCursor(1, 0);
            if (deleteChar) ic.deleteSurroundingText(1, 0);
            int toDelete = mCommittedLength;
            CharSequence toTheLeft = ic.getTextBeforeCursor(mCommittedLength, 0);
            if (toTheLeft != null && toTheLeft.length() > 0
                    && isWordSeparator(toTheLeft.charAt(0))) {
                toDelete--;
            }
            ic.deleteSurroundingText(toDelete, 0);
            ic.setComposingText(mComposing, 1);
            TextEntryState.backspace();
            ic.endBatchEdit();
            postUpdateSuggestions();
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            mJustRevertedSeparator = null;
        }
    }

    protected String getWordSeparators() {
        return mWordSeparators;
    }
    
    public boolean isWordSeparator(int code) {
        String separators = getWordSeparators();
        return separators.contains(String.valueOf((char)code));
    }

    public boolean isSentenceSeparator(int code) {
        return mSentenceSeparators.contains(String.valueOf((char)code));
    }

    private void sendSpace() {
        sendKeyChar((char)KEYCODE_SPACE);
        updateSoftShiftKeyState(getCurrentInputEditorInfo());
        //onKey(KEY_SPACE[0], KEY_SPACE);
    }

    public boolean preferCapitalization() {
        return mWord.isCapitalized();
    }

    public void swipeRight() {
        if (SoftKeyboardView.DEBUG_AUTO_PLAY) {
            ClipboardManager cm = ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE));
            CharSequence text = cm.getText();
            if (!TextUtils.isEmpty(text)) {
                mInputView.startPlaying(text.toString());
            }
        }
    }
    
    public void swipeLeft() {
        //handleBackspace();
    }

    public void swipeDown() {
        //handleClose();
    }

    public void swipeUp() {
        //launchSettings();
    }

    public void onPress(int primaryCode) {
        vibrate();
        playKeyClick(primaryCode);
    }

    public void onRelease(int primaryCode) {
        //vibrate();
    }
    
    @Override
	public void showStatusIcon(int iconResId) {
		if (mHasVisibleHardwareKeyboard) {
			super.showStatusIcon(iconResId);
		} else {
			hideStatusIcon();
		}
	}

	// receive ringer mode changes to detect silent mode
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRingerMode();
        }
    };

    // update flags for silent mode
    private void updateRingerMode() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (mAudioManager != null) {
            mSilentMode = (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);
        }
    }

    private void playKeyClick(int primaryCode) {
        // if mAudioManager is null, we don't have the ringer state yet
        // mAudioManager will be set by updateRingerMode
        if (mAudioManager == null) {
            if (mInputView != null) {
                updateRingerMode();
            }
        }
        if (mSoundOn && !mSilentMode) {
            // FIXME: Volume and enable should come from UI settings
            // FIXME: These should be triggered after auto-repeat logic
            int sound = AudioManager.FX_KEYPRESS_STANDARD;
            switch (primaryCode) {
                case Keyboard.KEYCODE_DELETE:
                    sound = AudioManager.FX_KEYPRESS_DELETE;
                    break;
                case KEYCODE_ENTER:
                    sound = AudioManager.FX_KEYPRESS_RETURN;
                    break;
                case KEYCODE_SPACE:
                    sound = AudioManager.FX_KEYPRESS_SPACEBAR;
                    break;
            }
            mAudioManager.playSoundEffect(sound, FX_VOLUME);
        }
    }

    private void vibrate() {
        if (!mVibrateOn) {
            return;
        }
        if (mVibrator == null) {
            mVibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE);
        }
        mVibrator.vibrate(mVibrateDuration);
    }
    
    private void launchSettings() {
        handleClose();
        Intent intent = new Intent();
        intent.setClass(GreekIME.this, GreekIMESettings.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void loadSettings() {
        // Get the settings preferences
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, true);
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, false);
        mSMS7bitMode = sp.getBoolean(PREF_SMS_7BIT, true);
        mAutoCap = sp.getBoolean(PREF_AUTO_CAP, true);
        mAutoFinalSigma = sp.getBoolean(PREF_AUTO_FINAL_SIGMA, true);
        mQuickFixes = sp.getBoolean(PREF_QUICK_FIXES, true);
        // If there is no auto text data, then quickfix is forced to "on", so that the other options
        // will continue to work
        if (mInputView != null && AutoText.getSize(mInputView) < 1) mQuickFixes = true;
        mShowSuggestions = sp.getBoolean(PREF_SHOW_SUGGESTIONS, true) & mQuickFixes;
        mAutoComplete = sp.getBoolean(PREF_AUTO_COMPLETE, true) & mShowSuggestions;
        mAutoCorrectOn = mSuggest != null && (mAutoComplete || mQuickFixes);
        mCorrectionMode = mAutoComplete ? 2 : (mQuickFixes ? 1 : 0);
    }

    private void showOptionsMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_dialog_keyboard);
        builder.setNegativeButton(android.R.string.cancel, null);
        CharSequence itemSettings = getString(R.string.greek_ime_settings);
        CharSequence itemInputMethod = getString(R.string.inputMethod);
        builder.setItems(new CharSequence[] {
                itemSettings, itemInputMethod},
                new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface di, int position) {
                di.dismiss();
                switch (position) {
                    case POS_SETTINGS:
                        launchSettings();
                        break;
                    case POS_METHOD:
                        InputMethodManager imManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                        imManager.showInputMethodPicker();
                        break;
                }
            }
        });
        builder.setTitle(getResources().getString(R.string.greek_ime_name));
        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mInputView.getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    private void changeKeyboardLanguage() {
    	mKeyboardSwitcher.toggleLanguage();
    	mSuggest.setDictionaryLanguage(mKeyboardSwitcher.getKeyboardLanguage());
    	accentStateClear();
    	mHardKeyboard.clearAllMetaStates();
    	// XXX - Following code copied blindly from changeKeyboardSymbols (from LatinIME)
        if (mCapsLock && mKeyboardSwitcher.isAlphabetMode()) {
            ((SoftKeyboard) mInputView.getKeyboard()).setShiftLocked(mCapsLock);
        }

        updateSoftShiftKeyState(getCurrentInputEditorInfo()); // XXX - check: clearAllMetaStates ??
        showStatusIcon(mKeyboardSwitcher.getLanguageIcon());
    }
    
    private void changeKeyboardSymbols() {
        mKeyboardSwitcher.toggleSymbols();
        if (mCapsLock && mKeyboardSwitcher.isAlphabetMode()) {
            ((SoftKeyboard) mInputView.getKeyboard()).setShiftLocked(mCapsLock);
        }

        updateSoftShiftKeyState(getCurrentInputEditorInfo());
    }
    
    @Override protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        super.dump(fd, fout, args);
        
        final Printer p = new PrintWriterPrinter(fout);
        p.println("GreekIME state :");
        p.println("  Keyboard mode = " + mKeyboardSwitcher.getKeyboardMode());
        p.println("  mCapsLock=" + mCapsLock);
        p.println("  mComposing=" + mComposing.toString());
        p.println("  mPredictionOn=" + mPredictionOn);
        p.println("  mCorrectionMode=" + mCorrectionMode);
        p.println("  mPredicting=" + mPredicting);
        p.println("  mAutoCorrectOn=" + mAutoCorrectOn);
        p.println("  mAutoSpace=" + mAutoSpace);
        p.println("  mCompletionOn=" + mCompletionOn);
        p.println("  TextEntryState.state=" + TextEntryState.getState());
        p.println("  mSoundOn=" + mSoundOn);
        p.println("  mVibrateOn=" + mVibrateOn);
    }

    // Characters per second measurement
    
    private static final boolean PERF_DEBUG = false;
    private long mLastCpsTime;
    private static final int CPS_BUFFER_SIZE = 16;
    private long[] mCpsIntervals = new long[CPS_BUFFER_SIZE];
    private int mCpsIndex;
    
    private void measureCps() {
        if (!GreekIME.PERF_DEBUG) return;
        long now = System.currentTimeMillis();
        if (mLastCpsTime == 0) mLastCpsTime = now - 100; // Initial
        mCpsIntervals[mCpsIndex] = now - mLastCpsTime;
        mLastCpsTime = now;
        mCpsIndex = (mCpsIndex + 1) % CPS_BUFFER_SIZE;
        long total = 0;
        for (int i = 0; i < CPS_BUFFER_SIZE; i++) total += mCpsIntervals[i];
        System.out.println("CPS = " + ((CPS_BUFFER_SIZE * 1000f) / total));
    }
    
    private static SparseIntArray sAcuteAccentTable = new SparseIntArray();
    private static SparseIntArray sDiaeresisAccentTable = new SparseIntArray();
    private static SparseIntArray sBothAccentTable = new SparseIntArray();
    private static SparseIntArray sKeyCodeTable = new SparseIntArray();
    private static SparseIntArray sSMSKeyCodeTable = new SparseIntArray();
    static {
    	// Initialize acute accent table
    	sAcuteAccentTable.append(KEYCODE_SPACE, KEYCODE_ACUTE);
    	sAcuteAccentTable.append('\u0391', '\u0386');  // Α -> Ά
    	sAcuteAccentTable.append('\u0395', '\u0388');  // Ε -> Έ
    	sAcuteAccentTable.append('\u0399', '\u038a');  // Ι -> Ί
    	sAcuteAccentTable.append('\u039f', '\u038c');  // Ο -> Ό
    	sAcuteAccentTable.append('\u03a5', '\u038e');  // Υ -> Ύ
    	sAcuteAccentTable.append('\u03a9', '\u038f');  // Ω -> Ώ
    	sAcuteAccentTable.append('\u03b1', '\u03ac');  // α -> ά
    	sAcuteAccentTable.append('\u03b5', '\u03ad');  // ε -> έ
    	sAcuteAccentTable.append('\u03b7', '\u03ae');  // η -> ή
    	sAcuteAccentTable.append('\u03b9', '\u03af');  // ι -> ί
    	sAcuteAccentTable.append('\u03bf', '\u03cc');  // ο -> ό
    	sAcuteAccentTable.append('\u03c5', '\u03cd');  // υ -> ύ
    	sAcuteAccentTable.append('\u03c9', '\u03ce');  // ω -> ώ
    	// Initialize diaeresis table
    	sDiaeresisAccentTable.append(KEYCODE_SPACE, KEYCODE_DIAERESIS);
    	sDiaeresisAccentTable.append('\u0399', '\u03aa');  // Ι -> Ϊ
    	sDiaeresisAccentTable.append('\u03a5', '\u03ab');  // Υ -> Ϋ
    	sDiaeresisAccentTable.append('\u03b9', '\u03ca');  // ι -> ϊ
    	sDiaeresisAccentTable.append('\u03c5', '\u03cb');  // υ -> ϋ
    	// Initialize acute+diaeresis table
    	sBothAccentTable.append(KEYCODE_SPACE, KEYCODE_ACUTE_DIAERESIS);
    	sBothAccentTable.append('\u03b9', '\u0390');  // ι -> ΐ
    	sBothAccentTable.append('\u03c5', '\u03b0');  // υ -> ΰ
    	
    	// Initialize hardware key code table
        sKeyCodeTable.append(KeyEvent.KEYCODE_A, '\u03b1');  // α
        sKeyCodeTable.append(KeyEvent.KEYCODE_B, '\u03b2');  // β
        sKeyCodeTable.append(KeyEvent.KEYCODE_C, '\u03c8');  // ψ
        sKeyCodeTable.append(KeyEvent.KEYCODE_D, '\u03b4');  // δ
        sKeyCodeTable.append(KeyEvent.KEYCODE_E, '\u03b5');  // ε
        sKeyCodeTable.append(KeyEvent.KEYCODE_F, '\u03c6');  // φ
        sKeyCodeTable.append(KeyEvent.KEYCODE_G, '\u03b3');  // γ
        sKeyCodeTable.append(KeyEvent.KEYCODE_H, '\u03b7');  // η
        sKeyCodeTable.append(KeyEvent.KEYCODE_I, '\u03b9');  // ι
        sKeyCodeTable.append(KeyEvent.KEYCODE_J, '\u03be');  // ξ
        sKeyCodeTable.append(KeyEvent.KEYCODE_K, '\u03ba');  // κ
        sKeyCodeTable.append(KeyEvent.KEYCODE_L, '\u03bb');  // λ
        sKeyCodeTable.append(KeyEvent.KEYCODE_M, '\u03bc');  // μ
        sKeyCodeTable.append(KeyEvent.KEYCODE_N, '\u03bd');  // ν
        sKeyCodeTable.append(KeyEvent.KEYCODE_O, '\u03bf');  // ο
        sKeyCodeTable.append(KeyEvent.KEYCODE_P, '\u03c0');  // π
        sKeyCodeTable.append(KeyEvent.KEYCODE_Q, SoftKeyboard.KEYCODE_ACCENT);
        sKeyCodeTable.append(KeyEvent.KEYCODE_R, '\u03c1');  // ρ
        sKeyCodeTable.append(KeyEvent.KEYCODE_S, '\u03c3');  // σ
        sKeyCodeTable.append(KeyEvent.KEYCODE_T, '\u03c4');  // τ
        sKeyCodeTable.append(KeyEvent.KEYCODE_U, '\u03b8');  // θ
        sKeyCodeTable.append(KeyEvent.KEYCODE_V, '\u03c9');  // ω  		
        sKeyCodeTable.append(KeyEvent.KEYCODE_W, '\u03c2');  // ς
        sKeyCodeTable.append(KeyEvent.KEYCODE_X, '\u03c7');  // χ
        sKeyCodeTable.append(KeyEvent.KEYCODE_Y, '\u03c5');  // υ
        sKeyCodeTable.append(KeyEvent.KEYCODE_Z, '\u03b6');  // ζ
    	sKeyCodeTable.append(KeyEvent.KEYCODE_SPACE, KEYCODE_SPACE);
        sKeyCodeTable.append(KeyEvent.KEYCODE_SEMICOLON, SoftKeyboard.KEYCODE_ACCENT);
        
        // Initialize hardware keycode table, for faking 7bit SMS coding
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_A, 'A');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_B, 'B');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_C, '\u03a8');  // Ψ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_D, '\u0394');  // Δ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_E, 'E');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_F, '\u03a6');  // Φ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_G, '\u0393');  // Γ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_H, 'H');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_I, 'I');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_J, '\u039e');  // Ξ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_K, 'K');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_L, '\u039b');  // Λ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_M, 'M');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_N, 'N');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_O, 'O');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_P, '\u03a0');  // Π
        // No mapping for KeyEvent.KEYCODE_Q
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_R, 'P');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_S, '\u03a3');  // Σ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_T, 'T');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_U, '\u0398');  // Θ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_V, '\u03a9');  // Ω  		
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_W, '\u03a3');  // Σ
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_X, 'X');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_Y, 'Y');
        sSMSKeyCodeTable.append(KeyEvent.KEYCODE_Z, 'Z');
    	sSMSKeyCodeTable.append(KeyEvent.KEYCODE_SPACE, KEYCODE_SPACE);
    }
}



