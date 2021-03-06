/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * the License
 */
package com.android.providers.contacts;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import com.android.providers.contacts.ContactsDatabaseHelper.PhoneLookupColumns;
import com.android.providers.contacts.ContactsDatabaseHelper.Tables;
import com.android.providers.contacts.SearchIndexManager.IndexBuilder;
import com.android.providers.contacts.aggregation.AbstractContactAggregator;
import android.os.SystemProperties;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import android.util.Log;
import com.sprd.providers.contacts.ContactProxyManager;
import com.sprd.providers.contacts.SimContactProxyUtil;
import com.sprd.providers.util.Features;

/**
 * Handler for phone number data rows.
 */
public class DataRowHandlerForPhoneNumber extends DataRowHandlerForCommonDataKind {

    private static final String TAG = "DataRowHandlerForPhoneNumber";

    public DataRowHandlerForPhoneNumber(Context context,
            ContactsDatabaseHelper dbHelper, AbstractContactAggregator aggregator) {
        super(context, dbHelper, aggregator, Phone.CONTENT_ITEM_TYPE, Phone.TYPE, Phone.LABEL);
    }

    @Override
    public long insert(SQLiteDatabase db, TransactionContext txContext, long rawContactId,
            ContentValues values) {
        fillNormalizedNumber(values);

        final long dataId = super.insert(db, txContext, rawContactId, values);
        if (values.containsKey(Phone.NUMBER)) {
            final String number = values.getAsString(Phone.NUMBER);
            final String normalizedNumber = values.getAsString(Phone.NORMALIZED_NUMBER);
            /**
             * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
             *
             * @{
             */
            ContentValues tmp = new ContentValues(values);
            tmp.put(Phone.NUMBER, number);
            ContactProxyManager contactProxyManager = ContactProxyManager.getInstance(mContext);
            SimContactProxyUtil simProxyUtil = SimContactProxyUtil.getInstance(contactProxyManager, mContext);
            if (simProxyUtil.isMyContact(rawContactId)) {
                contactProxyManager.onDataUpdate(rawContactId, tmp, Phone.CONTENT_ITEM_TYPE);
            }
            /**
             * @}
             */
            updatePhoneLookup(db, rawContactId, dataId, number, normalizedNumber);
            mContactAggregator.updateHasPhoneNumber(db, rawContactId);
            fixRawContactDisplayName(db, txContext, rawContactId);

            triggerAggregation(txContext, rawContactId);
        }
        return dataId;
    }

    @Override
    public boolean update(SQLiteDatabase db, TransactionContext txContext, ContentValues values,
            Cursor c, boolean callerIsSyncAdapter, boolean callerIsMetadataSyncAdapter) {
        fillNormalizedNumber(values);

        /**
         * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
         *
         * @{
         */
        String origNum = c.getString(DataUpdateQuery.DATA1);
        int type = c.getInt(DataUpdateQuery.DATA2);
        /**
         * @}
         */
        if (!super.update(db, txContext, values, c, callerIsSyncAdapter, callerIsMetadataSyncAdapter)) {
            return false;
        }

        /**
         * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
         *
         * @{
         */
        long rawContactId = c.getLong(DataUpdateQuery.RAW_CONTACT_ID);
        /**
        * @}
        */

         ContactProxyManager contactProxyManager = ContactProxyManager.getInstance(mContext);
         SimContactProxyUtil simProxyUtil = SimContactProxyUtil.getInstance(contactProxyManager, mContext);

        if (values.containsKey(Phone.NUMBER)) {
            long dataId = c.getLong(DataUpdateQuery._ID);
            /**
             * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
             *
             * Original Android code:
             *
            long rawContactId = c.getLong(DataUpdateQuery.RAW_CONTACT_ID);
             *
             */
            updatePhoneLookup(db, rawContactId, dataId,
                    values.getAsString(Phone.NUMBER),
                    values.getAsString(Phone.NORMALIZED_NUMBER));
            mContactAggregator.updateHasPhoneNumber(db, rawContactId);
            fixRawContactDisplayName(db, txContext, rawContactId);

            triggerAggregation(txContext, rawContactId);
            /**
             * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
             *       make sure updating the data, to avoid the case like updating is_primary
             * @{
             */
            ContentValues tmp = new ContentValues(values);
            tmp.put(Phone.NUMBER + "_orig", origNum);
            tmp.put("data2", type);
            if (simProxyUtil.isMyContact(rawContactId)) {
                contactProxyManager.onDataUpdate(rawContactId, tmp, Phone.CONTENT_ITEM_TYPE);
            }
            /**
             * @}
             */
        }

        /**
         * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
         * SPRD: bug 506910
         * @{
         */
        else if(values.containsKey(Phone.TYPE) && !values.containsKey(Phone.NUMBER)){
        ContentValues tmp = new ContentValues(values);
        tmp.put(Phone.NUMBER + "_orig", origNum);
        tmp.put(Phone.NUMBER, origNum);
            if (simProxyUtil.isMyContact(rawContactId)) {
            contactProxyManager.onDataUpdate(rawContactId, tmp, Phone.CONTENT_ITEM_TYPE);
            }
        }
        /**
         * @}
         */
        return true;
    }

