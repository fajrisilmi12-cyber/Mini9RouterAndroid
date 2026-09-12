package id.my.minirouter;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;

public class MainActivity extends Activity {
    EditText port, localKey, model;
    EditText p1Name, p1Url, p1Key, p1Model;
    EditText p2Name, p2Url, p2Key, p2Model;
    EditText p3Name, p3Url, p3Key, p3Model;
    CheckBox p1Enabled, p2Enabled, p3Enabled, autoBoot;
    TextView status, info;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        port = findViewById(R.id.port);
        localKey = findViewById(R.id.localKey);
        model = findViewById(R.id.model);
        p1Enabled = findViewById(R.id.p1Enabled); p1Name = findViewById(R.id.p1Name); p1Url = findViewById(R.id.p1Url); p1Key = findViewById(R.id.p1Key); p1Model = findViewById(R.id.p1Model);
        p2Enabled = findViewById(R.id.p2Enabled); p2Name = findViewById(R.id.p2Name); p2Url = findViewById(R.id.p2Url); p2Key = findViewById(R.id.p2Key); p2Model = findViewById(R.id.p2Model);
        p3Enabled = findViewById(R.id.p3Enabled); p3Name = findViewById(R.id.p3Name); p3Url = findViewById(R.id.p3Url); p3Key = findViewById(R.id.p3Key); p3Model = findViewById(R.id.p3Model);
        autoBoot = findViewById(R.id.autoBoot);
        status = findViewById(R.id.status);
        info = findViewById(R.id.info);

        load();

        findViewById(R.id.startBtn).setOnClickListener(v -> {
            save();
            stopService(new Intent(this, GatewayService.class));
            startService(new Intent(this, GatewayService.class));
            status.setText("Status: RUNNING");
            info.setText("API: http://IP-HP:" + port.getText() + "/v1\nDashboard: http://IP-HP:" + port.getText() + "/dashboard");
        });

        findViewById(R.id.stopBtn).setOnClickListener(v -> {
            stopService(new Intent(this, GatewayService.class));
            status.setText("Status: STOPPED");
        });
    }

    private void load() {
        SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        port.setText(p.getString("port", "20128"));
        localKey.setText(p.getString("localKey", ""));
        model.setText(p.getString("model", "AGY"));

        p1Enabled.setChecked(p.getBoolean("p1Enabled", true));
        p1Name.setText(p.getString("p1Name", "Nara"));
        p1Url.setText(p.getString("p1Url", p.getString("baseUrl", "https://router.bynara.id/v1")));
        p1Key.setText(p.getString("p1Key", p.getString("providerKey", "")));
        p1Model.setText(p.getString("p1Model", p.getString("model", "AGY")));

        p2Enabled.setChecked(p.getBoolean("p2Enabled", false));
        p2Name.setText(p.getString("p2Name", "OpenRouter"));
        p2Url.setText(p.getString("p2Url", "https://openrouter.ai/api/v1"));
        p2Key.setText(p.getString("p2Key", ""));
        p2Model.setText(p.getString("p2Model", ""));

        p3Enabled.setChecked(p.getBoolean("p3Enabled", false));
        p3Name.setText(p.getString("p3Name", "Provider 3"));
        p3Url.setText(p.getString("p3Url", ""));
        p3Key.setText(p.getString("p3Key", ""));
        p3Model.setText(p.getString("p3Model", ""));

        autoBoot.setChecked(p.getBoolean("autoBoot", true));
    }

    private void save() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("port", port.getText().toString().trim())
            .putString("localKey", localKey.getText().toString().trim())
            .putString("model", model.getText().toString().trim())
            .putBoolean("p1Enabled", p1Enabled.isChecked()).putString("p1Name", p1Name.getText().toString().trim()).putString("p1Url", p1Url.getText().toString().trim()).putString("p1Key", p1Key.getText().toString().trim()).putString("p1Model", p1Model.getText().toString().trim())
            .putBoolean("p2Enabled", p2Enabled.isChecked()).putString("p2Name", p2Name.getText().toString().trim()).putString("p2Url", p2Url.getText().toString().trim()).putString("p2Key", p2Key.getText().toString().trim()).putString("p2Model", p2Model.getText().toString().trim())
            .putBoolean("p3Enabled", p3Enabled.isChecked()).putString("p3Name", p3Name.getText().toString().trim()).putString("p3Url", p3Url.getText().toString().trim()).putString("p3Key", p3Key.getText().toString().trim()).putString("p3Model", p3Model.getText().toString().trim())
            .putBoolean("autoBoot", autoBoot.isChecked())
            .apply();
    }
}
