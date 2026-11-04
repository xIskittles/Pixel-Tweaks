package com.anton.lockcenter;

import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class AutoBrightnessTile extends TileService {
    @Override public void onStartListening() { refresh(); }
    @Override public void onTileAdded() { refresh(); }

    @Override public void onClick() {
        final boolean auto = isAuto();
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "settings put system screen_brightness_mode " + (auto ? 0 : 1)}).waitFor();
            } catch (Throwable ignored) {}
            try { Thread.sleep(150); } catch (Throwable ignored) {}
            refresh();
        }).start();
    }

    private boolean isAuto() {
        try {
            return Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refresh() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            tile.setState(isAuto() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("Авто-яркость");
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_auto_brightness));
            tile.updateTile();
        } catch (Throwable ignored) {}
    }
}
