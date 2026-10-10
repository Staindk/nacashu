package io.github.staindk.nacashu;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import io.rebble.pebblekit2.common.SendDataCallback;
import io.rebble.pebblekit2.common.UniversalRequestResponse;

/**
 * The Pebble app binds this service (PebbleKit 2) while the Shutter watchapp is open and
 * forwards its AppMessages here.
 */
public class ShutterListenerService extends Service {
    private static final String TAG = "ShutterListener";

    private final Handler main = new Handler(Looper.getMainLooper());

    private final UniversalRequestResponse.Stub binder = new UniversalRequestResponse.Stub() {
        @Override
        public void request(final Bundle data, final SendDataCallback callback) {
            final String caller = getPackageManager().getNameForUid(Binder.getCallingUid());
            main.post(new Runnable() {
                @Override
                public void run() {
                    Bundle reply;
                    if (!PebbleKit2.isPebbleApp(ShutterListenerService.this, caller)) {
                        Log.w(TAG, "Ignoring request from " + caller);
                        reply = new Bundle();
                    } else {
                        reply = handle(data);
                    }
                    try {
                        callback.onResult(reply);
                    } catch (RemoteException ignored) {
                        // The Pebble app went away; nothing to reply to.
                    }
                }
            });
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private Bundle handle(Bundle data) {
        String action = data.getString(PebbleKit2.KEY_ACTION);
        String watchId = data.getString(PebbleKit2.KEY_WATCH_ID);
        if (PebbleKit2.ACTION_RECEIVE_DATA_FROM_WATCH.equals(action)) {
            Bundle dictionary = data.getBundle(PebbleKit2.KEY_DATA_DICTIONARY);
            Long cmd = dictionary == null ? null : PebbleKit2.getInt(dictionary, ShutterProtocol.KEY_CMD);
            if (cmd == null) {
                return PebbleKit2.receiveResult(false);
            }
            runCommand(cmd.intValue(), watchId);
            // Ack straight away so the watch's outbox frees up; the outcome follows separately.
            return PebbleKit2.receiveResult(true);
        }
        if (PebbleKit2.ACTION_APP_OPENED.equals(action)) {
            Log.d(TAG, "Watchapp opened on " + watchId);
            ShutterAccessibilityService service = ShutterAccessibilityService.instance;
            if (service != null && Settings.autoOpenCamera(this)) {
                service.openCamera();
            }
        }
        return new Bundle();
    }

    private void runCommand(final int cmd, final String watchId) {
        Log.d(TAG, "cmd=" + cmd + " from " + watchId);
        ShutterProtocol.run(cmd, new ShutterProtocol.StatusCallback() {
            @Override
            public void onStatus(int status) {
                Log.d(TAG, "cmd=" + cmd + " -> " + ShutterProtocol.statusName(status));
                PebbleKit2.send(ShutterListenerService.this, ShutterProtocol.WATCHAPP_UUID,
                        PebbleKit2.uint8Dictionary(ShutterProtocol.KEY_STATUS, status), watchId);
            }
        });
    }
}
