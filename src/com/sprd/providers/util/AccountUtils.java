
package com.sprd.providers.util;

import android.accounts.Account;
import com.android.providers.contacts.AccountWithDataSet;

/**
 * SPRD: add for bug693208, AndroidO porting for contacts to add Phone and SIM account
 *
 * @{
 */
public class AccountUtils {
    public static Account getAccount(AccountWithDataSet accountWithDataSet) {
        if (accountWithDataSet == null) {
            return null;
        }
        String accountName = accountWithDataSet.getAccountName();
        String accountType = accountWithDataSet.getAccountType();
        if (accountName == null || accountType == null) {
            return null;
        }
        return new Account(accountName, accountType);
    }
}
/**
 * @}
 */
