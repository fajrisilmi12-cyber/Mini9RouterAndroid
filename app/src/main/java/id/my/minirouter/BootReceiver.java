package id.my.minirouter;

import android.content.*;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            boolean auto = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .getBoolean("autoBoot", true);
            if (auto) context.startService(new Intent(context, GatewayService.class));
        }
    }
}
