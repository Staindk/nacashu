package io.github.staindk.nacashu;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Setup and test screen. Plain views to keep the POC dependency-free. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout column;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad * 3, pad, pad);

        status = new TextView(this);
        status.setTextSize(16f);
        status.setPadding(0, 0, 0, pad);
        column.addView(status);

        addButton("1. Turn on accessibility service", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        CheckBox autoOpen = new CheckBox(this);
        autoOpen.setText("Open camera when the watchapp starts");
        autoOpen.setChecked(Settings.autoOpenCamera(this));
        autoOpen.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.setAutoOpenCamera(MainActivity.this, checked);
            }
        });
        column.addView(autoOpen);

        addButton("Test: open camera", new Runnable() {
            @Override
            public void run() {
                runCommand(ShutterProtocol.CMD_OPEN_CAMERA, 0);
            }
        });
        addButton("Test: open camera, then shutter after 4s", new Runnable() {
            @Override
            public void run() {
                runCommand(ShutterProtocol.CMD_OPEN_CAMERA, 0);
                runCommand(ShutterProtocol.CMD_SHUTTER, 4000);
            }
        });
        addButton("Debug: dump camera screen after 5s", new Runnable() {
            @Override
            public void run() {
                toast("Switch to the camera now");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ShutterAccessibilityService service = ShutterAccessibilityService.instance;
                        if (service == null) {
                            toast("Accessibility service is off");
                        } else {
                            service.dumpCameraUi();
                            toast("Camera screen dumped");
                        }
                    }
                }, 5000);
            }
        });
        addButton("Debug: share last dump", new Runnable() {
            @Override
            public void run() {
                shareDump();
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String pebbleApp = PebbleKit2.pebbleAppPackage(this);
        status.setText((ShutterAccessibilityService.instance != null
                ? "Accessibility service: ON"
                : "Accessibility service: OFF (step 1)")
                + "\nPebble app: " + (pebbleApp != null ? pebbleApp : "not found")
                + "\nCamera app: " + Settings.cameraPackage(this)
                + "\n\nOpen Shutter on the watch, then press SELECT to take a photo.");
    }

    private void addButton(String label, final Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });
        column.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void runCommand(final int cmd, long delayMs) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                ShutterProtocol.run(cmd, new ShutterProtocol.StatusCallback() {
                    @Override
                    public void onStatus(int result) {
                        toast(ShutterProtocol.statusName(result));
                    }
                });
            }
        }, delayMs);
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void shareDump() {
        File file = ShutterAccessibilityService.lastDumpFile(this);
        if (!file.exists()) {
            toast("No dump yet");
            return;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            toast("Could not read dump");
            return;
        }
        if (text.length() > 90000) text = text.substring(0, 90000);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Share camera dump"));
    }
}
