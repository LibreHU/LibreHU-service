package org.librehu.jacsettings;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.util.Log;

/**
 * content://com.jancar.settings.provider/settings, as Jancar's settings app provides it: one table {@code settings}
 * ({@code _id}, {@code name}, {@code value}; names such as {@code global_brake} or {@code global_defaultnavipackage},
 * schema read on the head unit, LibreHU-service docs/ivi-services/10-config.md). It starts empty: the readers (the
 * stock SystemUI's SettingModel) get no value and keep their defaults, what a patched SystemUI does without the
 * provider, and their change observers can register. Anyone reads; only the system, root and apps signed with the
 * LibreHU key write.
 */
public final class JancarSettingsProvider extends ContentProvider {
    private static final String TAG = "LibreHU-JacSettings";
    private static final String TABLE = "settings";
    private static final Uri BASE = Uri.parse("content://com.jancar.settings.provider/" + TABLE);

    private SQLiteOpenHelper helper;

    @Override
    public boolean onCreate() {
        // Device protected storage: readable before the user is unlocked (direct boot), when SystemUI starts.
        Context storage = getContext().createDeviceProtectedStorageContext();
        helper = new SQLiteOpenHelper(storage, "settings.db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE " + TABLE
                        + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE ON CONFLICT REPLACE, value TEXT)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            }
        };
        return true;
    }

    /** content://…/settings/<name> stands for the row of that name. */
    private static String selection(Uri uri, String selection) {
        if (uri.getPathSegments().size() < 2) return selection;
        return selection == null || selection.isEmpty() ? "name=?" : "(" + selection + ") AND name=?";
    }

    private static String[] arguments(Uri uri, String[] args) {
        if (uri.getPathSegments().size() < 2) return args;
        String name = uri.getPathSegments().get(1);
        if (args == null) return new String[] {name};
        String[] out = new String[args.length + 1];
        System.arraycopy(args, 0, out, 0, args.length);
        out[args.length] = name;
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        Cursor c = helper.getReadableDatabase()
                .query(TABLE, projection, selection(uri, selection), arguments(uri, args), null, null, sortOrder);
        c.setNotificationUri(getContext().getContentResolver(), BASE);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return uri.getPathSegments().size() < 2 ? "vnd.android.cursor.dir/" + TABLE : "vnd.android.cursor.item/" + TABLE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        checkWrite();
        long id = helper.getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        changed(uri);
        return id < 0 ? null : ContentUris.withAppendedId(BASE, id);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        checkWrite();
        int n = helper.getWritableDatabase().update(TABLE, values, selection(uri, selection), arguments(uri, args));
        if (n > 0) changed(uri);
        return n;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        checkWrite();
        int n = helper.getWritableDatabase().delete(TABLE, selection(uri, selection), arguments(uri, args));
        if (n > 0) changed(uri);
        return n;
    }

    private void changed(Uri uri) {
        getContext().getContentResolver().notifyChange(BASE, null);
        if (!BASE.equals(uri)) getContext().getContentResolver().notifyChange(uri, null);
    }

    private void checkWrite() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) return;
        if (getContext().getPackageManager().checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH) return;
        Log.w(TAG, "write refused to uid " + uid);
        throw new SecurityException("com.jancar.settings.provider: writes are kept to the system and LibreHU apps");
    }
}
