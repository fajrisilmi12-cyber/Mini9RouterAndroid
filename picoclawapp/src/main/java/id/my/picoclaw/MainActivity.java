package id.my.picoclaw;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.*;

import java.io.*;

public class MainActivity extends Activity {
    private TextView status, log, path;
    private EditText configEditor;
    private final Handler handler = new Handler();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("PicoClaw ARMv7");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,40);
        root.setBackgroundColor(Color.rgb(247,248,250));
        scroll.addView(root);

        TextView title = text("PicoClaw ARMv7", 26, true);
        root.addView(title);
        TextView sub = text("Native Go core untuk Android 5+ / ARMv7 · tanpa Termux/proot", 14, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        status = text("Status: -", 18, true);
        status.setPadding(0,24,0,4);
        root.addView(status);
        log = text("-", 13, false);
        log.setTextColor(Color.DKGRAY);
        root.addView(log);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0,18,0,12);
        Button start = button("START", 0xFF10A37F);
        Button stop = button("STOP", 0xFFD14B4B);
        row.addView(start, new LinearLayout.LayoutParams(0, 52, 1));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0,52,1); stopLp.leftMargin=12;
        row.addView(stop, stopLp);
        root.addView(row);

        path = text("Gateway: http://<IP-HP>:18790", 14, true);
        root.addView(path);

        TextView cfgTitle = text("config.json", 19, true);
        cfgTitle.setPadding(0,24,0,6);
        root.addView(cfgTitle);

        configEditor = new EditText(this);
        configEditor.setTextSize(12);
        configEditor.setTextColor(Color.WHITE);
        configEditor.setBackgroundColor(Color.rgb(24,29,36));
        configEditor.setPadding(18,16,18,16);
        configEditor.setMinLines(18);
        configEditor.setGravity(android.view.Gravity.TOP|android.view.Gravity.LEFT);
        configEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(configEditor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = button("SAVE CONFIG", 0xFFFF6B35);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56); saveLp.topMargin=14;
        root.addView(save, saveLp);

        TextView note = text("Sesudah mengubah provider/API key, tekan SAVE CONFIG lalu STOP dan START. File disimpan private di aplikasi.", 12, false);
        note.setTextColor(Color.GRAY); note.setPadding(0,10,0,0); root.addView(note);

        setContentView(scroll);
        ensureConfig();
        loadConfig();

        start.setOnClickListener(v -> startService(new Intent(this, PicoClawService.class).putExtra("action","START")));
        stop.setOnClickListener(v -> startService(new Intent(this, PicoClawService.class).putExtra("action","STOP")));
        save.setOnClickListener(v -> saveConfig());

        handler.post(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText("Status: " + PicoClawService.status);
            status.setTextColor(PicoClawService.running ? 0xFF0A8F62 : 0xFFC23B4A);
            log.setText("Last output: " + PicoClawService.lastLine);
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(0xFF111827);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String s, int color) {
        Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setBackgroundColor(color); return b;
    }

    private File configFile() { return new File(new File(getFilesDir(), "picoclaw"), "config.json"); }

    private void ensureConfig() {
        File f=configFile(); if(f.exists()) return;
        f.getParentFile().mkdirs();
        File workspace=new File(f.getParentFile(),"workspace"); workspace.mkdirs();
        String p=workspace.getAbsolutePath().replace("\\","\\\\").replace("\"","\\\"");
        String json="{\n  \"gateway\": {\"host\": \"0.0.0.0\", \"port\": 18790, \"log_level\": \"info\"},\n  \"agents\": {\"defaults\": {\"workspace\": \""+p+"\", \"restrict_to_workspace\": true}},\n  \"providers\": {}\n}\n";
        try { write(f,json); } catch(Exception ignored) {}
    }

    private void loadConfig() {
        try { configEditor.setText(read(configFile())); }
        catch(Exception e) { configEditor.setText("{\n  \"error\": \""+e.getMessage()+"\"\n}"); }
    }

    private void saveConfig() {
        try {
            new org.json.JSONObject(configEditor.getText().toString());
            write(configFile(), configEditor.getText().toString());
            Toast.makeText(this,"Config tersimpan",Toast.LENGTH_SHORT).show();
        } catch(Exception e) {
            Toast.makeText(this,"JSON tidak valid: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private static String read(File f) throws Exception {
        BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"));
        StringBuilder s=new StringBuilder(); String line; while((line=br.readLine())!=null)s.append(line).append('\n'); br.close(); return s.toString();
    }
    private static void write(File f,String s) throws Exception {
        FileOutputStream out=new FileOutputStream(f); out.write(s.getBytes("UTF-8")); out.close();
    }
}
