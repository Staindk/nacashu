package io.github.staindk.nacashu;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.UUID;

import io.rebble.pebblekit2.common.SendDataCallback;
import io.rebble.pebblekit2.common.UniversalRequestResponse;

/**
 * The parts of the PebbleKit 2 protocol this app needs, without the client library's
 * dependencies. Wire format follows https://github.com/pebble-dev/PebbleKitAndroid2
 * (Apache License 2.0): PebbleKitBundleKeys, PebbleDictionarySerialization and
 * ReceiveResultSerialization.
 */
final class PebbleKit2 {
    private static final String TAG = "PebbleKit2";

    /** Our service is bound with this action when a registered watchapp opens. */
    static final String INTENT_RECEIVE_DATA = "io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH";
    /** The Pebble app's service we bind to send data. */
    static final String INTENT_SEND_DATA = "io.rebble.pebblekit2.SEND_DATA_TO_WATCH";

    static final String KEY_ACTION = "ACTION";
    static final String KEY_WATCHAPP_UUID = "WATCHAPP_UUID";
    static final String KEY_WATCHES_ID = "WATCHES_ID";
    static final String KEY_WATCH_ID = "WATCH_ID";
    static final String KEY_DATA_DICTIONARY = "DATA_DICTIONARY";
    static final String KEY_TRANSMISSION_RESULTS = "TRANSMISSION_RESULTS";

    static final String ACTION_SEND_DATA_TO_WATCH = "SEND_DATA_TO_WATCH";
    static final String ACTION_RECEIVE_DATA_FROM_WATCH = "ACTION_RECEIVE_DATA_FROM_WATCH";
    static final String ACTION_APP_OPENED = "APP_OPENED";
    static final String ACTION_APP_CLOSED = "APP_CLOSED";

    private static final long SEND_TIMEOUT_MS = 5000;

    private PebbleKit2() {
    }

    /** Package of the installed Pebble app (Core "Pebble" app), or null. */
    static String pebbleAppPackage(Context context) {
        List<ResolveInfo> services =
                context.getPackageManager().queryIntentServices(new Intent(INTENT_SEND_DATA), 0);
        return services.isEmpty() ? null : services.get(0).serviceInfo.packageName;
    }

    static boolean isPebbleApp(Context context, String pkg) {
        if (pkg == null) return false;
        for (ResolveInfo info : context.getPackageManager()
                .queryIntentServices(new Intent(INTENT_SEND_DATA), 0)) {
            if (pkg.equals(info.serviceInfo.packageName)) return true;
        }
        return false;
    }

    /** Reads an integer dictionary entry, or null if missing or not a number. */
    static Long getInt(Bundle dictionary, int key) {
        String type = dictionary.getString(key + "_TYPE");
        if (type == null || type.equals("String") || type.equals("ByteArray")) return null;
        return dictionary.getLong(key + "_VALUE");
    }

    static Bundle uint8Dictionary(int key, int value) {
        Bundle bundle = new Bundle();
        bundle.putString(key + "_TYPE", "UInt8");
        bundle.putLong(key + "_VALUE", value & 0xFF);
        return bundle;
    }

    static Bundle receiveResult(boolean ack) {
        Bundle result = new Bundle();
        result.putString("TYPE", ack ? "ACK" : "NACK");
        Bundle reply = new Bundle();
        reply.putBundle(KEY_TRANSMISSION_RESULTS, result);
        return reply;
    }

    /** Sends a dictionary to the watchapp on one watch. Fire and forget. */
    static void send(Context context, UUID watchapp, Bundle dictionary, String watchId) {
        String pkg = pebbleAppPackage(context);
        if (pkg == null) {
            Log.w(TAG, "No Pebble app installed");
            return;
        }
        final Context app = context.getApplicationContext();
        final Bundle request = new Bundle();
        request.putString(KEY_ACTION, ACTION_SEND_DATA_TO_WATCH);
        request.putString(KEY_WATCHAPP_UUID, watchapp.toString());
        request.putBundle(KEY_DATA_DICTIONARY, dictionary);
        request.putStringArray(KEY_WATCHES_ID, watchId == null ? null : new String[]{watchId});

        final SendConnection connection = new SendConnection(app, request);
        Intent intent = new Intent(INTENT_SEND_DATA).setPackage(pkg);
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Log.w(TAG, "Could not bind to " + pkg);
            connection.finish();
            return;
        }
        connection.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                connection.finish();
            }
        }, SEND_TIMEOUT_MS);
    }

    /** One bind, one request, then unbind. All callbacks run on the main thread. */
    private static final class SendConnection implements ServiceConnection {
        final Handler main = new Handler(Looper.getMainLooper());
        private final Context app;
        private final Bundle request;
        private boolean done;

        SendConnection(Context app, Bundle request) {
            this.app = app;
            this.request = request;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                UniversalRequestResponse.Stub.asInterface(binder)
                        .request(request, new SendDataCallback.Stub() {
                            @Override
                            public void onResult(Bundle bundle) {
                                Log.d(TAG, "Send result: " + bundle);
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        finish();
                                    }
                                });
                            }
                        });
            } catch (RemoteException e) {
                Log.w(TAG, "Send failed", e);
                finish();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            finish();
        }

        void finish() {
            if (done) return;
            done = true;
            try {
                app.unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // Never bound.
            }
        }
    }
}
