package id.my.picoclaw;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class RemoteShellService extends Service {
    public static final int PORT = 18792;
    public static volatile boolean running = false;
    public static volatile String status = "STOPPED";
    public static volatile String lastClient = "-";

    private final AtomicBoolean alive = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        startServer();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "START" : intent.getStringExtra("action");
        if ("STOP".equals(action)) stopServer(); else startServer();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public static String getOrCreateToken(android.content.Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("remote_shell", MODE_PRIVATE);
        String token = p.getString("token", null);
        if (token != null && token.length() >= 24) return token;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        token = Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        p.edit().putString("token", token).apply();
        return token;
    }

    private synchronized void startServer() {
        if (alive.get()) return;
        alive.set(true);
        status = "STARTING";
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoClawARMv7:RemoteShell");
            wakeLock.acquire();
        } catch (Exception ignored) {}

        acceptThread = new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                running = true;
                status = "LISTENING :" + PORT;
                while (alive.get()) {
                    Socket client = server.accept();
                    new Thread(() -> handleClient(client), "remote-shell-client").start();
                }
            } catch (Exception e) {
                if (alive.get()) status = "ERROR: " + e.getMessage();
            } finally {
                running = false;
                alive.set(false);
                releaseWakeLock();
            }
        }, "remote-shell-accept");
        acceptThread.start();
    }

    private synchronized void stopServer() {
        alive.set(false);
        running = false;
        status = "STOPPED";
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        releaseWakeLock();
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void handleClient(Socket s) {
        lastClient = String.valueOf(s.getRemoteSocketAddress());
        try {
            s.setSoTimeout(120000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            out.println("PicoClaw MiniRemote Shell v0.4");
            out.println("AUTH <token>");
            String auth = in.readLine();
            String expected = "AUTH " + getOrCreateToken(this);
            if (auth == null || !constantTimeEquals(auth.trim(), expected)) {
                out.println("ERR authentication failed");
                return;
            }
            out.println("OK authenticated");
            out.println("Type 'help' for built-in commands. Shell is app-user only, not root.");
            out.print("picoclaw$ "); out.flush();

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) { out.print("picoclaw$ "); out.flush(); continue; }
                if (line.length() > 2048) { out.println("ERR command too long"); out.print("picoclaw$ "); out.flush(); continue; }
                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) { out.println("bye"); break; }
                if ("help".equalsIgnoreCase(line)) {
                    out.println("Built-ins: help, exit, picoclaw status|start|stop|restart|logs");
                    out.println("Other commands run through /system/bin/sh -c inside the app sandbox.");
                } else if (line.startsWith("picoclaw ")) {
                    handleBuiltin(line.substring(10).trim(), out);
                } else {
                    runShell(line, out);
                }
                out.print("picoclaw$ "); out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void handleBuiltin(String cmd, PrintWriter out) {
        if ("status".equals(cmd)) {
            out.println("gateway=" + PicoClawService.status + " running=" + PicoClawService.running);
            out.println("remote=" + status + " client=" + lastClient);
        } else if ("start".equals(cmd)) {
            startService(new Intent(this, PicoClawService.class).putExtra("action", "START"));
            out.println("OK start requested");
        } else if ("stop".equals(cmd)) {
            startService(new Intent(this, PicoClawService.class).putExtra("action", "STOP"));
            out.println("OK stop requested");
        } else if ("restart".equals(cmd)) {
            startService(new Intent(this, PicoClawService.class).putExtra("action", "STOP"));
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            startService(new Intent(this, PicoClawService.class).putExtra("action", "START"));
            out.println("OK restart requested");
        } else if ("logs".equals(cmd)) {
            out.println(PicoClawService.recentLog());
        } else {
            out.println("ERR unknown picoclaw command");
        }
    }

    private void runShell(String cmd, PrintWriter out) {
        Process p = null;
        try {
            File home = new File(getFilesDir(), "picoclaw");
            home.mkdirs();
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .directory(home)
                    .redirectErrorStream(true)
                    .start();
            final Process proc = p;
            Thread killer = new Thread(() -> {
                try { Thread.sleep(15000); } catch (InterruptedException ignored) { return; }
                try { proc.destroy(); } catch (Exception ignored) {}
            });
            killer.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String l;
            int lines = 0;
            while ((l = br.readLine()) != null) {
                out.println(l);
                if (++lines >= 500) { out.println("[output truncated]"); break; }
            }
            try { int code = p.waitFor(); out.println("[exit " + code + "]"); } catch (InterruptedException ignored) {}
            killer.interrupt();
        } catch (Exception e) {
            out.println("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
