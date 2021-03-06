/*
**
** Copyright 2009, The Android Open Source Project
** Copyright 2009, Spiros Papadimitriou <spapadim@cs.cmu.edu>
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#include <stdio.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <string.h>
//#include <cutils/log.h>  // Not available in NDK

// ICU4C not available in NDK,
// substituted with static map in lowerchars.h
//#include <unicode/uchar.h>

#include "dictionary.h"
#include "basechars.h"
#include "lowerchars.h"
#include "unicodemap.h"

#define DEBUG_DICT 0

namespace greekim {

Dictionary::Dictionary(unsigned char *dict, int typedLetterMultiplier, int fullWordMultiplier)
{
    mDict = dict;
    mTypedLetterMultiplier = typedLetterMultiplier;
    mFullWordMultiplier = fullWordMultiplier;
}

Dictionary::~Dictionary()
{
}

int Dictionary::getSuggestions(int *codes, int codesSize, unsigned short *outWords, int *frequencies,
        int maxWordLength, int maxWords, int maxAlternatives)
{
    memset(frequencies, 0, maxWords * sizeof(*frequencies));
    memset(outWords, 0, maxWords * maxWordLength * sizeof(*outWords));

    mFrequencies = frequencies;
    mOutputChars = outWords;
    mInputCodes = codes;
    mInputLength = codesSize;
    mMaxAlternatives = maxAlternatives;
    mMaxWordLength = maxWordLength;
    mMaxWords = maxWords;
    mWords = 0;

    getWordsRec(0, 0, mInputLength * 3, false, 1, 0);

    //if (DEBUG_DICT) LOGI("Returning %d words", mWords);
    return mWords;
}

unsigned short
Dictionary::getChar(int *pos)
{
    unsigned short ch = (unsigned short) (mDict[(*pos)++] & 0xFF);
    // If the code is 255, then actual 16 bit code follows (in big endian)
    if (ch == 0xFF) {
        ch = ((mDict[*pos] & 0xFF) << 8) | (mDict[*pos + 1] & 0xFF);
        (*pos) += 2;
    } else {
    	ch = ISO_TO_UNICODE[ch];
    }
    return ch;
}

int
Dictionary::getAddress(int *pos)
{
    int address = 0;
    if ((mDict[*pos] & FLAG_ADDRESS_MASK) == 0) {
        *pos += 1;
    } else {
        address += (mDict[*pos] & (ADDRESS_MASK >> 16)) << 16;
        address += (mDict[*pos + 1] & 0xFF) << 8;
        address += (mDict[*pos + 2] & 0xFF);
        *pos += 3;
    }
    return address;
}

int
Dictionary::wideStrLen(unsigned short *str)
{
    if (!str) return 0;
    unsigned short *end = str;
    while (*end)
        end++;
    return end - str;
}

bool
Dictionary::addWord(unsigned short *word, int length, int frequency)
{
    word[length] = 0;
    //if (DEBUG_DICT) LOGI("Found word = %s, freq = %d : \n", word, frequency);

    // Find the right insertion point
    int insertAt = 0;
    while (insertAt < mMaxWords) {
        if (frequency > mFrequencies[insertAt]
                 || (mFrequencies[insertAt] == frequency
                     && length < wideStrLen(mOutputChars + insertAt * mMaxWordLength))) {
            break;
        }
        insertAt++;
    }
    if (insertAt < mMaxWords) {
        memmove((char*) mFrequencies + (insertAt + 1) * sizeof(mFrequencies[0]),
               (char*) mFrequencies + insertAt * sizeof(mFrequencies[0]),
               (mMaxWords - insertAt - 1) * sizeof(mFrequencies[0]));
        mFrequencies[insertAt] = frequency;
        memmove((char*) mOutputChars + (insertAt + 1) * mMaxWordLength * sizeof(short),
               (char*) mOutputChars + (insertAt    ) * mMaxWordLength * sizeof(short),
               (mMaxWords - insertAt - 1) * sizeof(short) * mMaxWordLength);
        unsigned short *dest = mOutputChars + (insertAt    ) * mMaxWordLength;
        while (length--) {
            *dest++ = *word++;
        }
        *dest = 0; // NULL terminate
        // Update the word count
        if (insertAt + 1 > mWords) mWords = insertAt + 1;
        //if (DEBUG_DICT) LOGI("Added word at %d\n", insertAt);
        return true;
    }
    return false;
}

unsigned short
Dictionary::toLowerCase(unsigned short c, const int depth) {
    if (c < sizeof(BASE_CHARS) / sizeof(BASE_CHARS[0])) {
        c = BASE_CHARS[c];
    }
    if (depth == 0) {
        if (c >='A' && c <= 'Z') {
            c |= 32;
        } else if (c > 127 && c < sizeof(LOWER_CHARS) / sizeof(LOWER_CHARS[0])) {
            c = LOWER_CHARS[c];
        }
    }
    return c;
}

bool
Dictionary::sameAsTyped(unsigned short *word, int length)
{
    if (length != mInputLength) {
        return false;
    }
    int *inputCodes = mInputCodes;
    while (length--) {
        if ((unsigned int) *inputCodes != (unsigned int) *word) {
            return false;
        }
        inputCodes += mMaxAlternatives;
        word++;
    }
    return true;
}

static char QUOTE = '\'';

void
Dictionary::getWordsRec(int pos, int depth, int maxDepth, bool completion, int snr, int inputIndex)
{
    // Optimization: Prune out words that are too long compared to how much was typed.
    if (depth > maxDepth) {
        return;
    }
    int count = getCount(&pos);
    int *currentChars = NULL;
    if (mInputLength <= inputIndex) {
        completion = true;
    } else {
        currentChars = mInputCodes + (inputIndex * mMaxAlternatives);
    }

    for (int i = 0; i < count; i++) {
        unsigned short c = getChar(&pos);
        unsigned short lowerC = toLowerCase(c, depth);
        bool terminal = getTerminal(&pos);
        int childrenAddress = getAddress(&pos);
        int freq = 1;
        if (terminal) freq = getFreq(&pos);
        // If we are only doing completions, no need to look at the typed characters.
        if (completion) {
            mWord[depth] = c;
            if (terminal) {
                addWord(mWord, depth + 1, freq * snr);
            }
            if (childrenAddress != 0) {
                getWordsRec(childrenAddress, depth + 1, maxDepth,
                            completion, snr, inputIndex);
            }
        } else if (c == QUOTE && currentChars[0] != QUOTE) {
            // Skip the ' and continue deeper
            mWord[depth] = QUOTE;
            if (childrenAddress != 0) {
                getWordsRec(childrenAddress, depth + 1, maxDepth, false, snr, inputIndex);
            }
        } else {
            int j = 0;
            while (currentChars[j] > 0) {
                int addedWeight = j == 0 ? mTypedLetterMultiplier : 1;
                if (currentChars[j] == lowerC || currentChars[j] == c) {
                    mWord[depth] = c;
                    if (mInputLength == inputIndex + 1) {
                        if (terminal) {
                            if (//INCLUDE_TYPED_WORD_IF_VALID ||
                                !sameAsTyped(mWord, depth + 1)) {
                                addWord(mWord, depth + 1,
                                    (freq * snr * addedWeight * mFullWordMultiplier));
                            }
                        }
                        if (childrenAddress != 0) {
                            getWordsRec(childrenAddress, depth + 1,
                                    maxDepth, true, snr * addedWeight, inputIndex + 1);
                        }
                    } else if (childrenAddress != 0) {
                        getWordsRec(childrenAddress, depth + 1, maxDepth,
                                false, snr * addedWeight, inputIndex + 1);
                    }
                }
                j++;
            }
        }
    }
}

bool
Dictionary::isValidWord(unsigned short *word, int length)
{
    return isValidWordRec(0, word, 0, length);
}

bool
Dictionary::isValidWordRec(int pos, unsigned short *word, int offset, int length) {
    int count = getCount(&pos);
    unsigned short currentChar = (unsigned short) word[offset];
    for (int j = 0; j < count; j++) {
        unsigned short c = getChar(&pos);
        int terminal = getTerminal(&pos);
        int childPos = getAddress(&pos);
        if (c == currentChar) {
            if (offset == length - 1) {
                if (terminal) {
                    return true;
                }
            } else {
                if (childPos != 0) {
                    if (isValidWordRec(childPos, word, offset + 1, length)) {
                        return true;
                    }
                }
            }
        }
        if (terminal) {
            getFreq(&pos);
        }
        // There could be two instances of each alphabet - upper and lower case. So continue
        // looking ...
    }
    return false;
}


} // namespace greekim
