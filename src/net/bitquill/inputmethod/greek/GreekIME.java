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
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class GreekIME extends InputMethodService 
        implements KeyboardView.OnKeyboardActionListener {
    static final boolean DEBUG = false;
    static final boolean TRACE = false;
    
    private static final String PREF_VIBRATE_ON = "vibrate_on";
    private static final String PREF_SOUND_ON = "sound_on";
    private static final String PREF_AUTO_CAP = "auto_cap";
    private static final String PREF_SMS_7BIT = "sms_7bit";
    private static final String PREF_AUTO_FINAL_SIGMA = "auto_final_sigma";
    
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
    
    private AlertDialog mOptionsDialog;
    
    KeyboardSwitcher mKeyboardSwitcher;
    HardKeyboardState mHardKeyboard;
    
    private StringBuilder mComposing = new StringBuilder();
    private WordComposer mWord = new WordComposer();
    private boolean mAutoSpace;
    private boolean mCapsLock;
    private boolean mVibrateOn;
    private boolean mSMS7bitMode;
    private boolean mSoundOn;
    private boolean mAutoCap;
    private boolean mAutoFinalSigma;
    // Indicates whether the suggestion strip is to be on in landscape
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
    
    @Override public void onCreate() {
        super.onCreate();
        //setStatusIcon(R.drawable.ime_qwerty);
        mKeyboardSwitcher = new KeyboardSwitcher(this);
        mHardKeyboard = new HardKeyboardState(this);
        
        mVibrateDuration = getResources().getInteger(R.integer.vibrate_duration_ms);
        
        // register to receive ringer mode changes for silent mode
        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, filter);
        
        mWordSeparators = getResources().getString(R.string.word_separators);
        mSentenceSeparators = getResources().getString(R.string.sentence_separators);
        if (TRACE) Debug.startMethodTracing("greekime");
    }    
    
    @Override public void onDestroy() {
        unregisterReceiver(mReceiver);
        if (TRACE) Debug.stopMethodTracing();
        super.onDestroy();
    }
    
    @Override
	public void onConfigurationChanged(Configuration config) {
		super.onConfigurationChanged(config);
		mHardKeyboard.clearAllMetaStates();
	    mHasVisibleHardwareKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS
        	&& config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
	}

	@Override
    public View onCreateInputView() {
        mInputView = (SoftKeyboardView) getLayoutInflater().inflate(
                R.layout.input, null);
        mKeyboardSwitcher.setInputView(mInputView);
        mKeyboardSwitcher.makeKeyboards();
        mInputView.setOnKeyboardActionListener(this);
        mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_TEXT, KeyboardSwitcher.LANGUAGE_EN, 0);
        return mInputView;
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
                // Make sure that passwords are not displayed in candidate view
                int variation = attribute.inputType &  EditorInfo.TYPE_MASK_VARIATION;
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME) {
                    mAutoSpace = false;
                } else {
                    mAutoSpace = true;
                }
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
                    mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_EMAIL,
                    		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI) {
                    mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_URL,
                    		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
                    mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_IM,
                    		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                }
                updateShiftKeyState(attribute);
                break;
            default:
                mKeyboardSwitcher.setSoftKeyboardState(KeyboardSwitcher.MODE_TEXT,
                		KeyboardSwitcher.LANGUAGE_EN, attribute.imeOptions);
                updateShiftKeyState(attribute);
        }
        mInputView.closing();
        mComposing.setLength(0);
        mDeleteCount = 0;
        setCandidatesViewShown(false);
        mInputView.setProximityCorrectionEnabled(true);
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
    public void hideWindow() {
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
        TextEntryState.endSession();
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

    public void updateShiftKeyState(EditorInfo attr) {
        InputConnection ic = getCurrentInputConnection();
        if (attr != null && mInputView != null && mKeyboardSwitcher.isAlphabetMode()
                && ic != null) {
            int caps = 0;
            EditorInfo ei = getCurrentInputEditorInfo();
            if (mAutoCap && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
                caps = ic.getCursorCapsMode(attr.inputType);
            }
            mInputView.setShifted(mCapsLock || caps != 0);
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
            updateShiftKeyState(getCurrentInputEditorInfo());
        }
    }
    
    private void finalizeSigma() {
    	if (!mAutoFinalSigma) {
    		return;
    	}
    	final InputConnection ic = getCurrentInputConnection();
    	CharSequence lastChar = ic.getTextBeforeCursor(1, 0);
    	if (lastChar != null && lastChar.charAt(0) == KEYCODE_LC_SIGMA) {
    		ic.beginBatchEdit();
    		ic.deleteSurroundingText(1, 0);
    		ic.commitText("\u03c2", 1);
    		ic.endBatchEdit();
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
        }
    }
    
    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.beginBatchEdit();
        ic.commitText(text, 1);
        ic.endBatchEdit();
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private void handleBackspace() {
        boolean deleteChar = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        deleteChar = true;
        updateShiftKeyState(getCurrentInputEditorInfo());
        TextEntryState.backspace();
        if (TextEntryState.getState() == TextEntryState.STATE_UNDO_COMMIT) {
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
    }
    
    private boolean isAccentShifted () {
    	return (mAccentShiftState != ACCENT_STATE_NONE)
    	    && (mKeyboardSwitcher.getKeyboardLanguage() == KeyboardSwitcher.LANGUAGE_EL);
    }
    
    private void handleShift() {
        if (mKeyboardSwitcher.isAlphabetMode()) {
            // Alphabet keyboard
            checkToggleCapsLock();
            mInputView.setShifted(mCapsLock || !mInputView.isShifted());
        } else {
            mKeyboardSwitcher.toggleSymbolShift();
        }
    }
    
    private void handleCharacter(int primaryCode, int[] keyCodes) {
        if (mInputView.isShifted()) {
            primaryCode = Character.toUpperCase(primaryCode);
        }
        if (isAccentShifted()) {
        	primaryCode = addAccent(primaryCode, mAccentShiftState);
        }
        sendKeyChar((char)primaryCode);
        updateShiftKeyState(getCurrentInputEditorInfo());
        accentStateClear();
        measureCps();
        TextEntryState.typedCharacter((char) primaryCode, isWordSeparator(primaryCode));
    }
    
    /**
     * 
     */
    private void accentStateShift () {
    	InputConnection ic = getCurrentInputConnection();
    	switch (mAccentShiftState) {
    	case ACCENT_STATE_NONE:
    		mAccentShiftState = ACCENT_STATE_ACUTE;
    		ic.setComposingText("\u0384", 1);
    		break;
    	case ACCENT_STATE_ACUTE:
    		mAccentShiftState = ACCENT_STATE_DIAERESIS;
    		ic.setComposingText("\u00a8", 1);
    		break;
    	case ACCENT_STATE_DIAERESIS:
    		mAccentShiftState = ACCENT_STATE_BOTH;
    		ic.setComposingText("\u0385", 1);
    		break;
    	default:
    		mAccentShiftState = ACCENT_STATE_NONE;
    		ic.setComposingText("", 1);
    		break;
    	}
    }
    
    private void accentStateClear () {
    	InputConnection ic = getCurrentInputConnection();
    	mAccentShiftState = ACCENT_STATE_NONE;
    	if (ic != null) {  // XXX - why should we have to check?
    		ic.setComposingText("", 1);
    	}
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
    			boolean caps = event.isShiftPressed() || mHardKeyboard.isMetaOn(HardKeyboardState.META_SHIFT);
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
        // Handle separator
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        if (primaryCode == KEYCODE_SPACE && isAccentShifted()) {  // XXX - fixme, don't hardcode space code?
        	primaryCode = addAccent(primaryCode, mAccentShiftState);
        }
        finalizeSigma();
        sendKeyChar((char)primaryCode);
        TextEntryState.typedCharacter((char) primaryCode, true);
        if (TextEntryState.getState() == TextEntryState.STATE_PUNCTUATION_AFTER_ACCEPTED 
                && primaryCode != KEYCODE_ENTER) {
            swapPunctuationAndSpace();
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
        accentStateClear();
        if (ic != null) {
            ic.endBatchEdit();
        }
    }
    
    private void handleClose() {
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
    	accentStateClear();
    	mHardKeyboard.clearAllMetaStates();
    	// XXX - Following code copied blindly from changeKeyboardSymbols (from LatinIME)
        if (mCapsLock && mKeyboardSwitcher.isAlphabetMode()) {
            ((SoftKeyboard) mInputView.getKeyboard()).setShiftLocked(mCapsLock);
        }

        updateShiftKeyState(getCurrentInputEditorInfo()); // XXX - check: clearAllMetaStates ??
        showStatusIcon(mKeyboardSwitcher.getLanguageIcon());
    }
    
    private void changeKeyboardSymbols() {
        mKeyboardSwitcher.toggleSymbols();
        if (mCapsLock && mKeyboardSwitcher.isAlphabetMode()) {
            ((SoftKeyboard) mInputView.getKeyboard()).setShiftLocked(mCapsLock);
        }

        updateShiftKeyState(getCurrentInputEditorInfo());
    }
    
    @Override protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        super.dump(fd, fout, args);
        
        final Printer p = new PrintWriterPrinter(fout);
        p.println("GreekIME state :");
        p.println("  Keyboard mode = " + mKeyboardSwitcher.getKeyboardMode());
        p.println("  mCapsLock=" + mCapsLock);
        p.println("  mComposing=" + mComposing.toString());
        p.println("  mAutoSpace=" + mAutoSpace);
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



