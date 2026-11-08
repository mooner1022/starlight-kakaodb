package dev.mooner.kakaodb.sharedd;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.util.Log;

import dev.mooner.kakaodb.rootd.Protocol;
import dev.mooner.kakaodb.rootd.RootDaemon;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Serves KakaoTalk's databases to StarLight. Installed patched into KakaoTalk's shared UID (by the setup tool), it can
 * open them where they are; the plugin binds it and makes the one transaction described in {@link Protocol}, which
 * hands out a socket speaking RootDaemon's protocol, limited to KakaoTalk's database directory.
 *
 * <p>Only StarLight's UID is served, and only while this helper really runs as KakaoTalk's UID. Every StarLight plugin
 * shares that UID, so this can't tell this plugin apart from the others.
 */
public final class SharedService extends Service {

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code != IBinder.FIRST_CALL_TRANSACTION)
                return super.onTransact(code, data, reply, flags);
            data.enforceInterface(Protocol.SHARED_DESCRIPTOR);
            int version = data.readInt();
            if (version != Protocol.VERSION)
                throw new IllegalArgumentException(
                    Protocol.SHARED_MISMATCH + ": the plugin speaks " + version + ", this helper " + Protocol.VERSION);
            authorize(Binder.getCallingUid());

            final ParcelFileDescriptor[] pair;
            try {
                pair = ParcelFileDescriptor.createSocketPair();
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't create a socket pair: " + e.getMessage(), e);
            }
            new Thread(() -> serve(pair[0]), "kakaodb-shared-session").start();
            HelperState.connected();
            reply.writeNoException();
            reply.writeString(BuildConfig.SHARED_HELPER_BUILD);
            // Closes this side's copy once written.
            pair[1].writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            return true;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        HelperState.loadSqlCipher();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** Throws a SecurityException, which reaches the caller, unless [uid] is StarLight's and this is KakaoTalk's. */
    private void authorize(int uid) {
        PackageManager packages = getPackageManager();
        String refusal;
        try {
            if (uid != packages.getApplicationInfo(Protocol.STARLIGHT_PACKAGE, 0).uid)
                refusal = "uid " + uid + " is not StarLight";
            else if (Process.myUid() != packages.getApplicationInfo(Protocol.KAKAO_PACKAGE, 0).uid)
                refusal = "this helper does not share KakaoTalk's UID";
            else
                return;
        } catch (PackageManager.NameNotFoundException e) {
            refusal = e.getMessage() + " is not installed";
        }
        HelperState.refused(refusal);
        throw new SecurityException(refusal);
    }

    private void serve(ParcelFileDescriptor socket) {
        HelperState.sessions.incrementAndGet();
        try (ParcelFileDescriptor fd = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(
                 new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()))));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                 new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()))))) {
            RootDaemon.serveAuthenticated(in, out, HelperChecks.databaseDir(this));
        } catch (IOException | RuntimeException | PackageManager.NameNotFoundException e) {
            Log.w(HelperState.TAG, "session ended", e);
        } finally {
            HelperState.sessions.decrementAndGet();
        }
    }
}
