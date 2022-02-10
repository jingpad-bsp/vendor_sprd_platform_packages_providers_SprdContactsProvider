/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.contacts;

import android.icu.text.Transliterator;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

import com.sprd.providers.util.PolyPinyinUtility;

/**
 * An object to convert Chinese character to its corresponding pinyin string.
 * For characters with multiple possible pinyin string, only one is selected
 * according to ICU Transliterator class. Polyphone is not supported in this
 * implementation.
 */
public class HanziToPinyin {
    private static final String TAG = "HanziToPinyin";
    private static final int POLY_MAX = 6;

    private static HanziToPinyin sInstance;
    private Transliterator mPinyinTransliterator;
    private Transliterator mAsciiTransliterator;

    public static class Token {
        /**
         * Separator between target string for each source char
         */
        public static final String SEPARATOR = " ";

        public static final int LATIN = 1;
        public static final int PINYIN = 2;
        public static final int UNKNOWN = 3;

        //SPRD: add for bug 556233,693227 polyphone feature for Chinese
        public static final int Cyrillic = 4;

        public Token() {
        }

        public Token(int type, String source, String target) {
            this.type = type;
            this.source = source;
            this.target = target;
        }

        /**
         * Type of this token, ASCII, PINYIN or UNKNOWN.
         */
        public int type;
        /**
         * Original string before translation.
         */
        public String source;
        /**
         * Translated string of source. For Han, target is corresponding Pinyin. Otherwise target is
         * original string in source.
         */
        public String target;
    }

