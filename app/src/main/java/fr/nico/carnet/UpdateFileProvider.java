package fr.nico.carnet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Exposes only the verified update APK, read-only, to the Android installer. */
public class UpdateFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File checkedFile(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !(getContext().getPackageName()+".updates").equals(uri.getAuthority()) || !"/Carnet.apk".equals(uri.getPath()))
            throw new IllegalArgumentException("Fichier non autorisé");
        return new File(getContext().getCacheDir(),"updates/Carnet.apk");
    }
    @Override public String getType(Uri uri) { checkedFile(uri); return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        File file=checkedFile(uri);
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]="Carnet.apk";
            else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file.length();
        }
        cursor.addRow(row);return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Lecture seule");
        return ParcelFileDescriptor.open(checkedFile(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
