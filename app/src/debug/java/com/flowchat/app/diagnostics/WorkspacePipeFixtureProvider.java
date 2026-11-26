package com.flowchat.app.diagnostics;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Dev-only deterministic documents for pipe/cancellation diagnostics. Never opens user files. */
public final class WorkspacePipeFixtureProvider extends DocumentsProvider {
    private volatile long generation = 1;
    private volatile boolean permitted = true;
    private volatile boolean changeOnOpen;
    private volatile boolean slow;
    private final byte[] tail = "中文末尾".getBytes(StandardCharsets.UTF_8);
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};

    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{"root_id"} : projection);
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        if (!permitted) throw new SecurityException("Fixture permission revoked");
        if (!id.equals("root") && !id.equals("stream")) throw new IllegalArgumentException("Unknown fixture document");
        boolean root = id.equals("root");
        String[] columns = projection == null ? COLUMNS : projection;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: row[i] = root ? "pipe-root" : "stream.txt"; break;
                case Document.COLUMN_MIME_TYPE: row[i] = root ? Document.MIME_TYPE_DIR : "text/plain"; break;
                case Document.COLUMN_SIZE: row[i] = root ? 0L : 200_000L + tail.length; break;
                case Document.COLUMN_LAST_MODIFIED: row[i] = generation; break;
                case Document.COLUMN_FLAGS: row[i] = 0; break;
                default: row[i] = null;
            }
        }
        MatrixCursor result = new MatrixCursor(columns);
        result.addRow(row);
        return result;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) {
        return queryDocument("stream", projection);
    }
    @Override public boolean isChildDocument(String parent, String child) {
        return parent.equals("root") && child.equals("stream");
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!permitted) throw new SecurityException("Fixture permission revoked");
        if (!id.equals("stream") || !mode.equals("r")) throw new FileNotFoundException("Read-only fixture");
        if (changeOnOpen) generation++;
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException failure) { throw new FileNotFoundException(failure.toString()); }
        final boolean slowed = slow;
        Thread writer = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                byte[] bytes = new byte[4096]; Arrays.fill(bytes, (byte) 'x');
                int count = 0;
                while (count < 200_000) {
                    int n = Math.min(bytes.length, 200_000 - count);
                    output.write(bytes, 0, n); count += n;
                    if (slowed) Thread.sleep(4);
                }
                output.write(tail);
            } catch (IOException | InterruptedException expectedClosedPipe) {
                // A range reader closes its source early or is cancelled.
            }
        }, "p0c-fixture-pipe");
        writer.setDaemon(true); writer.start();
        return pipe[0];
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.equals("p0c_configure")) return super.call(method, arg, extras);
        generation++;
        permitted = extras == null || extras.getBoolean("permitted", true);
        changeOnOpen = extras != null && extras.getBoolean("change", false);
        slow = extras != null && extras.getBoolean("slow", false);
        Bundle result = new Bundle(); result.putLong("generation", generation); return result;
    }
}
