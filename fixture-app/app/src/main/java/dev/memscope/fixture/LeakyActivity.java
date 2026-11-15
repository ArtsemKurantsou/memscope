package dev.memscope.fixture;

import android.app.Activity;
import android.os.Bundle;

/** Stores itself in a static field and finishes: a classic activity leak. */
public class LeakyActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Leaks.activity = this;
        finish();
    }
}
