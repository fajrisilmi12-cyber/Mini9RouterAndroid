package id.my.picoclaw;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent p = new Intent(context, PicoClawService.class);
            p.putExtra("action", "START");
            context.startService(p);

            Intent r = new Intent(context, RemoteShellService.class);
            r.putExtra("action", "START");
            context.startService(r);
        }
    }
}