    private HanziToPinyin() {
        try {
            mPinyinTransliterator = Transliterator.getInstance(
                    "Han-Latin/Names; Latin-Ascii; Any-Upper");
            mAsciiTransliterator = Transliterator.getInstance("Latin-Ascii");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Han-Latin/Names transliterator data is missing,"
                  + " HanziToPinyin is disabled");
        }
    }

    public boolean hasChineseTransliterator() {
        return mPinyinTransliterator != null;
    }

    public static HanziToPinyin getInstance() {
        synchronized (HanziToPinyin.class) {
            if (sInstance == null) {
                sInstance = new HanziToPinyin();
            }
            return sInstance;
        }
    }

    private void tokenize(char character, Token token) {
        token.source = Character.toString(character);

        // ASCII
        if (character < 128) {
            token.type = Token.LATIN;
            token.target = token.source;
            return;
        }

        /* SPRD: get token nize for bug693095 @{ */
        else if ((character >= 1024) && (character <= 1279)) {// Russian、Ukrainian
            Log.i(TAG, "Token.Cyrillic(Russian/Ukrainian)");
            token.type = Token.Cyrillic;
            token.target = token.source;
            return;
        }
        /* @} */
        // Extended Latin. Transcode these to ASCII equivalents
        if (character < 0x250 || (0x1e00 <= character && character < 0x1eff)) {
            token.type = Token.LATIN;
            token.target = mAsciiTransliterator == null ? token.source :
                mAsciiTransliterator.transliterate(token.source);
            return;
        }

        token.type = Token.PINYIN;
        token.target = mPinyinTransliterator.transliterate(token.source);
        if (TextUtils.isEmpty(token.target) ||
            TextUtils.equals(token.source, token.target)) {
            token.type = Token.UNKNOWN;
            token.target = token.source;
        }
    }

    public String transliterate(final String input) {
        if (!hasChineseTransliterator() || TextUtils.isEmpty(input)) {
            return null;
        }
        return mPinyinTransliterator.transliterate(input);
    }

    /**
     * Convert the input to a array of tokens. The sequence of ASCII or Unknown characters without
     * space will be put into a Token, One Hanzi character which has pinyin will be treated as a
     * Token. If there is no Chinese transliterator, the empty token array is returned.
     */
    public ArrayList<Token> getTokens(final String input) {
        ArrayList<Token> tokens = new ArrayList<Token>();
        if (!hasChineseTransliterator() || TextUtils.isEmpty(input)) {
            // return empty tokens.
            return tokens;
        }

        final int inputLength = input.length();
        final StringBuilder sb = new StringBuilder();
        int tokenType = Token.LATIN;
        Token token = new Token();

        // Go through the input, create a new token when
        // a. Token type changed
        // b. Get the Pinyin of current charater.
        // c. current character is space.
        for (int i = 0; i < inputLength; i++) {
            final char character = input.charAt(i);
            if (Character.isSpaceChar(character)) {
                if (sb.length() > 0) {
                    addToken(sb, tokens, tokenType);
                }
            } else {
                tokenize(character, token);
                if (token.type == Token.PINYIN) {
                    if (sb.length() > 0) {
                        addToken(sb, tokens, tokenType);
                    }
                    tokens.add(token);
                    token = new Token();
                } else {
                    if (tokenType != token.type && sb.length() > 0) {
                        addToken(sb, tokens, tokenType);
                    }
                    sb.append(token.target);
                }
                tokenType = token.type;
            }
        }
        if (sb.length() > 0) {
            addToken(sb, tokens, tokenType);
        }
        return tokens;
    }

    /**
     * SPRD: add for bug 556233,693227 polyphone feature for Chinese
     * @{
     */
    public ArrayList<ArrayList<Token>> getAllTokenLists(final String input) {
        ArrayList<ArrayList<Token>> result = new ArrayList<ArrayList<Token>>();
        if (!hasChineseTransliterator() || TextUtils.isEmpty(input)) {
            // return empty tokens.
            return result;
        }
        ArrayList<ArrayList<Token>> TokensForCharacter = new ArrayList<ArrayList<Token>>();
        ArrayList<Token> tokens = new ArrayList<Token>();
        int totalTokenListCount = 1;
        /*SPRD:681303 The first letter of the English name cannot be searched @{*/
        final StringBuilder sb = new StringBuilder();
        final int inputLength = input.length();
        Token token = new Token();
        int polynum = 0;
        for (int i = 0; i < inputLength; i++) {
            final char character = input.charAt(i);
            if (Character.isSpaceChar(character)) {
                if (sb.length() > 0) {
                    tokens.add(new Token(Token.LATIN, sb.toString(), sb.toString()));
                    totalTokenListCount *= tokens.size();
                    TokensForCharacter.add(tokens);
                    tokens = new ArrayList<Token>();
                    sb.setLength(0);
                }
                continue;
            } else {
                tokenize(character, token);
            }
            if (token != null) {
                if (token.type == Token.PINYIN) {
                    //Sprd:922992, limit poly char for oom
                    if (polynum < POLY_MAX && ispolyPinyin(character)) {
                        tokens = getAllTokenByCharacter(character);
                        polynum++;
                        token = new Token();
                    } else {
                        tokens.add(token);
                        token = new Token();
                    }
                    totalTokenListCount *= tokens.size();
                    TokensForCharacter.add(tokens);
                    tokens = new ArrayList<Token>();
                } else {
                    sb.append(token.target);
                    token = new Token();
                }
            }
        }
        if (sb.length() > 0) {
           /**
            * SPRD: Bug767988 modify the token type for feature of match callLog when search in dialpad
            * @{
            */
           for(int i = 0; i < sb.length(); i++){
                final char character = sb.toString().charAt(i);
                tokenize(character, token);
            }
            tokens.add(new Token(token.type, sb.toString(), sb.toString()));
            /**
             * @}
             */
            totalTokenListCount *= tokens.size();
            TokensForCharacter.add(tokens);
            tokens = new ArrayList<Token>();
            sb.setLength(0);
        }
        /* @}*/
        try{
            result = computeTokenLists(TokensForCharacter, totalTokenListCount);
        }catch(Exception e){
            Log.e(TAG,e.toString()+ "totalcount is" + totalTokenListCount);
        }
        return result;
    }

    private ArrayList<ArrayList<Token>> computeTokenLists(ArrayList<ArrayList<Token>> TokenLists,
            int totalCount) {
        int total = totalCount;
        ArrayList<ArrayList<Token>> returnResult = new ArrayList<ArrayList<Token>>();
        for (int i = 0; i < total; i++) {
            ArrayList<Token> tempList = new ArrayList<HanziToPinyin.Token>();
            returnResult.add(tempList);
        }

        int now = 1;
        // the output number of every element in each loop
        int itemLoopNum = 1;
        // the total output number of every element
        int loopPerItem = 1;
        for (int i = 0; i < TokenLists.size(); i++) {
            ArrayList<Token> temp = TokenLists.get(i);
            now = now * temp.size();
            // the index of return token list
            int index = 0;
            int currentSize = temp.size();
            itemLoopNum = total / now;
            loopPerItem = total / (itemLoopNum * currentSize);
            int myindex = 0;
            for (int j = 0; j < temp.size(); j++) {
                for (int k = 0; k < loopPerItem; k++) {
                    if (myindex == temp.size())
                        myindex = 0;
                    for (int m = 0; m < itemLoopNum; m++) {
                        returnResult.get(index).add(temp.get(myindex));
                        index++;
                    }
                    myindex++;
                }
            }
        }
        return returnResult;
    }

    private ArrayList<Token> getAllTokenByCharacter(char character) {
        ArrayList<Token> result = new ArrayList<Token>();

        // Check Poly Pinyin Tokens
        ArrayList<String> polyPinyinList = PolyPinyinUtility.getPolyPinyins(character);

        for (String pinyin : polyPinyinList) {
            Token polyToken = new Token();
            polyToken.source = Character.toString(character);
            polyToken.target = pinyin;
            polyToken.type = Token.PINYIN;
            result.add(polyToken);
        }
        return result;
    }

    private boolean ispolyPinyin (char character) {
        ArrayList<String> polyPinyinList = PolyPinyinUtility.getPolyPinyins(character);
        return !polyPinyinList.isEmpty();
    }
    /**
     * @}
     */

    private void addToken(
            final StringBuilder sb, final ArrayList<Token> tokens, final int tokenType) {
        String str = sb.toString();
        tokens.add(new Token(tokenType, str, str));
        sb.setLength(0);
    }

}
