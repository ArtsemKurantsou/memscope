package dev.memscope.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Runs in the :remote process and holds a 2 MB static blob. */
public class RemoteService extends Service {
    public static byte[] remoteBlob;

    @Override public void onCreate() { super.onCreate(); remoteBlob = new byte[2 * 1024 * 1024]; }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
}
