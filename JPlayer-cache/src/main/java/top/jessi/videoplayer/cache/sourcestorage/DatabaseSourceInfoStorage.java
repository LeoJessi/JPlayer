package top.jessi.videoplayer.cache.sourcestorage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import static top.jessi.videoplayer.cache.internal.Preconditions.checkAllNotNull;
import static top.jessi.videoplayer.cache.internal.Preconditions.checkNotNull;

/**
 * Database based {@link SourceInfoStorage}.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class DatabaseSourceInfoStorage extends SQLiteOpenHelper implements SourceInfoStorage {

    private static final String TABLE = "SourceInfo";
    private static final String COLUMN_ID = "_id";
    private static final String COLUMN_URL = "url";
    private static final String COLUMN_LENGTH = "length";
    private static final String COLUMN_MIME = "mime";
    private static final String URL_INDEX = "index_SourceInfo_url";
    private static final String[] ALL_COLUMNS = new String[]{COLUMN_ID, COLUMN_URL, COLUMN_LENGTH, COLUMN_MIME};
    private static final String CREATE_SQL =
            "CREATE TABLE " + TABLE + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                    COLUMN_URL + " TEXT NOT NULL," +
                    COLUMN_MIME + " TEXT," +
                    COLUMN_LENGTH + " INTEGER" +
                    ");";

    DatabaseSourceInfoStorage(Context context) {
        super(context, "AndroidVideoCache.db", null, 2);
        checkNotNull(context);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        checkNotNull(db);
        db.execSQL(CREATE_SQL);
        createUrlIndex(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // 旧版本没有 URL 唯一约束；保留每个 URL 最近一次写入的元数据。
            db.execSQL("DELETE FROM " + TABLE + " WHERE " + COLUMN_ID + " NOT IN (SELECT MAX("
                    + COLUMN_ID + ") FROM " + TABLE + " GROUP BY " + COLUMN_URL + ")");
            createUrlIndex(db);
        }
    }

    @Override
    public SourceInfo get(String url) {
        checkNotNull(url);
        try (Cursor cursor = getReadableDatabase().query(TABLE, ALL_COLUMNS, COLUMN_URL + "=?", new String[]{url}, null, null, null)) {
            return cursor == null || !cursor.moveToFirst() ? null : convert(cursor);
        }
    }

    @Override
    public void put(String url, SourceInfo sourceInfo) {
        checkAllNotNull(url, sourceInfo);
        ContentValues contentValues = convert(sourceInfo);
        contentValues.put(COLUMN_URL, url);
        getWritableDatabase().insertWithOnConflict(
                TABLE, null, contentValues, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void release() {
        close();
    }

    private SourceInfo convert(Cursor cursor) {
        return new SourceInfo(
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_URL)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LENGTH)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MIME))
        );
    }

    private ContentValues convert(SourceInfo sourceInfo) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_URL, sourceInfo.url);
        values.put(COLUMN_LENGTH, sourceInfo.length);
        values.put(COLUMN_MIME, sourceInfo.mime);
        return values;
    }

    private void createUrlIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS " + URL_INDEX
                + " ON " + TABLE + "(" + COLUMN_URL + ")");
    }
}
