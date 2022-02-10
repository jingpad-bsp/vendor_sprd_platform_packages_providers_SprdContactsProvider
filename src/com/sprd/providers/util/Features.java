package com.sprd.providers.util;

import android.content.Context;
import com.android.providers.contacts.R;

//for feature control
public class Features{

    public static final boolean supportDefaultContactsFeature(Context context){
        //remove /data/user/0/com.android.providers.contacts/databases/*.db and reboot to check
        return context.getResources().getBoolean(R.bool.is_support_default_contacts);
    }
    public static final boolean supportSegmentSearchFeature(Context context){
        //UNISOC: modify for bug1057775, delete operator information
        return context.getResources().getBoolean(R.bool.is_support_segment_search);
    }
}
