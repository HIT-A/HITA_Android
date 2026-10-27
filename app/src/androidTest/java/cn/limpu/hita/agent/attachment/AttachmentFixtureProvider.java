package cn.limpu.hita.agent.attachment;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** A synthetic test APK provider: reported size is deliberately different from streamed content. */
public final class AttachmentFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "text/plain"; }

    @Override public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{
                "download".equals(uri.getLastPathSegment()) ? "课程.PDF.download.MHT" : "notes.txt",
                0L,
        });
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("fixture provider is not attached");
        try {
            File file = File.createTempFile("chat-provider-fixture-", ".txt", getContext().getCacheDir());
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write("hello local fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            } finally {
                // The descriptor remains usable after the directory entry is removed on Android/Linux.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only fixture"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only fixture"); }
}
