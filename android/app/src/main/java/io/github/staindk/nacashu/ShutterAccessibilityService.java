package io.github.staindk.nacashu;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.provider.MediaStore;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Finds the stock camera's shutter button and clicks it, the same way TalkBack would.
 * The camera app still takes the photo, so all of its processing applies.
 */
public class ShutterAccessibilityService extends AccessibilityService {
    private static final String TAG = "ShutterA11y";

    // Guesses for Pixel Camera; confirm with "Dump camera screen" on a real device.
    private static final String[] SHUTTER_VIEW_IDS = {
            "com.google.android.GoogleCamera:id/shutter_button",
            "com.google.android.GoogleCamera:id/shutter",
    };
    private static final String[] SHUTTER_DESCRIPTIONS = {
            "take photo", "shutter", "take picture", "capture",
    };

    static volatile ShutterAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    int openCamera() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        String action = keyguard != null && keyguard.isKeyguardLocked()
                ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA;
        Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String pkg = Settings.cameraPackage(this);
        if (new Intent(intent).setPackage(pkg).resolveActivity(getPackageManager()) != null) {
            intent.setPackage(pkg);
        }
        try {
            // Allowed from the background: a running accessibility service is exempt from
            // background activity launch restrictions.
            startActivity(intent);
            return ShutterProtocol.STATUS_OK;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not open camera", e);
            return ShutterProtocol.STATUS_CAMERA_NOT_OPEN;
        }
    }

    void pressShutter(final ShutterProtocol.StatusCallback callback) {
        AccessibilityNodeInfo root = cameraRoot();
        if (root == null) {
            callback.onStatus(ShutterProtocol.STATUS_CAMERA_NOT_OPEN);
            return;
        }

        AccessibilityNodeInfo shutter = findShutter(root);
        if (shutter != null) {
            AccessibilityNodeInfo target = clickableAncestor(shutter);
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Settings.saveTapPoint(this, bounds.exactCenterX(), bounds.exactCenterY());
                callback.onStatus(ShutterProtocol.STATUS_OK);
                return;
            }
            // Some camera builds ignore ACTION_CLICK on the shutter; tap it instead.
            tap(bounds.exactCenterX(), bounds.exactCenterY(), callback);
            return;
        }

        // No node found: tap where the shutter was last time, or bottom centre of the camera.
        float[] saved = Settings.tapPoint(this);
        Rect window = new Rect();
        root.getBoundsInScreen(window);
        float x = saved != null ? saved[0] : window.exactCenterX();
        float y = saved != null ? saved[1] : window.top + window.height() * 0.86f;
        tap(x, y, callback);
    }

    /** Writes the camera's view tree to a file so we can learn its real IDs and labels. */
    String dumpCameraUi() {
        AccessibilityNodeInfo root = cameraRoot();
        if (root == null) root = getRootInActiveWindow();
        StringBuilder out = new StringBuilder();
        if (root == null) {
            out.append("No window content available\n");
        } else {
            dump(root, 0, out);
        }
        String text = out.toString();
        try (FileOutputStream stream = new FileOutputStream(lastDumpFile(this))) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Could not write dump", e);
        }
        Log.i(TAG, text);
        return text;
    }

    static File lastDumpFile(Context context) {
        return new File(context.getFilesDir(), "camera-ui-dump.txt");
    }

    private AccessibilityNodeInfo cameraRoot() {
        String pkg = Settings.cameraPackage(this);
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && pkg.contentEquals(String.valueOf(root.getPackageName()))) {
                return root;
            }
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && pkg.equals(String.valueOf(active.getPackageName()))) {
            return active;
        }
        return null;
    }

    private AccessibilityNodeInfo findShutter(AccessibilityNodeInfo root) {
        for (String id : SHUTTER_VIEW_IDS) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id)) {
                if (node.isVisibleToUser()) return node;
            }
        }
        return findByDescription(root);
    }

    private AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo node) {
        CharSequence desc = node.getContentDescription();
        if (desc != null && node.isVisibleToUser()) {
            String lower = desc.toString().toLowerCase(Locale.ROOT);
            for (String match : SHUTTER_DESCRIPTIONS) {
                if (lower.contains(match)) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findByDescription(child);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return node;
    }

    private void tap(float x, float y, final ShutterProtocol.StatusCallback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                callback.onStatus(ShutterProtocol.STATUS_GESTURE_FALLBACK);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                callback.onStatus(ShutterProtocol.STATUS_SHUTTER_NOT_FOUND);
            }
        }, null);
        if (!dispatched) {
            callback.onStatus(ShutterProtocol.STATUS_SHUTTER_NOT_FOUND);
        }
    }

    private static void dump(AccessibilityNodeInfo node, int depth, StringBuilder out) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        for (int i = 0; i < depth; i++) out.append("  ");
        String cls = String.valueOf(node.getClassName());
        out.append(cls.substring(cls.lastIndexOf('.') + 1))
                .append(" id=").append(node.getViewIdResourceName())
                .append(" desc=").append(node.getContentDescription())
                .append(" text=").append(node.getText())
                .append(node.isClickable() ? " CLICKABLE" : "")
                .append(' ').append(bounds.toShortString())
                .append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) dump(child, depth + 1, out);
        }
    }
}
