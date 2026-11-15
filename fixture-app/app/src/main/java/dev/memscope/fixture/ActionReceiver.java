package dev.memscope.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** adb shell am broadcast -a dev.memscope.fixture.ACTION --es actions <list> -p dev.memscope.fixture */
public class ActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Actions.run(ctx.getApplicationContext(), intent.getStringExtra("actions"));
    }
}
