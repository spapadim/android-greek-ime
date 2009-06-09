/*
 * Copyright (C) 2008 Google Inc.
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

import android.inputmethodservice.Keyboard;

import java.util.List;

public class KeyboardSwitcher {

    public static final int MODE_TEXT = 1;
    public static final int MODE_SYMBOLS = 2;
    public static final int MODE_PHONE = 3;
    public static final int MODE_URL = 4;
    public static final int MODE_EMAIL = 5;
    public static final int MODE_IM = 6;
    
    public static final int LANGUAGE_EN = 0;
    public static final int LANGUAGE_EL = 1;
    public static final int LANGUAGE_COUNT = 2;
   
    public static final int KEYBOARDMODE_NORMAL = R.id.mode_normal;
    public static final int KEYBOARDMODE_URL = R.id.mode_url;
    public static final int KEYBOARDMODE_EMAIL = R.id.mode_email;
    public static final int KEYBOARDMODE_IM = R.id.mode_im;
    

    SoftKeyboardView mInputView;
    GreekIME mContext;

    // Keyboards that are language-independent
    private SoftKeyboard mPhoneKeyboard;
    private SoftKeyboard mPhoneSymbolsKeyboard;
    // Keyboards with language-specific variants
    private SoftKeyboard[] mSymbolsKeyboard = new SoftKeyboard[2];
    private SoftKeyboard[] mSymbolsShiftedKeyboard = new SoftKeyboard[2];
    private SoftKeyboard[] mTextKeyboard = new SoftKeyboard[2];
    private SoftKeyboard[] mUrlKeyboard = new SoftKeyboard[2];
    private SoftKeyboard[] mEmailKeyboard = new SoftKeyboard[2];
    private SoftKeyboard[] mIMKeyboard = new SoftKeyboard[2];
    
    private static final int[] XML_RES_ID = new int[]{ R.xml.kbd_latin, R.xml.kbd_greek };
    private static final int[] XML_MODE_ID = new int[]{ R.id.lang_en, R.id.lang_el };
    private static final int[] ICON_RES_ID = new int[]{ R.drawable.ime_en, R.drawable.ime_el };
    
    private int mMode;
    private int mLanguage;
    private int mImeOptions;

    KeyboardSwitcher(GreekIME context) {
        mContext = context;
    }

    void setInputView(SoftKeyboardView inputView) {
        mInputView = inputView;
    }
    
    void makeKeyboards() {
        // Delayed creation when keyboard mode is set.
        mPhoneKeyboard = null;
        mPhoneSymbolsKeyboard = null;
        for (int lang = 0;  lang < LANGUAGE_COUNT;  lang++) {
        	mTextKeyboard[lang] = null;
        	mUrlKeyboard[lang] = null;
        	mEmailKeyboard[lang] = null;
        	mIMKeyboard[lang] = null;
        	mSymbolsKeyboard[lang] = null;
        	mSymbolsShiftedKeyboard[lang] = null;
        }
    }

    void setSoftKeyboardState(int mode, int language, int imeOptions) {
        mMode = mode;
        mLanguage = language;
        mImeOptions = imeOptions;
        SoftKeyboard keyboard = (SoftKeyboard) mInputView.getKeyboard();
        mInputView.setPreviewEnabled(true);
        switch (mode) {
            case MODE_TEXT:
                if (mTextKeyboard[mLanguage] == null) {
                    mTextKeyboard[mLanguage] = new SoftKeyboard(mContext, XML_RES_ID[mLanguage],
                            KEYBOARDMODE_NORMAL);
                    mTextKeyboard[mLanguage].enableShiftLock();
                }
                keyboard = mTextKeyboard[mLanguage];
                break;
            case MODE_SYMBOLS:
                if (mSymbolsKeyboard[mLanguage] == null) {
                    mSymbolsKeyboard[mLanguage] = new SoftKeyboard(mContext, 
                            R.xml.kbd_symbols, XML_MODE_ID[mLanguage]);
                }
                if (mSymbolsShiftedKeyboard[mLanguage] == null) {
                    mSymbolsShiftedKeyboard[mLanguage] = new SoftKeyboard(mContext, 
                            R.xml.kbd_symbols_shift, XML_MODE_ID[mLanguage]);
                }
                keyboard = mSymbolsKeyboard[mLanguage];
                break;
            case MODE_PHONE:
                if (mPhoneKeyboard == null) {
                    mPhoneKeyboard = new SoftKeyboard(mContext, R.xml.kbd_phone);
                }
                mInputView.setPhoneKeyboard(mPhoneKeyboard);
                if (mPhoneSymbolsKeyboard == null) {
                    mPhoneSymbolsKeyboard = new SoftKeyboard(mContext, R.xml.kbd_phone_symbols);
                }
                keyboard = mPhoneKeyboard;
                mInputView.setPreviewEnabled(false);
                break;
            case MODE_URL:
                if (mUrlKeyboard[mLanguage] == null) {
                    mUrlKeyboard[mLanguage] = new SoftKeyboard(mContext, XML_RES_ID[mLanguage], KEYBOARDMODE_URL);
                    mUrlKeyboard[mLanguage].enableShiftLock();
                }
                keyboard = mUrlKeyboard[mLanguage];
                break;
            case MODE_EMAIL:
                if (mEmailKeyboard[mLanguage] == null) {
                    mEmailKeyboard[mLanguage] = new SoftKeyboard(mContext, XML_RES_ID[mLanguage], KEYBOARDMODE_EMAIL);
                    mEmailKeyboard[mLanguage].enableShiftLock();
                }
                keyboard = mEmailKeyboard[mLanguage];
                break;
            case MODE_IM:
                if (mIMKeyboard[mLanguage] == null) {
                    mIMKeyboard[mLanguage] = new SoftKeyboard(mContext, XML_RES_ID[mLanguage], KEYBOARDMODE_IM);
                    mIMKeyboard[mLanguage].enableShiftLock();
                }
                keyboard = mIMKeyboard[mLanguage];
                break;
        }
        mInputView.setKeyboard(keyboard);
        keyboard.setShifted(false);
        keyboard.setShiftLocked(keyboard.isShiftLocked());
        keyboard.setImeOptions(mContext.getResources(), mMode, imeOptions);
    }

    int getKeyboardMode() {
        return mMode;
    }
    
    int getKeyboardLanguage() {
    	return mLanguage;
    }
    
    int getLanguageIcon () {
    	return ICON_RES_ID[mLanguage];
    }
    
    boolean isTextMode() {
        return mMode == MODE_TEXT;
    }

    boolean isAlphabetMode() {
        Keyboard current = mInputView.getKeyboard();
        if (current == mTextKeyboard[mLanguage]
                || current == mUrlKeyboard[mLanguage]
                || current == mIMKeyboard[mLanguage]
                || current == mEmailKeyboard[mLanguage]) {
            return true;
        }
        return false;
    }
    
    void toggleSymbolShift() {
        Keyboard currentKeyboard = mInputView.getKeyboard();
        if (currentKeyboard == mSymbolsKeyboard[mLanguage]) {
            mSymbolsKeyboard[mLanguage].setShifted(true);
            mInputView.setKeyboard(mSymbolsShiftedKeyboard[mLanguage]);
            mSymbolsShiftedKeyboard[mLanguage].setShifted(true);
            mSymbolsShiftedKeyboard[mLanguage].setImeOptions(mContext.getResources(), mMode, mImeOptions);
        } else if (currentKeyboard == mSymbolsShiftedKeyboard[mLanguage]) {
            mSymbolsShiftedKeyboard[mLanguage].setShifted(false);
            mInputView.setKeyboard(mSymbolsKeyboard[mLanguage]);
            mSymbolsKeyboard[mLanguage].setShifted(false);
            mSymbolsKeyboard[mLanguage].setImeOptions(mContext.getResources(), mMode, mImeOptions);
        }
    }

    void toggleSymbols() {
        SoftKeyboard current = (SoftKeyboard)mInputView.getKeyboard();
        if (mSymbolsKeyboard[mLanguage] == null) {
            mSymbolsKeyboard[mLanguage] = new SoftKeyboard(mContext, R.xml.kbd_symbols, XML_MODE_ID[mLanguage]);
        }
        if (mSymbolsShiftedKeyboard[mLanguage] == null) {
            mSymbolsShiftedKeyboard[mLanguage] = new SoftKeyboard(mContext, R.xml.kbd_symbols_shift, XML_MODE_ID[mLanguage]);
        }
        if (current == mSymbolsKeyboard[mLanguage] || current == mSymbolsShiftedKeyboard[mLanguage]) {
            setSoftKeyboardState(mMode, mLanguage, mImeOptions); // Could be qwerty, alpha, url, email or im
            return;
        } else if (current == mPhoneKeyboard) {
            current = mPhoneSymbolsKeyboard;
            mPhoneSymbolsKeyboard.setImeOptions(mContext.getResources(), mMode, mImeOptions);
        } else if (current == mPhoneSymbolsKeyboard) {
            current = mPhoneKeyboard;
            mPhoneKeyboard.setImeOptions(mContext.getResources(), mMode, mImeOptions);
        } else {
            current = mSymbolsKeyboard[mLanguage];
            mSymbolsKeyboard[mLanguage].setImeOptions(mContext.getResources(), mMode, mImeOptions);
        }
        mInputView.setKeyboard(current);
        if (current == mSymbolsKeyboard[mLanguage]) {
            current.setShifted(false);
        }
    }
    
    void toggleLanguage() {
    	if (mLanguage == LANGUAGE_EN) {
    		mLanguage = LANGUAGE_EL;
    	} else {
    		mLanguage = LANGUAGE_EN;
    	}
    	setSoftKeyboardState(mMode, mLanguage, mImeOptions);
    }
}
