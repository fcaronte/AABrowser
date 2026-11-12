package com.fcaronte.aabrowser.smartphone;

import android.view.Surface;

interface IShizukuAppLauncher {
    String createDisplay(in Surface surface, int width, int height, int density);
    String updateDisplay(int displayId, in Surface surface, int width, int height, int density);
    String releaseDisplay(int displayId);
    String launchFavoriteApp(String packageName, int displayId);
    String tapOnActiveFavorite(int displayId, int width, int height, int x, int y);
    String swipeOnActiveFavorite(int displayId, int width, int height, int x1, int y1, int x2, int y2, int durationMs);
    String backOnActiveFavorite(int displayId);
}