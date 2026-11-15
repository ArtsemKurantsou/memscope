package dev.memscope.fixture;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Plants the memory problems; every result is held in a static field. */
public class Actions {
    public static final String TAG = "MemScopeFixture";

    public static ArrayList<String> dupStrings;
    public static ArrayList<Bitmap> dupBitmaps;
    public static Bitmap bigBitmap;
    public static ArrayList<Object> sparseList;
    public static Node inflateHead;

    private static final long LEAK_TIMEOUT_MS = 5000;
    private static Runnable leakTimeout;
    private static Runnable leakFinish;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Must be called on the main thread. Logs "ready" only when every action has really finished. */
    public static void run(Context ctx, String actions) {
        if (actions == null) actions = "";
        final String all = actions;
        final List<String> failures = new ArrayList<>();
        boolean leak = false;
        for (String raw : actions.split(",")) {
            String a = raw.trim();
            if (a.isEmpty()) continue;
            if (a.equals("leak_activity")) { leak = true; continue; }
            try {
                one(ctx, a);
            } catch (Throwable t) {
                failures.add(a + ": " + t);
                Log.e(TAG, "action failed: " + a, t);
            }
        }
        if (!leak) { finish(all, failures); return; }
        leakFinish = () -> { MAIN.removeCallbacks(leakTimeout); leakFinish = null; finish(all, failures); };
        leakTimeout = () -> {
            leakFinish = null;
            failures.add("leak_activity: LeakyActivity did not start and finish within " + LEAK_TIMEOUT_MS + " ms (background launch blocked?)");
            finish(all, failures);
        };
        MAIN.postDelayed(leakTimeout, LEAK_TIMEOUT_MS);
        try {
            Intent i = new Intent(ctx, LeakyActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            MAIN.removeCallbacks(leakTimeout);
            leakFinish = null;
            failures.add("leak_activity: " + t);
            finish(all, failures);
        }
    }

    /** Called by LeakyActivity.onDestroy after Leaks.activity was set. */
    static void leakDone() {
        Runnable r = leakFinish;
        if (r != null) r.run();
    }

    private static void finish(String all, List<String> failures) {
        if (failures.isEmpty()) { Log.i(TAG, "ready " + all); return; }
        for (String f : failures) Log.e(TAG, "failed " + f);
    }

    private static void one(Context ctx, String a) {
        switch (a) {
            case "dup_strings": {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 200; i++) sb.append((char) ('a' + i % 26));
                String v = sb.toString();
                ArrayList<String> l = new ArrayList<>();
                for (int i = 0; i < 2000; i++) l.add(new String(v.toCharArray()));
                dupStrings = l;
                break;
            }
            case "dup_bitmaps": {
                ArrayList<Bitmap> l = new ArrayList<>();
                for (int i = 0; i < 20; i++) l.add(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
                dupBitmaps = l;
                break;
            }
            case "big_bitmap":
                bigBitmap = Bitmap.createBitmap(4000, 4000, Bitmap.Config.ARGB_8888);
                break;
            case "sparse_list": {
                ArrayList<Object> l = new ArrayList<>(1_000_000);
                l.add("a"); l.add("b"); l.add("c");
                sparseList = l;
                break;
            }
            case "static_cache": {
                HashMap<Integer, byte[]> m = new HashMap<>();
                for (int i = 0; i < 200; i++) m.put(i, Fill.bytes(100 * 1024));
                Caches.imageCache = m;
                break;
            }
            case "shared": {
                byte[] b = Fill.bytes(1024 * 1024);
                SharedA.ref = b;
                SharedB.ref = b;
                break;
            }
            case "grow":
                Growth.list.add(Fill.bytes(1024 * 1024));
                break;
            default:
                if (a.startsWith("inflate:")) {
                    int mb = Integer.parseInt(a.substring(8).trim());
                    // object ~ 16 header/fields + array 16+32 = ~64 bytes
                    long count = mb * 1024L * 1024L / 64L;
                    Node head = inflateHead;
                    for (long i = 0; i < count; i++) head = new Node(head);
                    inflateHead = head;
                } else {
                    throw new IllegalArgumentException("unknown action");
                }
        }
    }
}
