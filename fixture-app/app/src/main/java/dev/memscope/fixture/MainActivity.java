package dev.memscope.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    public static byte[] activityBlob;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        activityBlob = new byte[3 * 1024 * 1024];
        TextView tv = new TextView(this);
        tv.setText("MemScope fixture");
        setContentView(tv);
        Actions.run(getApplicationContext(), getIntent().getStringExtra("actions"));
    }
}
