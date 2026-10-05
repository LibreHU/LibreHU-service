package org.librehu.service.bt

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import android.util.LruCache

/**
 * Phone book and call history. The Bluetooth app's PBAP client downloads them into Android's contacts (account type
 * `com.android.bluetooth.pbapsink`) and call log when the phone connects; this class only reads them, like Jancar's
 * btservice (`ECardUtil`). Needs READ_CONTACTS and READ_CALL_LOG (granted at install, see docs/bluetooth.md).
 */
internal class BtPhonebook(
    private val context: Context,
    main: Handler,
    private val onChanged: () -> Unit,
) {
    private val names = LruCache<String, String>(200)

    private val observer =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                names.evictAll()
                onChanged()
            }
        }

    fun start() {
        try {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        } catch (e: SecurityException) {
            Log.w(TAG, "Phone book not readable: ${e.message}")
        }
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(observer)
    }

    fun contactCount(): Int = query(PHONES, arrayOf(ContactsContract.CommonDataKinds.Phone._ID), null, null, null) { it.count } ?: 0

    fun contacts(
        offset: Int,
        limit: Int,
    ): List<BtContact> =
        query(PHONES, PHONE_COLUMNS, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC") { c ->
            val out = ArrayList<BtContact>()
            if (c.moveToPosition(offset.coerceAtLeast(0))) {
                do {
                    out += BtContact(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2))
                } while (out.size < limit.coerceIn(0, MAX_PAGE) && c.moveToNext())
            }
            out
        } ?: emptyList()

    fun search(
        text: String,
        limit: Int,
    ): List<BtContact> {
        if (text.isBlank()) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(text.trim()))
        return query(uri, PHONE_COLUMNS, null, null, null) { c ->
            val out = ArrayList<BtContact>()
            while (out.size < limit.coerceIn(0, MAX_PAGE) && c.moveToNext()) {
                out += BtContact(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2))
            }
            out
        } ?: emptyList()
    }

    /** [type]: 0 all, else `CallLog.Calls.TYPE` (1 incoming, 2 outgoing, 3 missed). */
    fun callLog(
        type: Int,
        limit: Int,
    ): List<BtContact> {
        val selection = if (type > 0) "${CallLog.Calls.TYPE} = ?" else null
        val args = if (type > 0) arrayOf(type.toString()) else null
        val columns = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
        return query(CallLog.Calls.CONTENT_URI, columns, selection, args, "${CallLog.Calls.DATE} DESC") { c ->
            val out = ArrayList<BtContact>()
            while (out.size < limit.coerceIn(0, MAX_PAGE) && c.moveToNext()) {
                val number = c.getString(1).orEmpty()
                val name = c.getString(0).orEmpty().ifEmpty { lookupName(number) }
                out += BtContact(name, number, c.getInt(2), c.getLong(3))
            }
            out
        } ?: emptyList()
    }

    /** Last number dialed from the car or the phone (outgoing call log). */
    fun lastDialed(): String? = callLog(CallLog.Calls.OUTGOING_TYPE, 1).firstOrNull()?.number

    fun lookupName(number: String): String {
        if (number.isBlank()) return ""
        names.get(number)?.let { return it }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name =
            query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null) { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            } ?: ""
        names.put(number, name)
        return name
    }

    private fun <T> query(
        uri: Uri,
        columns: Array<String>,
        selection: String?,
        args: Array<String>?,
        order: String?,
        read: (android.database.Cursor) -> T,
    ): T? =
        try {
            context.contentResolver.query(uri, columns, selection, args, order)?.use(read)
        } catch (e: Exception) {
            Log.w(TAG, "Query $uri: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "LibreHU-BT"
        const val MAX_PAGE = 500
        val PHONES: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val PHONE_COLUMNS =
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )
    }
}
