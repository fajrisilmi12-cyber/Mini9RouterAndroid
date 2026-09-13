package id.my.picoclaw;

import android.os.Environment;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class SystemInfo {
    private SystemInfo() {}

    public static JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            long[] mem = readMemInfo();
            long total = mem[0], avail = mem[1], swapTotal = mem[2], swapFree = mem[3];
            long used = total > 0 ? Math.max(0, total - avail) : 0;
            long swapUsed = swapTotal > 0 ? Math.max(0, swapTotal - swapFree) : 0;
            o.put("ram_total_kb", total);
            o.put("ram_available_kb", avail);
            o.put("ram_used_kb", used);
            o.put("ram_used_percent", total > 0 ? (used * 100.0 / total) : 0);
            o.put("swap_total_kb", swapTotal);
            o.put("swap_used_kb", swapUsed);
            o.put("uptime_seconds", readUptimeSeconds());
            o.put("loadavg", readFirstLine("/proc/loadavg"));
            addDisk(o, new File(Environment.getDataDirectory().getAbsolutePath()));
        } catch (Exception e) {
            try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    private static long[] readMemInfo() throws Exception {
        long total=0, avail=0, memFree=0, buffers=0, cached=0, swapTotal=0, swapFree=0;
        BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"));
        String line;
        while ((line = br.readLine()) != null) {
            long v = parseKb(line);
            if (line.startsWith("MemTotal:")) total = v;
            else if (line.startsWith("MemAvailable:")) avail = v;
            else if (line.startsWith("MemFree:")) memFree = v;
            else if (line.startsWith("Buffers:")) buffers = v;
            else if (line.startsWith("Cached:")) cached = v;
            else if (line.startsWith("SwapTotal:")) swapTotal = v;
            else if (line.startsWith("SwapFree:")) swapFree = v;
        }
        br.close();
        if (avail <= 0) avail = memFree + buffers + cached;
        return new long[]{total, avail, swapTotal, swapFree};
    }

    private static long parseKb(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 2) return 0;
        try { return Long.parseLong(p[1]); } catch (Exception e) { return 0; }
    }

    private static long readUptimeSeconds() {
        try {
            String s = readFirstLine("/proc/uptime");
            if (s == null) return 0;
            int p = s.indexOf(' ');
            if (p > 0) s = s.substring(0,p);
            return (long)Double.parseDouble(s);
        } catch (Exception e) { return 0; }
    }

    private static String readFirstLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String s = br.readLine();
            br.close();
            return s == null ? "" : s;
        } catch (Exception e) { return ""; }
    }

    private static void addDisk(JSONObject o, File path) throws Exception {
        StatFs s = new StatFs(path.getAbsolutePath());
        long block = s.getBlockSize();
        long total = (long)s.getBlockCount() * block;
        long free = (long)s.getAvailableBlocks() * block;
        long used = Math.max(0, total - free);
        o.put("disk_total_bytes", total);
        o.put("disk_free_bytes", free);
        o.put("disk_used_bytes", used);
        o.put("disk_used_percent", total > 0 ? (used * 100.0 / total) : 0);
    }
}
