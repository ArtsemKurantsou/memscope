package dev.memscope.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Runs before Application.onCreate; plants a 5 MB static blob. */
public class InitProvider extends ContentProvider {
    public static byte[] providerBlob;

    @Override public boolean onCreate() { providerBlob = Fill.bytes(5 * 1024 * 1024); return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
