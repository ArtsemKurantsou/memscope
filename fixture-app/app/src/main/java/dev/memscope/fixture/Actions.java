package dev.memscope.fixture;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/** Plants the memory problems; every result is held in a static field. */
public class Actions {
    public static final String TAG = "MemScopeFixture";

    public static ArrayList<String> dupStrings;
    public static ArrayList<Bitmap> dupBitmaps;
    public static Bitmap bigBitmap;
    public static ArrayList<Object> sparseList;
    public static Node inflateHead;

    public static void run(Context ctx, String actions) {
        if (actions == null) actions = "";
        for (String raw : actions.split(",")) {
            String a = raw.trim();
            if (a.isEmpty()) continue;
            try {
                one(ctx, a);
            } catch (Throwable t) {
                Log.e(TAG, "action failed: " + a, t);
            }
        }
        Log.i(TAG, "ready " + actions);
    }

    private static void one(Context ctx, String a) {
        switch (a) {
            case "leak_activity": {
                Intent i = new Intent(ctx, LeakyActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                break;
            }
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
                for (int i = 0; i < 200; i++) m.put(i, new byte[100 * 1024]);
                Caches.imageCache = m;
                break;
            }
            case "shared": {
                byte[] b = new byte[1024 * 1024];
                SharedA.ref = b;
                SharedB.ref = b;
                break;
            }
            case "grow":
                Growth.list.add(new byte[1024 * 1024]);
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
                    Log.w(TAG, "unknown action: " + a);
                }
        }
    }
}