    private void fillNormalizedNumber(ContentValues values) {
        // No NUMBER? Also ignore NORMALIZED_NUMBER
        if (!values.containsKey(Phone.NUMBER)) {
            values.remove(Phone.NORMALIZED_NUMBER);
            return;
        }

        // NUMBER is given. Try to extract NORMALIZED_NUMBER from it, unless it is also given
        final String number = values.getAsString(Phone.NUMBER);
        final String numberE164 = values.getAsString(Phone.NORMALIZED_NUMBER);
        if (number != null && numberE164 == null) {
            final String newNumberE164 = PhoneNumberUtils.formatNumberToE164(number,
                    mDbHelper.getCurrentCountryIso());
            values.put(Phone.NORMALIZED_NUMBER, newNumberE164);
        }
    }

    @Override
    public int delete(SQLiteDatabase db, TransactionContext txContext, Cursor c) {
        long dataId = c.getLong(DataDeleteQuery._ID);
        long rawContactId = c.getLong(DataDeleteQuery.RAW_CONTACT_ID);

        int count = super.delete(db, txContext, c);

        /**
         * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
         *
         * @{
         */
        ContentValues tmp = new ContentValues();
        tmp.put(Phone.NUMBER + "_orig", c.getString(DataDeleteQuery.DATA1));
        tmp.put("data2", c.getString(DataDeleteQuery.DATA2));

        ContactProxyManager contactProxyManager = ContactProxyManager.getInstance(mContext);
        SimContactProxyUtil simProxyUtil = SimContactProxyUtil.getInstance(contactProxyManager, mContext);
        if (simProxyUtil.isMyContact(rawContactId)) {
            contactProxyManager.onDataUpdate(rawContactId, tmp, Phone.CONTENT_ITEM_TYPE);
        }
        /**
        * @}
        */

        updatePhoneLookup(db, rawContactId, dataId, null, null);
        mContactAggregator.updateHasPhoneNumber(db, rawContactId);
        fixRawContactDisplayName(db, txContext, rawContactId);
        triggerAggregation(txContext, rawContactId);
        return count;
    }

    private void updatePhoneLookup(SQLiteDatabase db, long rawContactId, long dataId,
            String number, String numberE164) {
        mSelectionArgs1[0] = String.valueOf(dataId);
        db.delete(Tables.PHONE_LOOKUP, PhoneLookupColumns.DATA_ID + "=?", mSelectionArgs1);
        if (number != null) {
            String normalizedNumber = PhoneNumberUtils.normalizeNumber(number);
            if (!TextUtils.isEmpty(normalizedNumber)) {
                ContentValues phoneValues = new ContentValues();
                phoneValues.put(PhoneLookupColumns.RAW_CONTACT_ID, rawContactId);
                phoneValues.put(PhoneLookupColumns.DATA_ID, dataId);
                phoneValues.put(PhoneLookupColumns.NORMALIZED_NUMBER, normalizedNumber);
                phoneValues.put(PhoneLookupColumns.MIN_MATCH,
                        PhoneNumberUtils.toCallerIDMinMatch(normalizedNumber));
                db.insert(Tables.PHONE_LOOKUP, null, phoneValues);

                if (numberE164 != null && !numberE164.equals(normalizedNumber)) {
                    phoneValues.put(PhoneLookupColumns.NORMALIZED_NUMBER, numberE164);
                    phoneValues.put(PhoneLookupColumns.MIN_MATCH,
                            PhoneNumberUtils.toCallerIDMinMatch(numberE164));
                    db.insert(Tables.PHONE_LOOKUP, null, phoneValues);
                }
            }
        }
    }

    @Override
    protected int getTypeRank(int type) {
        switch (type) {
            case Phone.TYPE_MOBILE: return 0;
            case Phone.TYPE_WORK: return 1;
            case Phone.TYPE_HOME: return 2;
            case Phone.TYPE_PAGER: return 3;
            case Phone.TYPE_CUSTOM: return 4;
            case Phone.TYPE_OTHER: return 5;
            case Phone.TYPE_FAX_WORK: return 6;
            case Phone.TYPE_FAX_HOME: return 7;
            default: return 1000;
        }
    }

    @Override
    public boolean containsSearchableColumns(ContentValues values) {
        return values.containsKey(Phone.NUMBER);
    }

    @Override
    public void appendSearchableData(IndexBuilder builder) {
        String number = builder.getString(Phone.NUMBER);
        if (TextUtils.isEmpty(number)) {
            return;
        }

        String normalizedNumber = PhoneNumberUtils.normalizeNumber(number);
        if (TextUtils.isEmpty(normalizedNumber)) {
            return;
        }

        builder.appendToken(normalizedNumber);
        /**
         * SPRD: add for bug 474305,693202 contacts segment search feature
         * @{
         */
        if (Features.supportSegmentSearchFeature(mContext)){
            appendTokenForSegmentSearch(normalizedNumber, builder);
        }
        /**
         * @}
         */
        String numberE164 = PhoneNumberUtils.formatNumberToE164(
                number, mDbHelper.getCurrentCountryIso());
        if (numberE164 != null && !numberE164.equals(normalizedNumber)) {
            builder.appendToken(numberE164);
        }
    }

    private void appendTokenForSegmentSearch(String normalizedNumber, IndexBuilder builder) {
        Log.d(TAG, "appendTokenForSegmentSearch normalizedNumber: ");
        int N = normalizedNumber.length();
        for (int i = 1; i < N; ++i) {
            builder.appendToken(normalizedNumber.substring(i,
                    Math.min(i + MAX_SEARCH_SNIPPET_SIZE, N)));
        }
    }
}
