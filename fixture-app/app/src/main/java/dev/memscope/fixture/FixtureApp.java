package dev.memscope.fixture;

import android.app.Application;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;

public class FixtureApp extends Application {
    public static byte[] appBlob;

    @Override public void onCreate() {
        super.onCreate();
        appBlob = new byte[10 * 1024 * 1024];
        Intent i = new Intent(this, RemoteService.class);
        try {
            startService(i);
        } catch (RuntimeException e) {
            bindService(i, new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName n, IBinder b) { }
                @Override public void onServiceDisconnected(ComponentName n) { }
            }, BIND_AUTO_CREATE);
        }
    }
}
