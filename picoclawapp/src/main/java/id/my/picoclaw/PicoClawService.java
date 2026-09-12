package id.my.picoclaw;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.*;
import java.util.Map;

public class PicoClawService extends Service {
    public static volatile boolean running = false;
    public static volatile String status = "STOPPED";
    public static volatile String lastLine = "-";

    private Process process;
    private Thread readerThread;
    private PowerManager.WakeLock wakeLock;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "START" : intent.getStringExtra("action");
        if ("STOP".equals(action)) {
            stopNative();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startNative();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopNative();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private synchronized void startNative() {
        try {
            File home = new File(getFilesDir(), "picoclaw");
            File workspace = new File(home, "workspace");
            File tmp = new File(getCacheDir(), "picoclaw-tmp");
            home.mkdirs(); workspace.mkdirs(); tmp.mkdirs();

            File config = new File(home, "config.json");
            if (!config.exists()) writeDefaultConfig(config, workspace);

            File nativeBin = new File(getApplicationInfo().nativeLibraryDir, "libpicoclaw.so");
            if (!nativeBin.exists()) throw new IOException("libpicoclaw.so tidak ditemukan untuk armeabi-v7a");
            nativeBin.setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder(nativeBin.getAbsolutePath(), "gateway");
            pb.redirectErrorStream(true);
            pb.directory(home);
            Map<String,String> env = pb.environment();
            env.put("PICOCLAW_HOME", home.getAbsolutePath());
            env.put("PICOCLAW_CONFIG", config.getAbsolutePath());
            env.put("HOME", home.getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("PICOCLAW_GATEWAY_HOST", "0.0.0.0");
            env.put("PICOCLAW_GATEWAY_PORT", "18790");

            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoClawARMv7:Gateway");
            wakeLock.acquire();

            process = pb.start();
            running = true;
            status = "RUNNING";
            lastLine = "PicoClaw gateway starting...";

            readerThread = new Thread(() -> {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) lastLine = line;
                    int code = process.waitFor();
                    lastLine = "PicoClaw exited with code " + code;
                    status = "EXITED (" + code + ")";
                } catch (Exception e) {
                    lastLine = e.getClass().getSimpleName() + ": " + e.getMessage();
                    status = "ERROR";
                } finally {
                    running = false;
                    releaseWakeLock();
                }
            }, "picoclaw-output");
            readerThread.start();
        } catch (Exception e) {
            running = false;
            status = "ERROR";
            lastLine = e.getClass().getSimpleName() + ": " + e.getMessage();
            releaseWakeLock();
        }
    }

    private synchronized void stopNative() {
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) {}
            process = null;
        }
        running = false;
        status = "STOPPED";
        releaseWakeLock();
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void writeDefaultConfig(File file, File workspace) throws IOException {
        String p = workspace.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\n" +
                "  \"gateway\": {\"host\": \"0.0.0.0\", \"port\": 18790, \"log_level\": \"info\"},\n" +
                "  \"agents\": {\"defaults\": {\"workspace\": \"" + p + "\", \"restrict_to_workspace\": true}},\n" +
                "  \"providers\": {}\n" +
                "}\n";
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(json.getBytes("UTF-8"));
        fos.close();
    }
}
