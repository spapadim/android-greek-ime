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
import android.util.PrintWriterPrinter;
import android.util.Printer;
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
    
    // How many continuous deletes at which to start deleting at a higher speed.
    private static final int DELETE_ACCELERATE_AT = 20;
    // Key events coming any faster than this are long-presses.
    private static final int QUICK_PRESS = 200; 
    
    private static final int KEYCODE_ENTER = 10;
    private static final int KEYCODE_SPACE = ' ';

    // Contextual menu positions
    private static final int POS_SETTINGS = 0;
    private static final int POS_METHOD = 1;
    
    private SoftKeyboardView mInputView;
    
    private AlertDialog mOptionsDialog;
    
    KeyboardSwitcher mKeyboardSwitcher;
    
    private StringBuilder mComposing = new StringBuilder();
    private WordComposer mWord = new WordComposer();
    private boolean mAutoSpace;
    private boolean mCapsLock;
    private boolean mVibrateOn;
    private boolean mSoundOn;
    private boolean mAutoCap;
    // Indicates whether the suggestion strip is to be on in landscape
    private int mDeleteCount;
    private long mLastKeyTime;
    
    private boolean mAccentShift;
    private boolean mCapsShift;
    
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
        
        mVibrateDuration = getResources().getInteger(R.integer.vibrate_duration_ms);
        
        // register to receive ringer mode changes for silent mode
        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, filter);
        
        mWordSeparators = getResources().getString(R.string.word_separators);
        mSentenceSeparators = getResources().getString(R.string.sentence_separators);
    }    
    
    @Override public void onDestroy() {
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }
    
    @Override
	public void onConfigurationChanged(Configuration config) {
		super.onConfigurationChanged(config);
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
		super.onStartInput(attribute, restarting);
	}

	@Override 
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        // In landscape mode, this method gets called without the input view being created.
        if (mInputView == null) {
            return;
        }

        mKeyboardSwitcher.makeKeyboards();
        
        TextEntryState.newSession(this);
        
        mCapsLock = false;
        switch (attribute.inputType&EditorInfo.TYPE_MASK_CLASS) {
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
        showStatusIcon(mKeyboardSwitcher.getLanguageIcon());
        mInputView.closing();
        mComposing.setLength(0);
        mDeleteCount = 0;
        setCandidatesViewShown(false);
        loadSettings();
        mInputView.setProximityCorrectionEnabled(true);
        if (TRACE) Debug.startMethodTracing("latinime");
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
    public void hideWindow() {
        if (TRACE) Debug.stopMethodTracing();
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
        	mCapsShift = false;
            int allMetaStates = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
            	| KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_SHIFT_ON
            	| KeyEvent.META_SHIFT_LEFT_ON
            	| KeyEvent.META_SHIFT_RIGHT_ON | KeyEvent.META_SYM_ON;
            getCurrentInputConnection().clearMetaKeyStates(allMetaStates);  // from PinyinIME
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
                mAccentShift = false;
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
                mAccentShift = false;
                break;
            case SoftKeyboard.KEYCODE_SHIFT_LONGPRESS:
                if (mCapsLock) {
                    handleShift();
                } else {
                    toggleCapsLock();
                }
                // Don't reset accent shift here
                break;
            case Keyboard.KEYCODE_MODE_CHANGE:
                changeKeyboardLanguage();
                mAccentShift = false;
                break;
            case SoftKeyboard.KEYCODE_SYMBOL_SHIFT:
            	changeKeyboardSymbols();
            	mAccentShift = false;
            	break;
            case SoftKeyboard.KEYCODE_ACCENT:
            	mAccentShift = true;
            	break;
            default:
                if (isWordSeparator(primaryCode)) {
                    handleSeparator(primaryCode);
                } else {
                    handleCharacter(primaryCode, keyCodes);
                }
            	mAccentShift = false;
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
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            if (mDeleteCount > DELETE_ACCELERATE_AT) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            }
        }
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
        if (mAccentShift && KeyboardSwitcher.LANGUAGE_EL == mKeyboardSwitcher.getKeyboardLanguage()) {
        	primaryCode = addAccent(primaryCode);
        }
        sendKeyChar((char)primaryCode);
        updateShiftKeyState(getCurrentInputEditorInfo());
        measureCps();
        TextEntryState.typedCharacter((char) primaryCode, isWordSeparator(primaryCode));
    }
    
    /**
     * Add an acute accent to a Greek character, if applicable.
     * @param code  Unicode character code
     * @return      Corresponding accented character; returns code itself if character cannot be accented
     */
    private int addAccent (int code) {
    	switch (code) {
    	case 'α':
    		return 'ά';
    	case 'ε':
    		return 'έ';
    	case 'η':
    		return 'ή';
    	case 'ι':
    		return 'ί';
    	case 'ο':
    		return 'ό';
    	case 'υ':
    		return 'ύ';
    	case 'ω':
    		return 'ώ';
    	case 'Α':
    		return 'Ά';
    	case 'Ε':
    		return 'Έ';
    	case 'Ι':
    		return 'Ί';
    	case 'Ο':
    		return 'Ό';
    	case 'Υ':
    		return 'Ύ';
    	case 'Ω':
    		return 'Ώ';
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
    private int keyCodeToChar (int keyCode, boolean upperCase, boolean accented) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_A:
        	if (accented) {
                return upperCase ? 'Ά' : 'ά';
        	} else {
                return upperCase ? 'Α' : 'α';        		
        	}
        case KeyEvent.KEYCODE_B:
            return upperCase ? 'Β' : 'β';
        case KeyEvent.KEYCODE_C:
            return upperCase ? 'Ψ' : 'ψ';
        case KeyEvent.KEYCODE_D:
            return upperCase ? 'Δ' : 'δ';
        case KeyEvent.KEYCODE_E:
        	if (accented) {
                return upperCase ? 'Έ' : 'έ';
        	} else {
                return upperCase ? 'Ε' : 'ε';
        	}
        case KeyEvent.KEYCODE_F:
            return upperCase ? 'Φ' : 'φ';
        case KeyEvent.KEYCODE_G:
            return upperCase ? 'Γ' : 'γ';
        case KeyEvent.KEYCODE_H:
        	if (accented) {
                return upperCase ? 'Ή' : 'ή';        		
        	} else {
                return upperCase ? 'Η' : 'η';
            }
        case KeyEvent.KEYCODE_I:
        	if (accented) {
                return upperCase ? 'Ί' : 'ί';
        	} else {
                return upperCase ? 'Ι' : 'ι';
        	}
        case KeyEvent.KEYCODE_J:
            return upperCase ? 'Ξ' : 'ξ';
        case KeyEvent.KEYCODE_K:
            return upperCase ? 'Κ' : 'κ';
        case KeyEvent.KEYCODE_L:
            return upperCase ? 'Λ' : 'λ';
        case KeyEvent.KEYCODE_M:
            return upperCase ? 'Μ' : 'μ';
        case KeyEvent.KEYCODE_N:
            return upperCase ? 'Ν' : 'ν';
        case KeyEvent.KEYCODE_O:
        	if (accented) {
                return upperCase ? 'Ό' : 'ό';
        	} else {
                return upperCase ? 'Ο' : 'ο';
        	}
        case KeyEvent.KEYCODE_P:
            return upperCase ? 'Π' : 'π';
        case KeyEvent.KEYCODE_Q:
            return -1;  // XXX - check
        case KeyEvent.KEYCODE_R:
            return upperCase ? 'Ρ' : 'ρ';
        case KeyEvent.KEYCODE_S:
            return upperCase ? 'Σ' : 'σ';
        case KeyEvent.KEYCODE_T:
            return upperCase ? 'Τ' : 'τ';
        case KeyEvent.KEYCODE_U:
            return upperCase ? 'Θ' : 'θ';
        case KeyEvent.KEYCODE_V:
        	if (accented) {
                return upperCase ? 'Ώ' : 'ώ';
        	} else {
                return upperCase ? 'Ω' : 'ω';        		
        	}
        case KeyEvent.KEYCODE_W:
            return upperCase ? 'Σ' : 'ς';
        case KeyEvent.KEYCODE_X:
            return upperCase ? 'Χ' : 'χ';
        case KeyEvent.KEYCODE_Y:
        	if (accented) {
                return upperCase ? 'Ύ' : 'ύ';
        	} else {
                return upperCase ? 'Υ' : 'υ';        		
        	}
        case KeyEvent.KEYCODE_Z:
            return upperCase ? 'Ζ' : 'ζ';
        default:
        	return -1;
    	}
    }
    
    /**
     * Handle a hardware key press; should only be called when in greek language mode.
     * @param keyCode  Hardware keycode
     * @param event    Key event
     * @return         True if the event was consumed, false if it should be propagated
     */
    private boolean handleHardKeyGreek(int keyCode, KeyEvent event) {
    	if (KeyEvent.KEYCODE_Q == keyCode
    			|| KeyEvent.KEYCODE_SEMICOLON == keyCode) {
    		mAccentShift = true;
    		return true;
    	} else if (KeyEvent.KEYCODE_SHIFT_LEFT == keyCode
    			|| KeyEvent.KEYCODE_SHIFT_RIGHT == keyCode) {
    		// FIXME - Cannot figure out how to access hardware keyboard caps state,
    		// so faking it, like PinyinIME does
    		mCapsShift = true;
    		return true;
    	} else {
    		boolean caps = event.isShiftPressed() || mCapsShift;
			int greekCode = keyCodeToChar (keyCode, caps, mAccentShift);
			mAccentShift = false;
			mCapsShift = false;
    		if (greekCode >= 0) {
    			sendKeyChar((char)greekCode);
    			return true;
    		}
    	}
    	return false;
    }

    private void handleSeparator(int primaryCode) {
        // Handle separator
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        sendKeyChar((char)primaryCode);
        TextEntryState.typedCharacter((char) primaryCode, true);
        if (TextEntryState.getState() == TextEntryState.STATE_PUNCTUATION_AFTER_ACCEPTED 
                && primaryCode != KEYCODE_ENTER) {
            swapPunctuationAndSpace();
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
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
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, false);
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, false);
        mAutoCap = sp.getBoolean(PREF_AUTO_CAP, true);
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
    	// XXX - Following code copied blindly from changeKeyboardSymbols (from LatinIME)
        if (mCapsLock && mKeyboardSwitcher.isAlphabetMode()) {
            ((SoftKeyboard) mInputView.getKeyboard()).setShiftLocked(mCapsLock);
        }

        updateShiftKeyState(getCurrentInputEditorInfo());
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
        p.println("LatinIME state :");
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
    
}



