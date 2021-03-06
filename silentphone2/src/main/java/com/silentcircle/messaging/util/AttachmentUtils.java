/*
Copyright (C) 2016, Silent Circle, LLC.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Any redistribution, use, or modification is done solely for personal
      benefit and not for any commercial purpose or for monetary gain
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name Silent Circle nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL SILENT CIRCLE, LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package com.silentcircle.messaging.util;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.silentcircle.messaging.listener.DismissDialogOnClick;
import com.silentcircle.messaging.model.event.Event;
import com.silentcircle.messaging.model.event.Message;
import com.silentcircle.messaging.providers.AudioProvider;
import com.silentcircle.messaging.providers.PictureProvider;
import com.silentcircle.messaging.providers.TextProvider;
import com.silentcircle.messaging.providers.VCardProvider;
import com.silentcircle.messaging.providers.VideoProvider;
import com.silentcircle.messaging.repository.EventRepository;
import com.silentcircle.messaging.services.SCloudService;
import com.silentcircle.silentphone2.BuildConfig;
import com.silentcircle.silentphone2.R;
import com.silentcircle.silentphone2.util.Utilities;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utilities for attachment handling.
 */
public class AttachmentUtils {

    private static final String TAG = AttachmentUtils.class.getSimpleName();

    // Ends up being "/data/data/com.silentcircle.silentphone/app_attachments/{filename}"
    private static final String TEMP_DIR = "attachments";

    private static final String SC_DOWNLOAD_SUBDIRECTORY = "Silent Circle Saved Files";

    public static final long FILE_SIZE_LIMIT = 100 * 1024 * 1024;
    public static final long FILE_SIZE_BIG = 50 * 1024 * 1024;
    public static final long FILE_SIZE_MEDIUM = 10 * 1024 * 1024;
    public static final long FILE_SIZE_SMALL = 1 * 1024 * 1024;

    public static final int MATCH_CONTACTS_URI = 1;
    public static final int MATCH_VCARD_URI = 2;

