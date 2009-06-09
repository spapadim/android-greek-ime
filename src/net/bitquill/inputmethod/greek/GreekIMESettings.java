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

import android.os.Bundle;
import android.preference.PreferenceActivity;

public class GreekIMESettings extends PreferenceActivity {
      
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs);
    }

}
