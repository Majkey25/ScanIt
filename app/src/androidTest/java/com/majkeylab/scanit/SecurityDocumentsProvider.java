package com.majkeylab.scanit;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Hosted separately from instrumentation, so it must not depend on Kotlin runtime classes.
public class SecurityDocumentsProvider extends DocumentsProvider {
    public static final String TARGET = "com.majkeylab.scanit.internal";
    public static final String AUTHORITY = TARGET + ".storage-security-fixture";
    public static final String ROOT = "synthetic-root";
    private static final int RW = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final class Doc {
        final String id, mime;
        final File file;
        String name, sha;
        long length;
        boolean deleted;
        CountDownLatch closed = new CountDownLatch(0);
        Doc(String id, String name, String mime, File file) {
            this.id = id; this.name = name; this.mime = mime; this.file = file;
        }
    }
    private final LinkedHashMap<String, Doc> documents = new LinkedHashMap<>();
    private File directory;
    private String mode = "Stable";
    private boolean poisoned;

    @Override public boolean onCreate() { reset(); return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith("fixture_")) return super.call(method, arg, extras);
        String caller = getCallingPackage();
        if (!TARGET.equals(caller) && !Objects.requireNonNull(getContext()).getPackageName().equals(caller))
            throw new SecurityException("Fixture caller is not an isolated test app");
        Bundle result = new Bundle();
        switch (method) {
            case "fixture_reset":
                reset(); mode = Objects.requireNonNull(arg); return result;
            case "fixture_stats":
                result.putInt("created", documents.size());
                result.putBoolean("poisoned", poisoned);
                int index = 0;
                for (Doc doc : documents.values()) {
                    rememberBytes(doc);
                    result.putString("sha" + index, doc.sha);
                    result.putLong("length" + index++, doc.length);
                }
                return result;
            case "fixture_finish":
                try { clearFiles(); }
                finally {
                    Objects.requireNonNull(getContext()).revokeUriPermission(
                        TARGET, DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT), RW
                    );
                }
                return result;
            default: throw new IllegalArgumentException("Unknown fixture call");
        }
    }

    @Override public synchronized Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? new String[]{
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES
        } : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (Root.COLUMN_ROOT_ID.equals(column) || Root.COLUMN_DOCUMENT_ID.equals(column)) value = ROOT;
            else if (Root.COLUMN_TITLE.equals(column)) value = "Synthetic storage security fixture";
            else if (Root.COLUMN_FLAGS.equals(column)) value = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD;
            else if (Root.COLUMN_MIME_TYPES.equals(column)) value = "application/pdf\nimage/jpeg";
            row.add(value);
        }
        return cursor;
    }

    private MatrixCursor documentCursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        } : projection);
    }

    @Override public synchronized Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = documentCursor(projection);
        if (ROOT.equals(id)) addRow(cursor, null);
        else {
            Doc doc = documents.get(id);
            if (doc != null && !doc.deleted) addRow(cursor, doc);
        }
        return cursor;
    }

    @Override public synchronized Cursor queryChildDocuments(String parent, String[] projection, String order) {
        if (!ROOT.equals(parent)) throw new IllegalArgumentException("Unexpected fixture root");
        MatrixCursor cursor = documentCursor(projection);
        for (Doc doc : documents.values()) if (!doc.deleted) addRow(cursor, doc);
        return cursor;
    }

    @Override public synchronized boolean isChildDocument(String parent, String id) {
        Doc doc = documents.get(id);
        if (doc == null) return false;
        rememberBytes(doc);
        return ROOT.equals(parent) && !doc.deleted &&
            !(isMode("NotChildAfterBytes") && doc.file.length() > 0);
    }

    @Override public synchronized String createDocument(String parent, String mime, String requested)
            throws FileNotFoundException {
        if (!ROOT.equals(parent) || !(mime.equals("application/pdf") || mime.equals("image/jpeg")))
            throw new FileNotFoundException("Unsupported fixture create");
        if (documents.size() >= 8) throw new IllegalStateException("Fixture document limit exceeded");
        if (documents.size() == 1 && isMode("PoisonIdentityOnSecondCreate", "PoisonBytesOnSecondCreate"))
            poisonFirst();
        int dot = requested.lastIndexOf('.');
        String stem = requested.substring(0, dot), extension = requested.substring(dot + 1);
        String name = requested;
        int collision = 0;
        while (nameExists(name)) name = stem + " (" + ++collision + ")." + extension;
        String id = "synthetic-" + (documents.size() + 1);
        File file = new File(Objects.requireNonNull(directory), id + ".bin");
        try {
            if (!file.createNewFile()) throw new IOException("Fixture output exists");
        } catch (IOException failure) { throw fileFailure(failure); }
        documents.put(id, new Doc(id, name, mime, file));
        return id;
    }

    private boolean nameExists(String name) {
        for (Doc doc : documents.values()) if (!doc.deleted && doc.name.equals(name)) return true;
        return false;
    }

    @Override public synchronized ParcelFileDescriptor openDocument(String id, String access, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        Doc doc = documents.get(id);
        if (doc == null || doc.deleted) throw new FileNotFoundException(id);
        if (access.indexOf('w') >= 0) {
            CountDownLatch closed = new CountDownLatch(1);
            doc.closed = closed;
            try {
                return ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.parseMode(access),
                    new Handler(Looper.getMainLooper()), failure -> closed.countDown());
            } catch (IOException failure) { throw fileFailure(failure); }
        }
        try {
            if (!doc.closed.await(2, TimeUnit.SECONDS)) throw new FileNotFoundException("Fixture writer did not close");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw fileFailure(failure);
        }
        rememberBytes(doc);
        if (isMode("SafeRenameAfterClose") && !doc.name.substring(0, doc.name.lastIndexOf('.')).endsWith("_uzavřeno")) {
            int dot = doc.name.lastIndexOf('.');
            doc.name = doc.name.substring(0, dot) + "_uzavřeno." + doc.name.substring(dot + 1);
        }
        if (id.equals("synthetic-2") && isMode("PoisonIdentityOnSecondRead", "PoisonBytesOnSecondRead"))
            poisonFirst();
        return ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public synchronized void deleteDocument(String id) throws FileNotFoundException {
        Doc doc = documents.get(id);
        if (doc == null) throw new FileNotFoundException(id);
        rememberBytes(doc);
        if (!doc.deleted && !doc.file.delete()) throw new FileNotFoundException("Fixture output was not removed");
        doc.deleted = true;
    }

    private void addRow(MatrixCursor cursor, Doc doc) {
        if (doc != null) rememberBytes(doc);
        boolean written = doc != null && doc.file.length() > 0;
        boolean wrongId = written && (isMode("WrongIdAfterBytes") ||
            poisoned && doc == documents.values().iterator().next() &&
                isMode("PoisonIdentityOnSecondCreate", "PoisonIdentityOnSecondRead"));
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (Document.COLUMN_DOCUMENT_ID.equals(column)) value = wrongId ? "foreign-document" : doc == null ? ROOT : doc.id;
            else if (Document.COLUMN_DISPLAY_NAME.equals(column))
                value = written && isMode("UnsafeNameAfterBytes") ? "bad\nname.jpg" : doc == null ? "Synthetic root" : doc.name;
            else if (Document.COLUMN_MIME_TYPE.equals(column))
                value = written && isMode("WrongMimeAfterBytes") ? "text/plain" : doc == null ? Document.MIME_TYPE_DIR : doc.mime;
            else if (Document.COLUMN_FLAGS.equals(column))
                value = doc == null ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE |
                    (written && isMode("NoDeleteAfterBytes") ? 0 : Document.FLAG_SUPPORTS_DELETE);
            else if (Document.COLUMN_SIZE.equals(column)) value = doc == null ? 0L : doc.file.length();
            row.add(value);
        }
    }

    private boolean isMode(String... modes) {
        for (String candidate : modes) if (mode.equals(candidate)) return true;
        return false;
    }

    private void rememberBytes(Doc doc) {
        if (doc.sha == null && doc.file.length() > 0) {
            if (doc.file.length() > 1_048_576) throw new IllegalStateException("Fixture output exceeds 1 MiB");
            doc.length = doc.file.length();
            try { doc.sha = sha(Files.readAllBytes(doc.file.toPath())); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
    }

    private static String sha(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                if ((value & 255) < 16) result.append('0');
                result.append(Integer.toHexString(value & 255));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private void poisonFirst() {
        if (poisoned) return;
        Doc first = documents.values().iterator().next();
        rememberBytes(first);
        if (first.length == 0) throw new IllegalStateException("First page was not written");
        if (isMode("PoisonBytesOnSecondCreate", "PoisonBytesOnSecondRead")) {
            try {
                byte[] bytes = Files.readAllBytes(first.file.toPath());
                bytes[bytes.length - 1] ^= 1;
                Files.write(first.file.toPath(), bytes);
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        poisoned = true;
    }

    private void reset() {
        clearFiles();
        try {
            directory = Files.createTempDirectory(Objects.requireNonNull(getContext()).getCacheDir().toPath(),
                "storage-security-provider-").toFile();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        mode = "Stable"; poisoned = false;
    }

    private void clearFiles() {
        for (Doc doc : documents.values())
            if (doc.file.exists() && !doc.file.delete()) throw new IllegalStateException("Fixture file cleanup failed");
        documents.clear();
        if (directory != null && !directory.delete()) throw new IllegalStateException("Fixture directory cleanup failed");
        directory = null;
    }

    private static FileNotFoundException fileFailure(Exception failure) {
        FileNotFoundException result = new FileNotFoundException(failure.getMessage());
        result.initCause(failure);
        return result;
    }
}