    private static final UriMatcher CONTENT_URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        // content://com.android.contacts/contacts/lookup/*/*
        CONTENT_URI_MATCHER.addURI(ContactsContract.AUTHORITY, "contacts/lookup/*/*",
                MATCH_CONTACTS_URI);
        CONTENT_URI_MATCHER.addURI(VCardProvider.AUTHORITY, "*", MATCH_VCARD_URI);
    }

    private static final String FILE_NAME_PATTERN = "^(.+)\\.([^\\.]+)$";

    private static String mPicturePath;

    private static String mVideoPath;

    private static String mAudioPath;

    private static String mTextPath;

    public static String getExtensionFromFileName(String fileName) {
        if(fileName == null) {
            return null;
        }

        int slash = fileName.lastIndexOf(File.separatorChar);
        int dot = fileName.lastIndexOf('.');
        if (slash >= dot) {
            return null;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ENGLISH);
        return extension;
    }

    public static File getExternalStorageFile(String fileName) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            return null;
        }

        if (TextUtils.isEmpty(fileName)) {
            return null;
        }

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File scDownloadDir = new File(downloadDir, SC_DOWNLOAD_SUBDIRECTORY);
        scDownloadDir.mkdirs();

        String externalFileName = fileName;

        File externalFile = new File(scDownloadDir, externalFileName);

        return externalFile;
    }

    public static File getFile(Context context, Uri uri) {
        String path = getPath(context, uri);
        return path == null ? null : new File(path);
    }

    public static String getFileName(Context context, Uri uri) {
        String fileName = new String();
        String scheme = uri.getScheme();

        if(scheme.equals("file")) {
            fileName = uri.getLastPathSegment();
        } else if (scheme.equals("content")) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } catch (Throwable exception) {
                fileName = null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        if(fileName == null) {
            File file = getFile(context, uri);

            if(file != null) {
                fileName = file.getName();
            }
        }
                                        // FIXME: Hotfix
        if(fromOurMediaProvider(uri) || uri.toString().equals("content://" + BuildConfig.AUTHORITY_BASE + ".files/captured_image/IMG.jpg")) {
            fileName = decorateFilename(fileName, context);
        }

//        if(getExtensionFromFileName(fileName) == null) {
//            String extension = null;
//            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(getMIMETypeFromFileName(fileName));
//            if (TextUtils.isEmpty(extension)) {
//                extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(getMIMEType(context, uri));
//            }
//
//            if (fileName != null && extension != null && !fileName.toLowerCase().endsWith("." + extension.toLowerCase())) {
//                fileName = fileName + "." + extension;
//            }
//        }

        return !TextUtils.isEmpty(fileName) ? fileName : null;
    }

    public static long getFileSize(Context context, Uri uri) {
        try {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
            long size = fd.getStatSize();
            fd.close();
            return size;
        } catch (Throwable exception) {
            return 0;
        }
    }

    public static byte[] getContent(Context context, Uri uri) {
        byte[] result = null;

        InputStream stream = null;
        try {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
            stream = new ParcelFileDescriptor.AutoCloseInputStream(fd);
            result = IOUtils.readFully(stream);
        } catch (Throwable exception) {
            result = null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                }
                catch (IOException e) {
                    // ignore exception when failing to close the stream
                }
            }
        }
        return result;
    }


    public static String getLabelForDuration(int ms) {
        if (ms <= 0) {
            return null;
        }
        return String.format("%02d:%02d", Integer.valueOf(ms / 60000), Integer.valueOf(ms / 1000 % 60));
    }

    public static String getMIMEType(Context context, Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        if (mimeType != null) {
            return mimeType;
        }
        String fileName = getFileName(context, uri);

        mimeType = getMIMETypeFromFileName(fileName);

        return mimeType != null ? mimeType : "application/octet-stream";
    }

    public static String getMIMEType(File file) {
        return getMIMETypeFromFileName(file.getName());
    }

    public static String getMIMETypeFromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String extension = getExtensionFromFileName(fileName);
        if (extension == null) {
            return null;
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

    public static String getPath(Context context, Uri uri) {
        // we temp saved picture into internal storage, uri is for contentProvider and it's
        // different from path to the file. and this mPacturePath will be used by SCloudEncryptor
        // too. Same as video.
        if (uri.equals(PictureProvider.CONTENT_URI)) {
            mPicturePath = context.getFilesDir().getAbsolutePath() + "/" + PictureProvider.JPG_FILE_NAME;
            return mPicturePath;
        } else if (uri.equals(VideoProvider.CONTENT_URI)) {
            mVideoPath = context.getFilesDir().getAbsolutePath() + "/" + VideoProvider.MP4_FILE_NAME;
            return mVideoPath;
        } else if (uri.equals(AudioProvider.CONTENT_URI)) {
            mAudioPath = context.getFilesDir().getAbsolutePath() + "/" + AudioProvider.MP4_FILE_NAME;
            return mAudioPath;
        } else if (uri.equals(TextProvider.CONTENT_URI)) {
            mTextPath = context.getFilesDir().getAbsolutePath() + "/" + TextProvider.TXT_FILE_NAME;
            return mTextPath;
        }

        String path = uri.getPath();

        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{
                    MediaStore.MediaColumns.DATA
            }, null, null, null);
        } catch (IllegalArgumentException exception) {
            return path;
        } catch (RuntimeException exception) {
            return path;
        }

        if( cursor == null ) {
            return path;
        }
        if( cursor.moveToFirst() ) {
            path = cursor.getString( cursor.getColumnIndex( MediaStore.MediaColumns.DATA ) );
        }
        cursor.close();
        return path;
    }

    public static Bitmap getPreviewImage(String contentType, String base64thumbnail, int targetDensity) {

        Bitmap bitmap = null;

        if (base64thumbnail != null && MIME.isVisual(contentType) /*|| UTI.isVisual(contentType)*/) {
            try {
                byte[] thumbnail = Base64.decode(base64thumbnail, Base64.NO_WRAP);

                bitmap = getPreviewImage(thumbnail, targetDensity);
            } catch (IllegalArgumentException exception) {
                Log.e(TAG, "Attachment preview error - illegal argument", exception);

                return null;
            }
        }

        return bitmap;

    }

    public static Bitmap getPreviewImage(byte[] thumbnail, int targetDensity) {

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inDensity = DisplayMetrics.DENSITY_DEFAULT;
        options.inScaled = true;
        options.inTargetDensity = targetDensity;

        Bitmap bitmap = null;

        try {
            bitmap = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.length, options);
        } catch (OutOfMemoryError exception) {
            Log.e(TAG, "Attachment preview error - out of memory", exception);

            return null;
        }

        return bitmap;
    }

    public static int getPreviewIcon(String mimeType) {

        if(TextUtils.isEmpty(mimeType)) {
            return 0;
        }

        if(MIME.isPdf(mimeType)) {
            return R.drawable.ic_pdf;
        }

        if(MIME.isContact(mimeType)) {
            return R.drawable.ic_vcard;
        }

        if(MIME.isText(mimeType)) {
            return R.drawable.ic_txt;
        }

        if(MIME.isDoc(mimeType)) {
            return R.drawable.ic_doc;
        }

        if(MIME.isXls(mimeType)) {
            return R.drawable.ic_xls;
        }

        if(MIME.isPpt(mimeType)) {
            return R.drawable.ic_ppt;
        }

        if(MIME.isOctetStream(mimeType)) {
            return R.drawable.ic_generic_file;
        }
        return 0;
    }

    private static int getResourceDisplayDensityDPI(Context context) {
        try {
            Resources resources = context.getResources();
            DisplayMetrics displayMetrics = resources.getDisplayMetrics();
            return displayMetrics.densityDpi;
        } catch (NullPointerException expected) {
            return DisplayMetrics.DENSITY_DEFAULT;
        }
    }

    private static String decorateFilename(String fileName, Context context) {
        int slash = fileName.lastIndexOf(File.separatorChar);
        int dot = fileName.lastIndexOf('.');
        if (slash >= dot) {
            return null;
        }
        String name = fileName.substring(0, dot);
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ENGLISH);

        DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");

        return String.format("%s_%s.%s", name, dateFormat.format(new Date()), extension);
    }

    public static String getVideoPath() {
        return mVideoPath;
    }

    public static String getAudioPath() {
        return mAudioPath;
    }

    // TODO: Put these in a URI matcher when we deprecate the content providers
    public static boolean fromOurMediaProvider(Uri uri) {
        return uri.equals(PictureProvider.CONTENT_URI) || uri.equals(VideoProvider.CONTENT_URI)
                || uri.equals(AudioProvider.CONTENT_URI) || uri.equals(TextProvider.CONTENT_URI);

    }

    public static boolean isExported(String fileName, String integrityHash) {
        File externalFile = getExternalStorageFile(fileName);

        if (externalFile == null || !externalFile.exists()) {
            return false;
        }

        if (!TextUtils.isEmpty(integrityHash)) {
            try {
                String foundHash = Utilities.hash(new FileInputStream(externalFile), "SHA256");

                if (!integrityHash.equals(foundHash)) {
                    return false;
                }
            } catch (FileNotFoundException exception) {
                return false;
            }
        }

        return true;
    }

    /**
     * Attempts to remove the download with the given ID from the system's download manager, if
     * available.
     *
     * @see {@link DownloadManager#remove(long...)}
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    public static void removeFromDownloadManager(Context context, long id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager != null) {
                downloadManager.remove(id);
            }
        }
    }

    private static File getDir(Context context) {
        return context.getDir(TEMP_DIR, Context.MODE_PRIVATE);
    }

    public static File getFile(String messageId, Context context) {
        File tempFile = new File(getDir(context), messageId);

        if(!tempFile.exists()) {
            try {
                tempFile.createNewFile();
            } catch (IOException exception) {
                return null;
            }
        }

        return tempFile;
    }

    public static boolean writeFile(String messageId, byte[] data, Context context) {
        File newTempFile = new File(getDir(context), messageId);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(newTempFile);

            fos.write(data);
            fos.flush();

            return true;
        } catch (FileNotFoundException exception) {
            Log.e(TAG, "Temp file not found", exception);

            return false;
        } catch (IOException exception) {
            Log.e(TAG, "Temp file I/O error", exception);

            return false;
        } finally {
            try {
                if (fos != null)
                    fos.close();
            } catch (IOException ignore) { }
        }
    }

    public static File copyToExternalStorage(Context context, File file, final String fileName) {

        final File externalFile = AttachmentUtils.getExternalStorageFile(fileName);
        if (externalFile == null || externalFile.exists()) {
            return externalFile;
        }

        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(file);
            out = new FileOutputStream(externalFile, false);
            IOUtils.pipe(in, out);

            makePublic(context, externalFile);

            return externalFile;
        } catch (IOException exception) {
            externalFile.delete();
        } finally {
            IOUtils.close(in, out);
        }
        return null;
    }

    public static boolean removeAttachment(String partner, String messageId, boolean keepStatus, Context context) {
        File tempFile = getFile(messageId, context);

        if(!keepStatus) {
            SCloudService.DB.deleteAttachmentState(messageId, partner);
        }

        if(tempFile != null) {
            return tempFile.delete();
        }

        return false;
    }

    public static void removeAttachments(Context context) {
        File tempDir = getDir(context);

        for(File file : tempDir.listFiles()) {
            file.delete();
        }
    }

    public static boolean resolves(PackageManager packageManager, String action, Uri uri, String mimeType) {
        Intent intent = new Intent(action);
        intent.setDataAndType(uri, mimeType);
        return intent.resolveActivity(packageManager) != null;
    }

    public static void setExportedFilename(Context context, String partner, String messageId, String exportedFilename) {
        EventRepository eventRepository = MessageUtils.getEventRepository(context, partner);

        if (eventRepository == null) {
            return;
        }

        Event event = eventRepository.findById(messageId);

        if (event != null && event instanceof Message && ((Message) event).hasMetaData()) {
            JSONObject metaDataJson;
            try {
                metaDataJson = new JSONObject(((Message) event).getMetaData());
                metaDataJson.put("ExportedFileName", exportedFilename);

                ((Message) event).setMetaData(metaDataJson.toString());
            } catch (JSONException e) {
                return;
            }
        }

        eventRepository.save(event);
    }

    private static String sanitizeFileName(String fileName) {
        StringBuilder sanitized = new StringBuilder();
        sanitized.append(fileName.replaceAll(FILE_NAME_PATTERN, "$1").replaceAll("[^A-Za-z0-9_\\-]", "_"));
        sanitized.append(fileName.replaceAll(FILE_NAME_PATTERN, "$2"));
        return sanitized.toString();
    }

    public static void showFileSizeErrorDialog(Context context) {
        showFileSizeErrorDialog(context, null);
    }

    public static void showFileSizeErrorDialog(Context context, DialogInterface.OnClickListener onDismissed) {

        AlertDialog.Builder alert = new AlertDialog.Builder(context);

        alert.setTitle(R.string.dialog_title_error);
        alert.setMessage(R.string.dialog_message_file_too_large);
        alert.setNegativeButton(android.R.string.cancel, new DismissDialogOnClick(onDismissed));

        alert.show();
    }

    /**
     * Hack to make exported file visible for other users.
     *
     * When exporting file to Download folder on external storage on Lollipop devices
     * it is not visible when phone is mounted in USB storage mode. File is visible only
     * to the application which exported it.
     *
     * Android issue: https://code.google.com/p/android/issues/detail?id=38282
     */
    public static void makePublic(final Context context, final File file) {

        // grant read, write, execute permissions for file
        file.setExecutable(true);
        file.setReadable(true);
        file.setWritable(true);

        // Rescan file to make it available
        MediaScannerConnection.scanFile(context.getApplicationContext(),
                new String[]{file.toString()}, null, null);
    }

    /**
     * Recognize passed uri as a specific attachment type from list contained in
     * {@link #CONTENT_URI_MATCHER}.
     *
     * @param uri Uri to be matched.
     * @return Id for specific uri or UriMatcher.NO_MATCH (-1) if uri was not recognized.
     */
    public static int matchAttachmentUri(final Uri uri) {
        int result = UriMatcher.NO_MATCH;
        if (uri != null) {
            result = CONTENT_URI_MATCHER.match(uri);
        }
        return result;
    }
}

