package lv.quietnoise;

import android.app.Application;
import android.os.Build;
import android.util.Log;

/** Keeps crash details on this phone only; nothing is sent over the network. */
public final class QuietNoiseApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                String report = "Quiet Noise " + version + "\n"
                        + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ")\n\n" + Log.getStackTraceString(error);
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putString("lastCrash", report.substring(0, Math.min(report.length(), 16000))).commit();
            } catch (Throwable ignored) {
                // Reporting must never prevent Android's normal crash handling.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(1); }
            }
        });
    }
}
