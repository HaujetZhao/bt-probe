package me.haujet.btprobe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * Streams raw RFCOMM frames at the phone's own pace so the laptop can measure what the
 * Bluetooth link itself sustains, with none of Edgepad's batching in the way.
 *
 * Movement frames are 5 bytes: 0x10, dx, dy, seq low, 0x00, with dx/dy signed deltas.
 * A test sends a statistics line over the same socket when it ends, prefixed with 'S'.
 */
public class MainActivity extends Activity {

    private static final UUID SERVICE = UUID.fromString("8e9a2f6c-5b3e-4d1a-9c7f-2a6b8d0e1f35");
    private static final int DURATION_MS = 10000;
    private static final int CIRCLE_DURATION_MS = 15000;
    private static final int MAX_SAMPLES = 400000;

    /** Circle the cursor is told to trace: 220 px radius at 900 px/s along the arc. */
    private static final double CIRCLE_RADIUS = 220.0;
    private static final double CIRCLE_SPEED = 900.0;
    private static final double CIRCLE_OMEGA = CIRCLE_SPEED / CIRCLE_RADIUS;
    private static final int MAX_DELTA = 120;

    private final List<Button> buttons = new ArrayList<>();
    private final StringBuilder transcript = new StringBuilder();

    private TextView status;
    private TextView result;
    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private volatile String lastError = "";
    private OutputStream out;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setText("未连接");
        root.addView(status);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 1);
        }

        addHeading(root, "已配对的电脑");
        LinearLayout devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        root.addView(devices);

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("这台设备没有蓝牙");
        } else {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded.isEmpty()) {
                addNote(devices, "没有已配对的设备，先在系统设置里配对电脑");
            }
            for (BluetoothDevice d : bonded) {
                Button b = addButton(devices, safeName(d) + "\n" + d.getAddress());
                b.setOnClickListener(v -> connect(d));
            }
        }

        addHeading(root, "吞吐测试（每种 10 秒）");
        LinearLayout tests = new LinearLayout(this);
        tests.setOrientation(LinearLayout.VERTICAL);
        root.addView(tests);

        addTest(tests, "不限速，5 字节帧", 0, 5);
        addTest(tests, "不限速，50 字节帧", 0, 50);
        addTest(tests, "固定 120 Hz，5 字节帧", 120, 5);
        addTest(tests, "固定 240 Hz，5 字节帧", 240, 5);

        addHeading(root, "画圈（半径 220px，900px/s，每种 15 秒）");
        LinearLayout circles = new LinearLayout(this);
        circles.setOrientation(LinearLayout.VERTICAL);
        root.addView(circles);

        for (int hz : new int[] {60, 90, 120, 240}) {
            addCircle(circles, hz);
        }

        addHeading(root, "结果");
        result = new TextView(this);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        result.setTypeface(android.graphics.Typeface.MONOSPACE);
        result.setText("还没有跑过测试");
        root.addView(result);

        setContentView(scroll);
    }

    private void addTest(LinearLayout parent, String label, int hz, int size) {
        Button b = addButton(parent, label);
        buttons.add(b);
        b.setOnClickListener(v -> {
            if (socket == null || out == null) {
                toast("先连电脑");
                return;
            }
            if (running) {
                toast("还在跑");
                return;
            }
            new Thread(() -> runTest(label, hz, size), "probe-" + hz).start();
        });
    }

    private void addCircle(LinearLayout parent, int hz) {
        String label = "画圈 " + hz + "Hz（15 秒）";
        Button b = addButton(parent, label);
        buttons.add(b);
        b.setOnClickListener(v -> {
            if (socket == null || out == null) {
                toast("先连电脑");
                return;
            }
            if (running) {
                toast("还在跑");
                return;
            }
            new Thread(() -> runCircle(label, hz), "circle-" + hz).start();
        });
    }

    private void connect(BluetoothDevice device) {
        if (running) {
            toast("还在跑");
            return;
        }
        if (adapter == null) {
            toast("没有蓝牙适配器");
            return;
        }
        status.setText("连接 " + safeName(device) + " …");
        new Thread(() -> {
            BluetoothSocket s = open(device);
            if (s == null) {
                final String why = lastError;
                runOnUiThread(() -> status.setText("连接失败：" + why));
                return;
            }
            try {
                out = s.getOutputStream();
            } catch (Exception e) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // Already dead.
                }
                final String why2 = "拿不到输出流：" + e;
                runOnUiThread(() -> status.setText(why2));
                return;
            }
            socket = s;
            final String label = safeName(device) + " / " + device.getAddress();
            runOnUiThread(() -> status.setText("已连接 " + label));
            say("已连接 " + label);
        }, "connect").start();
    }

    /**
     * Discovery first: an SDP lookup during discovery on some phones fails or takes seconds.
     * Both socket flavours, in the order the CapsLock demo uses, because the secure one is
     * refused on some pairings and the insecure one on others.
     */
    private BluetoothSocket open(BluetoothDevice device) {
        try {
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
            // Cancelling discovery is a courtesy, not a requirement.
        }
        for (int mode = 0; mode < 2; mode++) {
            BluetoothSocket s = null;
            try {
                s = mode == 0
                        ? device.createRfcommSocketToServiceRecord(SERVICE)
                        : device.createInsecureRfcommSocketToServiceRecord(SERVICE);
                if (s == null) {
                    lastError = "socket 为空";
                    continue;
                }
                s.connect();
                return s;
            } catch (Exception e) {
                lastError = (mode == 0 ? "安全" : "不安全") + " socket：" + e;
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                        // Nothing to clean up.
                    }
                }
            }
        }
        return null;
    }

    private void runTest(String label, int hz, int size) {
        running = true;
        setButtons(false);
        say("开始：" + label);

        byte[] frame = new byte[size];
        frame[0] = (byte) (size == 5 ? 0x10 : 0x11);
        long[] durations = new long[MAX_SAMPLES];
        int count = 0;
        long start = System.nanoTime();
        long end = start + DURATION_MS * 1_000_000L;
        long period = hz > 0 ? 1_000_000_000L / hz : 0;
        long next = start;
        long slow5 = 0;
        long slow10 = 0;
        String failure = null;

        try {
            while (true) {
                long now = System.nanoTime();
                if (now >= end) break;
                if (period > 0) {
                    if (next > now) LockSupport.parkNanos(next - now);
                    next += period;
                    if (next < System.nanoTime() - period) next = System.nanoTime();
                }
                if (size == 5) {
                    // Movement frame with a zero delta: 0x10 dx dy seq 0x00.
                    frame[3] = (byte) count;
                } else {
                    frame[2] = (byte) count;
                    frame[3] = (byte) (count >> 8);
                }
                long t0 = System.nanoTime();
                out.write(frame);
                long t1 = System.nanoTime();
                long d = t1 - t0;
                if (count < MAX_SAMPLES) durations[count] = d;
                count++;
                if (d > 5_000_000L) slow5++;
                if (d > 10_000_000L) slow10++;
            }
            out.flush();
        } catch (Exception e) {
            failure = e.toString();
        }

        long elapsed = System.nanoTime() - start;
        String summary = summarise(label, size, count, elapsed, durations, slow5, slow10, failure);
        say(summary);
        sendStats(summary);
        running = false;
        setButtons(true);
    }

    private String summarise(String label, int size, int count, long elapsedNs, long[] d,
                             long slow5, long slow10, String failure) {
        int n = Math.min(count, MAX_SAMPLES);
        long[] copy = Arrays.copyOf(d, Math.max(n, 1));
        Arrays.sort(copy, 0, n);
        double secs = elapsedNs / 1e9;
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(label.replace(" ", "")).append(" bytes=").append(size);
        sb.append(" n=").append(count);
        sb.append(String.format(java.util.Locale.US, " dur=%.2fs rate=%.1f/s", secs, n / secs));
        if (n > 0) {
            sb.append(String.format(java.util.Locale.US, " wmed=%.3fms wp90=%.3fms wmax=%.3fms",
                    copy[n / 2] / 1e6, copy[(int) (n * 0.9)] / 1e6, copy[n - 1] / 1e6));
            sb.append(" slow5=").append(slow5).append(" slow10=").append(slow10);
        }
        if (failure != null) sb.append(" failed=").append(failure.replace(' ', '_'));
        return sb.toString();
    }

    /**
     * Traces the same 220 px circle at 900 px/s for every rate. The angle comes from elapsed
     * real time and each frame carries the rounded movement since the previous one, so
     * rounding cannot accumulate: the emitted position always tracks the exact one.
     */
    private void runCircle(String label, int hz) {
        running = true;
        setButtons(false);
        say("开始：" + label);

        byte[] frame = new byte[5];
        frame[0] = 0x10;
        int maxFrames = (CIRCLE_DURATION_MS / 1000 + 1) * hz;
        long[] durations = new long[Math.min(MAX_SAMPLES, maxFrames)];
        int sentX = (int) Math.round(CIRCLE_RADIUS);
        int sentY = 0;
        int count = 0;
        long start = System.nanoTime();
        long end = start + CIRCLE_DURATION_MS * 1_000_000L;
        long period = 1_000_000_000L / hz;
        long next = start + period;
        long slow5 = 0;
        long slow10 = 0;
        double stepMin = Double.MAX_VALUE;
        double stepMax = 0.0;
        String failure = null;

        try {
            while (true) {
                long now = System.nanoTime();
                if (now >= end) break;
                if (next > now) {
                    LockSupport.parkNanos(next - now);
                    now = System.nanoTime();
                    if (now >= end) break;
                } else if (now - next > period) {
                    // More than one slot behind: drop the missed slots instead of bursting.
                    next = now;
                }
                double angle = CIRCLE_OMEGA * ((now - start) / 1e9);
                int x = (int) Math.round(CIRCLE_RADIUS * Math.cos(angle));
                int y = (int) Math.round(CIRCLE_RADIUS * Math.sin(angle));
                int dx = clamp(x - sentX);
                int dy = clamp(y - sentY);
                sentX += dx;
                sentY += dy;

                frame[1] = (byte) dx;
                frame[2] = (byte) dy;
                frame[3] = (byte) count;
                long t0 = System.nanoTime();
                out.write(frame);
                long t1 = System.nanoTime();
                long d = t1 - t0;
                if (count < durations.length) durations[count] = d;
                count++;
                double step = Math.hypot(dx, dy);
                if (step < stepMin) stepMin = step;
                if (step > stepMax) stepMax = step;
                if (d > 5_000_000L) slow5++;
                if (d > 10_000_000L) slow10++;
                next += period;
            }
            out.flush();
        } catch (Exception e) {
            failure = e.toString();
        }

        long elapsed = System.nanoTime() - start;
        String summary = summariseCircle(label, hz, count, elapsed, durations,
                stepMin, stepMax, slow5, slow10, failure);
        say(summary);
        sendStats(summary);
        running = false;
        setButtons(true);
    }

    private String summariseCircle(String label, int hz, int count, long elapsedNs, long[] d,
                                   double stepMin, double stepMax, long slow5, long slow10,
                                   String failure) {
        int n = Math.min(count, d.length);
        long[] copy = Arrays.copyOf(d, Math.max(n, 1));
        Arrays.sort(copy, 0, n);
        double secs = elapsedNs / 1e9;
        StringBuilder sb = new StringBuilder();
        sb.append("mode=circle").append(hz).append(" bytes=5 hz=").append(hz);
        sb.append(" n=").append(count);
        sb.append(String.format(java.util.Locale.US, " dur=%.2fs rate=%.1f/s", secs, count / secs));
        if (n > 0 && stepMax > 0) {
            sb.append(String.format(java.util.Locale.US, " stepmin=%.1fpx stepmax=%.1fpx",
                    stepMin, stepMax));
            sb.append(String.format(java.util.Locale.US, " wmed=%.3fms wp90=%.3fms wmax=%.3fms",
                    copy[n / 2] / 1e6, copy[(int) (n * 0.9)] / 1e6, copy[n - 1] / 1e6));
            sb.append(" slow5=").append(slow5).append(" slow10=").append(slow10);
        }
        if (failure != null) sb.append(" failed=").append(failure.replace(' ', '_'));
        return sb.toString();
    }

    private static int clamp(int value) {
        if (value < -MAX_DELTA) return -MAX_DELTA;
        return value > MAX_DELTA ? MAX_DELTA : value;
    }

    private void sendStats(String text) {
        try {
            byte[] body = text.getBytes("US-ASCII");
            int len = Math.min(body.length, 250);
            byte[] frame = new byte[2 + len];
            frame[0] = 'S';
            frame[1] = (byte) len;
            System.arraycopy(body, 0, frame, 2, len);
            out.write(frame);
            out.flush();
        } catch (Exception ignored) {
            // The line is on screen already; the socket is only a convenience.
        }
    }

    private void setButtons(boolean enabled) {
        runOnUiThread(() -> {
            for (Button b : buttons) b.setEnabled(enabled);
        });
    }

    private void say(String line) {
        runOnUiThread(() -> {
            transcript.append(line).append('\n');
            result.setText(transcript.toString());
            status.setText(running ? "测试中…" : "空闲");
        });
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private void addHeading(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(4);
        parent.addView(t, lp);
    }

    private void addNote(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        parent.addView(t);
    }

    private Button addButton(LinearLayout parent, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private String safeName(BluetoothDevice d) {
        try {
            String name = d.getName();
            return name == null ? "未命名" : name;
        } catch (SecurityException e) {
            return "未命名";
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
            // Closing a dead socket is not worth a crash.
        }
    }
}
