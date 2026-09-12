package id.my.minirouter;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;

public class MainActivity extends Activity {
    EditText port, baseUrl, providerKey, model, localKey;
    CheckBox autoBoot;
    TextView status, info;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        port = findViewById(R.id.port);
        baseUrl = findViewById(R.id.baseUrl);
        providerKey = findViewById(R.id.providerKey);
        model = findViewById(R.id.model);
        localKey = findViewById(R.id.localKey);
        autoBoot = findViewById(R.id.autoBoot);
        status = findViewById(R.id.status);
        info = findViewById(R.id.info);

        load();

        findViewById(R.id.startBtn).setOnClickListener(v -> {
            save();
            startService(new Intent(this, GatewayService.class));
            status.setText("Status: RUNNING");
            info.setText("Server aktif. Gunakan http://IP-HP:" + port.getText() + "/v1");
        });

        findViewById(R.id.stopBtn).setOnClickListener(v -> {
            stopService(new Intent(this, GatewayService.class));
            status.setText("Status: STOPPED");
        });

        findViewById(R.id.hermesBtn).setOnClickListener(v -> {
            save();
            startService(new Intent(this, GatewayService.class));
            startActivity(new Intent(this, HermesActivity.class));
        });
    }

    private void load() {
        SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        port.setText(p.getString("port", "20128"));
        baseUrl.setText(p.getString("baseUrl", "https://router.bynara.id/v1"));
        providerKey.setText(p.getString("providerKey", ""));
        model.setText(p.getString("model", "AGY"));
        localKey.setText(p.getString("localKey", ""));
        autoBoot.setChecked(p.getBoolean("autoBoot", true));
    }

    private void save() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("port", port.getText().toString().trim())
            .putString("baseUrl", baseUrl.getText().toString().trim())
            .putString("providerKey", providerKey.getText().toString().trim())
            .putString("model", model.getText().toString().trim())
            .putString("localKey", localKey.getText().toString().trim())
            .putBoolean("autoBoot", autoBoot.isChecked())
            .apply();
    }
}
