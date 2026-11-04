package com.anton.lockcenter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // ======== НАСТРОЙКИ ЭКРАНА БЛОКИРОВКИ ========
    public static volatile float clockScaleX = 1.60f;
    public static volatile float clockScaleY = 1.30f;
    public static volatile int dateExtraGapPx = -20;
    public static volatile float dateScaleX = 1.30f;
    public static volatile float dateScaleY = 1.15f;
    public static volatile float largeClockScaleX = 1.35f;
    public static volatile float largeClockScaleY = 1.05f;
    public static volatile int cardTopMarginPx = -50;
    public static volatile int notifTopMarginPx = 0;

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static final String CLOCK_SUFFIX = ".FlexClockTextView";
    private static final String LARGE_CLOCK_SUFFIX = ".FlexClockViewGroup";
    private static final String ROOT_SUFFIX = ".KeyguardRootView";

    private static int placeholderId = 0;
    private static int cardId = 0;
    private static boolean receiverRegistered = false;
    private static long lastCheckTime = 0;

    public static void loadConfig(Context context) {
        if (context != null) {
            try {
                String cfg = Settings.Global.getString(context.getContentResolver(), "lockcenter_cfg");
                if (cfg != null && !cfg.isEmpty()) {
                    parseConfig(cfg);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        File f = new File("/data/user_de/0/com.android.systemui/lockcenter.conf");
        if (!f.exists()) f = new File("/data/data/com.android.systemui/lockcenter.conf");
        if (f.exists() && f.canRead()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        applyKeyValue(parts[0].trim(), parts[1].trim());
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void parseConfig(String cfg) {
        String[] pairs = cfg.contains(",") ? cfg.split(",") : cfg.split(";");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                applyKeyValue(kv[0].trim(), kv[1].trim());
            }
        }
    }

    private static void applyKeyValue(String k, String v) {
        try {
            if ("clock_scale_x".equals(k)) clockScaleX = Float.parseFloat(v);
            else if ("clock_scale_y".equals(k)) clockScaleY = Float.parseFloat(v);
            else if ("date_extra_gap_px".equals(k)) dateExtraGapPx = Integer.parseInt(v);
            else if ("date_scale_x".equals(k)) dateScaleX = Float.parseFloat(v);
            else if ("date_scale_y".equals(k)) dateScaleY = Float.parseFloat(v);
            else if ("large_clock_scale_x".equals(k)) largeClockScaleX = Float.parseFloat(v);
            else if ("large_clock_scale_y".equals(k)) largeClockScaleY = Float.parseFloat(v);
            else if ("card_top_margin_px".equals(k)) cardTopMarginPx = Integer.parseInt(v);
            else if ("notif_top_margin_px".equals(k)) notifTopMarginPx = Integer.parseInt(v);
        } catch (Throwable ignored) {}
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.phone".equals(lpparam.packageName)) {
            try {
                initCarrierWfcFix(lpparam);
            } catch (Throwable t) {
                XposedBridge.log("LC wfc ERR " + t);
            }
        }
        if ("com.android.settings".equals(lpparam.packageName)
                || "com.google.android.apps.nbu.files".equals(lpparam.packageName)
                || "com.google.android.documentsui".equals(lpparam.packageName)) {
            try {
                initBrokenIconFix(lpparam);
            } catch (Throwable t) {
                XposedBridge.log("LC iconfix ERR " + t);
            }
        }
        if ("com.android.systemui".equals(lpparam.packageName)) {
            initSystemUI(lpparam);
        }
    }

    // =========================================================================
    //                            SYSTEM UI (LOCKSCREEN)
    // =========================================================================

    private static void reorderMobileType(View v) {
        try {
            if (!(v instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) v;
            int id = root.getResources().getIdentifier(
                    "mobile_type_container", "id", "com.android.systemui");
            if (id == 0) return;
            View typeContainer = root.findViewById(id);
            if (typeContainer == null) return;
            ViewGroup parent = (ViewGroup) typeContainer.getParent();
            if (parent == null) return;
            int before = parent.indexOfChild(typeContainer);
            if (before != parent.getChildCount() - 1) {
                parent.removeView(typeContainer);
                parent.addView(typeContainer);
            }
            float density = root.getResources().getDisplayMetrics().density;
            if (typeContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) typeContainer.getLayoutParams();
                lp.setMarginStart(Math.round(5.5f * density));
                lp.setMarginEnd(0);
                typeContainer.setLayoutParams(lp);
            }
            typeContainer.setTranslationX(2f);
            if (typeContainer instanceof ViewGroup) {
                ((ViewGroup) typeContainer).setClipChildren(false);
                ((ViewGroup) typeContainer).setClipToPadding(false);
            }
            parent.setClipChildren(false);
            root.setClipChildren(false);
            final View tc = typeContainer;
            parent.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        ViewGroup p = (ViewGroup) tc.getParent();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < p.getChildCount(); i++) {
                            View ch = p.getChildAt(i);
                            sb.append(i).append(':').append(ch.getId() == tc.getId() ? "TYPE" : String.valueOf(ch.getId())).append(' ');
                        }
                        XposedBridge.log("LC typeLater: vis=" + tc.getVisibility()
                                + " w=" + tc.getWidth() + " h=" + tc.getHeight()
                                + " tx=" + tc.getTranslationX() + " order=[" + sb + "]");
                    } catch (Throwable ignored) {}
                }
            }, 10000);
            int typeId = root.getResources().getIdentifier(
                    "mobile_type", "id", "com.android.systemui");
            if (typeId != 0) {
                View typeView = root.findViewById(typeId);
                if (typeView != null) {
                    typeView.setPadding(0, typeView.getPaddingTop(), 0, typeView.getPaddingBottom());
                    ViewGroup.LayoutParams tlp = typeView.getLayoutParams();
                    tlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    typeView.setLayoutParams(tlp);
                    if (typeView instanceof android.widget.ImageView) {
                        ((android.widget.ImageView) typeView).setAdjustViewBounds(true);
                    }
                }
            }
            XposedBridge.log("LC mobileType: idx " + before + "->" + parent.indexOfChild(typeContainer)
                    + " vis=" + typeContainer.getVisibility()
                    + " w=" + typeContainer.getWidth() + " h=" + typeContainer.getHeight()
                    + " ms=" + (typeContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                        ? ((ViewGroup.MarginLayoutParams) typeContainer.getLayoutParams()).getMarginStart() : -1));
        } catch (Throwable t) {
            XposedBridge.log("LC mobileType ERR " + t);
        }
    }

    private static void hookMobileType(ClassLoader cl) {
        String[] classes = {
                "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
                "com.android.systemui.statusbar.StatusBarMobileView"
        };
        for (String name : classes) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            XposedBridge.log("LC mobileType: " + name + " found=" + (c != null));
            if (c == null) continue;
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    reorderMobileType((View) param.thisObject);
                }
            });
            for (String m : new String[]{"configureLayoutForNewStatusBarIcons", "initView", "updateState"}) {
                try {
                    XposedBridge.hookAllMethods(c, m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            reorderMobileType((View) param.thisObject);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void hookSplitTiles(ClassLoader cl) {
        for (String tn : new String[]{
                "com.android.systemui.qs.tiles.WifiTile",
                "com.android.systemui.qs.tiles.MobileDataTile"}) {
            Class<?> tc = XposedHelpers.findClassIfExists(tn, cl);
            XposedBridge.log("LC split: " + tn + " found=" + (tc != null));
            if (tc == null) continue;
            try {
                XposedHelpers.findAndHookMethod(tc, "isAvailable", new XC_MethodReplacement() {
                    @Override protected Object replaceHookedMethod(MethodHookParam p) {
                        return Boolean.TRUE;
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("LC split isAvailable ERR " + t);
            }
        }

        Class<?> migration = XposedHelpers.findClassIfExists(
                "com.android.systemui.qs.pipeline.shared.InternetTileMigration", cl);
        XposedBridge.log("LC split: migration found=" + (migration != null));
        if (migration != null) {
            for (String m : new String[]{"migrateInternetTile"}) {
                try {
                    XposedBridge.hookAllMethods(migration, m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam p) {
                            return p.args[0];
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log("LC split migration ERR " + t);
                }
            }
        }

        Class<?> secure = XposedHelpers.findClassIfExists(
                "com.android.systemui.util.settings.impl.SecureSettingsImpl", cl);
        if (secure != null) {
            try {
                XposedHelpers.findAndHookMethod(secure, "getStringForUser",
                        int.class, String.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!"sysui_qs_tiles".equals(param.args[1])) return;
                                    Object r = param.getResult();
                                    if (!(r instanceof String)) return;
                                    String v = (String) r;
                                    if (v.contains("wifi") && v.contains("cell")) return;
                                    if (v.contains("internet")) {
                                        v = v.replace("internet", "wifi,cell");
                                    } else {
                                        v = "wifi,cell," + v;
                                    }
                                    param.setResult(v);
                                    XposedBridge.log("LC split: tiles patched -> " + v.substring(0, Math.min(60, v.length())));
                                } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log("LC split secure ERR " + t);
            }
        }
    }

    private static android.content.Context tileContext(Object tile) {
        try {
            return (android.content.Context) XposedHelpers.getObjectField(tile, "mContext");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void toggleWifi(Object tile) {
        try {
            android.content.Context ctx = tileContext(tile);
            if (ctx == null) return;
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) ctx.getApplicationContext()
                            .getSystemService(android.content.Context.WIFI_SERVICE);
            boolean target = !wm.isWifiEnabled();
            wm.setWifiEnabled(target);
            XposedBridge.log("LC tile: wifi -> " + target);
        } catch (Throwable t) {
            XposedBridge.log("LC tile wifi ERR " + t);
        }
    }

    private static volatile Object internetDialogManager = null;
    private static volatile Object lastDataTile = null;

    private static Object resolveDialogManager() {
        Object mgr = internetDialogManager;
        if (mgr != null) return mgr;
        Object tile = lastDataTile;
        if (tile == null) return null;
        try {
            Object interactor = XposedHelpers.getObjectField(tile, "userActionInteractor");
            Object m = XposedHelpers.getObjectField(interactor, "internetDialogManager");
            if (m != null) {
                internetDialogManager = m;
                XposedBridge.log("LC tile: dialog manager resolved lazily");
            }
            return m;
        } catch (Throwable t) {
            XposedBridge.log("LC tile manager lazy ERR " + t);
            return null;
        }
    }

    private static void toggleData(Object tile) {
        try {
            android.content.Context ctx = tileContext(tile);
            if (ctx == null) return;
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) ctx.getSystemService(
                            android.content.Context.TELEPHONY_SERVICE);
            Boolean enabled = null;
            try {
                Object r = XposedHelpers.callMethod(tm, "isDataEnabled");
                if (r instanceof Boolean) enabled = (Boolean) r;
            } catch (Throwable ignored) {}
            if (enabled == null) {
                int cur = android.provider.Settings.Global.getInt(
                        ctx.getContentResolver(), "mobile_data", 0);
                enabled = cur != 0;
            }
            boolean target = !enabled;
            try {
                XposedHelpers.callMethod(tm, "setDataEnabled", target);
            } catch (Throwable t1) {
                XposedHelpers.callMethod(tm, "setDataEnabledForReason", 0, target);
            }
            XposedBridge.log("LC tile: data " + enabled + " -> " + target);
            XposedHelpers.callMethod(tile, "refreshState");
        } catch (Throwable t) {
            XposedBridge.log("LC tile data ERR " + t);
        }
    }

    private static void openInternetPanel(Object tile, Object expandable) {
        try {
            final Object mgr = resolveDialogManager();
            if (mgr == null) {
                android.content.Context ctx = tileContext(tile);
                if (ctx == null) return;
                Intent i = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                XposedBridge.log("LC tile: fallback wifi settings opened");
                return;
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        try {
                            XposedHelpers.callMethod(mgr, "destroyDialog");
                        } catch (Throwable ignored) {}
                        XposedHelpers.callMethod(mgr, "create", true, true, expandable);
                        XposedBridge.log("LC tile: internet dialog opened (main) expandable=" + (expandable != null));
                    } catch (Throwable t) {
                        XposedBridge.log("LC tile dialog ERR " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LC tile panel ERR " + t);
        }
    }

    private static void hookInternetDialogManager(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(
                    "com.android.systemui.qs.tiles.dialog.InternetDialogManager", cl);
            XposedBridge.log("LC dialog manager found=" + (c != null));
            if (c == null) return;
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    internetDialogManager = param.thisObject;
                    XposedBridge.log("LC dialog manager captured");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LC dialog manager ERR " + t);
        }
    }

    private static void hookLongClickEntry(ClassLoader cl) {
        try {
            Class<?> base = XposedHelpers.findClassIfExists(
                    "com.android.systemui.qs.tileimpl.QSTileImpl", cl);
            if (base == null) return;
            XposedBridge.hookAllMethods(base, "longClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String name = param.thisObject.getClass().getSimpleName();
                        if (name.contains("WifiTile") || name.contains("MobileDataTile")) {
                            XposedBridge.log("LC tile: LONG entry " + name);
                            openInternetPanel(param.thisObject, param.args.length > 0 ? param.args[0] : null);
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LC longClick ERR " + t);
        }
    }

    private static final java.util.WeakHashMap<Object, Boolean> confirmDialogs = new java.util.WeakHashMap<>();

    private static void hookDataDialog(ClassLoader cl) {
        try {
            Class<?> dialogCls = android.app.Dialog.class;
            XposedBridge.hookAllMethods(dialogCls, "setTitle", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        android.app.Dialog d = (android.app.Dialog) param.thisObject;
                        android.content.Context c = d.getContext();
                        int id = c.getResources().getIdentifier(
                                "mobile_data_enable_title", "string", "com.android.systemui");
                        if (id == 0) return;
                        String target = c.getString(id);
                        if (target.contentEquals((CharSequence) param.args[0])) {
                            confirmDialogs.put(d, Boolean.TRUE);
                            XposedBridge.log("LC tile: confirm dialog marked");
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(dialogCls, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (confirmDialogs.remove(param.thisObject) == null) return;
                        android.app.Dialog d = (android.app.Dialog) param.thisObject;
                        android.content.Context c = d.getContext();
                        android.telephony.TelephonyManager tm =
                                (android.telephony.TelephonyManager) c.getSystemService(
                                        android.content.Context.TELEPHONY_SERVICE);
                        try {
                            XposedHelpers.callMethod(tm, "setDataEnabled", true);
                        } catch (Throwable t1) {
                            XposedHelpers.callMethod(tm, "setDataEnabledForReason", 0, true);
                        }
                        XposedBridge.log("LC tile: confirm dialog auto-accepted");
                        param.setResult(null);
                    } catch (Throwable t) {
                        XposedBridge.log("LC tile auto-confirm ERR " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LC tile dialog hook ERR " + t);
        }
    }

    private static void hookTileBehavior(ClassLoader cl) {
        Class<?> wifiTile = XposedHelpers.findClassIfExists(
                "com.android.systemui.qs.tiles.WifiTile", cl);
        Class<?> dataTile = XposedHelpers.findClassIfExists(
                "com.android.systemui.qs.tiles.MobileDataTile", cl);

        if (dataTile != null) {
            XposedBridge.log("LC tile hooks: data tile found");
            XposedBridge.hookAllMethods(dataTile, "handleUpdateState", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object st = param.args.length > 0 ? param.args[0] : null;
                    if (st == null) return;
                    lastDataTile = param.thisObject;
                    try { XposedHelpers.setBooleanField(st, "handlesLongClick", true); } catch (Throwable ignored) {}
                    try { XposedHelpers.setBooleanField(st, "handlesSecondaryClick", true); } catch (Throwable ignored) {}
                }
            });
        }

        if (wifiTile != null) {
            XposedBridge.log("LC tile hooks: wifi tile found");
            try {
                Class<?> mapper = XposedHelpers.findClassIfExists(
                        "com.android.systemui.qs.tiles.impl.wifi.ui.mapper.WifiTileMapper", cl);
                XposedBridge.log("LC tile: wifi mapper found=" + (mapper != null));
                if (mapper != null) {
                    XposedBridge.hookAllMethods(mapper, "map", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object st = param.getResult();
                                if (st == null) return;
                                java.util.Set current = (java.util.Set) XposedHelpers
                                        .getObjectField(st, "supportedActions");
                                Class<?> ua = XposedHelpers.findClass(
                                        "com.android.systemui.qs.tiles.base.shared.model.QSTileState$UserAction",
                                        st.getClass().getClassLoader());
                                Object longClick = XposedHelpers.getStaticObjectField(ua, "LONG_CLICK");
                                java.util.Set<Object> copy = new java.util.HashSet<>(current);
                                copy.add(longClick);
                                XposedHelpers.setObjectField(st, "supportedActions", copy);
                                XposedBridge.log("LC tile: wifi LONG_CLICK added");
                            } catch (Throwable t) {
                                XposedBridge.log("LC tile mapper ERR " + t);
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                XposedBridge.log("LC tile mapper hook ERR " + t);
            }
            XposedBridge.hookAllMethods(wifiTile, "handleSecondaryClick", new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param) {
                    try {
                        android.content.Context ctx = tileContext(param.thisObject);
                        if (ctx == null) return null;
                        android.net.wifi.WifiManager wm =
                                (android.net.wifi.WifiManager) ctx.getApplicationContext()
                                        .getSystemService(android.content.Context.WIFI_SERVICE);
                        boolean target = !wm.isWifiEnabled();
                        boolean ok = wm.setWifiEnabled(target);
                        XposedBridge.log("LC tile: wifi SECONDARY real -> " + target + " ok=" + ok);
                        XposedHelpers.callMethod(param.thisObject, "refreshState");
                    } catch (Throwable t) {
                        XposedBridge.log("LC tile wifi ERR " + t);
                    }
                    return null;
                }
            });
            XposedBridge.hookAllMethods(wifiTile, "handleClick", new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param) {
                    Object exp = param.args.length > 0 ? param.args[0] : null;
                    XposedBridge.log("LC tile: wifi CLICK open panel exp=" + (exp != null));
                    openInternetPanel(param.thisObject, exp);
                    return null;
                }
            });
            XposedBridge.hookAllMethods(wifiTile, "handleLongClick", new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param) {
                    Object exp = param.args.length > 0 ? param.args[0] : null;
                    XposedBridge.log("LC tile: wifi LONG open panel exp=" + (exp != null));
                    openInternetPanel(param.thisObject, exp);
                    return null;
                }
            });
            XposedBridge.hookAllMethods(wifiTile, "handleUpdateState", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object st = param.args.length > 0 ? param.args[0] : null;
                    if (st == null) return;
                    try {
                        XposedHelpers.setBooleanField(st, "handlesLongClick", true);
                    } catch (Throwable ignored) {}
                    try {
                        android.content.Context ctx = tileContext(param.thisObject);
                        if (ctx == null) return;
                        int tileState = -1;
                        try { tileState = XposedHelpers.getIntField(st, "state"); } catch (Throwable ignored) {}
                        boolean off = tileState == 1;
                        if (!off) {
                            android.net.wifi.WifiManager wm =
                                    (android.net.wifi.WifiManager) ctx.getApplicationContext()
                                            .getSystemService(android.content.Context.WIFI_SERVICE);
                            off = !wm.isWifiEnabled();
                        }
                        if (!off) return;
                        int id = ctx.getResources().getIdentifier(
                                "ic_signal_wifi_off", "drawable", "com.android.systemui");
                        if (id == 0) return;
                        Class<?> resIcon = XposedHelpers.findClass(
                                "com.android.systemui.qs.tileimpl.QSTileImpl$ResourceIcon",
                                param.thisObject.getClass().getClassLoader());
                        Object icon = XposedHelpers.callStaticMethod(resIcon, "get", id);
                        XposedHelpers.setObjectField(st, "icon", icon);
                        XposedBridge.log("LC tile: wifi off icon applied");
                    } catch (Throwable t) {
                        XposedBridge.log("LC tile icon ERR " + t);
                    }
                }
            });
        }

    }

    private static volatile float appliedScale = -1f;
    private static volatile java.lang.ref.WeakReference<android.view.View> cachedOse = null;

    private static final java.util.Set<android.app.Activity> receiverOwners =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static final java.util.WeakHashMap<android.view.View, Integer> baseWidths =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<android.view.View, Runnable> pendingSizes =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<android.view.View, Integer> baseHeights =
            new java.util.WeakHashMap<>();
    private static volatile java.lang.ref.WeakReference<android.view.ViewGroup> hotseatRef = null;
    private static volatile String dockBgClass = null;
    private static volatile int lastHsW = 0;
    private static volatile int lastHsH = 0;
    private static volatile long lastRelayoutMs = 0L;
    private static final java.util.WeakHashMap<android.view.View, Long> lastApplyMs =
            new java.util.WeakHashMap<>();

    private static void initLauncher(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        XposedBridge.log("LC launcher: init");
        try {
            try {
                Class<?> oseCls = XposedHelpers.findClassIfExists(
                        "com.android.launcher3.qsb.OseWidgetView", cl);
                if (oseCls != null) {
                    XposedBridge.hookAllMethods(oseCls, "onLayout", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.view.View v = (android.view.View) param.thisObject;
                                float sc = readQsbScale(v.getContext());
                                if (Math.abs(sc - 1.0f) < 0.001f) return;
                                Integer bw = baseWidths.get(v);
                                Integer bh = baseHeights.get(v);
                                int baseW = bw != null ? bw : 1233;
                                int baseH = bh != null ? bh : 221;
                                int want = Math.min(Math.round(baseW * sc),
                                        Math.round(v.getResources().getDisplayMetrics().widthPixels * 0.96f));
                                if (v.getWidth() == want) return;
                                int left = v.getLeft() - (want - v.getWidth()) / 2;
                                v.layout(left, v.getTop(), left + want, v.getTop() + baseH);
                                XposedBridge.log("LC launcher: ose relaid " + want);
                            } catch (Throwable ignored) {}
                        }
                    });
                    XposedBridge.log("LC launcher: OseWidgetView.onLayout hooked");
                } else {
                    XposedBridge.log("LC launcher: OseWidgetView not found");
                }
            } catch (Throwable t) {
                XposedBridge.log("LC launcher: ose layout hook ERR " + t);
            }
            Class<?> hotseat = XposedHelpers.findClassIfExists(
                    "com.android.launcher3.Hotseat", cl);
            XposedBridge.log("LC launcher: Hotseat=" + (hotseat != null));
            XposedBridge.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        android.app.Activity a = (android.app.Activity) param.thisObject;
                        if (!a.getClass().getName().contains("nexuslauncher")) return;
                        new android.os.Handler(a.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    StringBuilder sb = new StringBuilder();
                                    final android.view.View decor = a.getWindow().getDecorView();
                                    hookGlassPredicate(decor);
                                    for (int d : new int[]{150, 400, 800, 1300, 2000}) {
                                        new android.os.Handler(a.getMainLooper()).postDelayed(new Runnable() {
                                            @Override public void run() {
                                                try {
                                                    android.view.View g = findGlassView(decor);
                                                    if (g != null) g.invalidate();
                                                } catch (Throwable ignored) {}
                                            }
                                        }, d);
                                    }
                                    sb.append("dock views:\n");
                                    collectBg(a.getWindow().getDecorView(), sb, 0);
                                    XposedBridge.log("LC bg\n" + sb);
                                } catch (Throwable ignored) {}
                            }
                        }, 1200);
                        final android.view.ViewGroup hsBg = hotseatRef != null ? hotseatRef.get() : null;
                        if (hsBg != null) {
                            try {
                                android.graphics.drawable.Drawable bg = hsBg.getBackground();
                                if (bg instanceof android.graphics.drawable.DrawableWrapper) {
                                    android.graphics.drawable.Drawable inner =
                                            ((android.graphics.drawable.DrawableWrapper) bg).getDrawable();
                                    if (inner != null && inner.getClass().getName().equals("sb")) {
                                        inner = ((android.graphics.drawable.DrawableWrapper) inner).getDrawable();
                                    }
                                    if (inner != null) {
                                        hsBg.setBackground(inner);
                                        hsBg.requestLayout();
                                        XposedBridge.log("LC launcher: bg unwrapped");
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("LC launcher: unwrap ERR " + t);
                            }
                        }
                        if (hsBg != null) {
                            for (int d : new int[]{300, 700, 1200, 2000}) {
                                new android.os.Handler(a.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            android.graphics.drawable.Drawable bg = hsBg.getBackground();
                                            if (bg == null) return;
                                            int w = hsBg.getWidth(), h = hsBg.getHeight();
                                            if (w <= 0 || h <= 0) return;
                                            android.graphics.Rect b = bg.getBounds();
                                            if (b.width() < w - 2 || b.height() < h - 2) {
                                                bg.setBounds(0, 0, w, h);
                                                hsBg.invalidate();
                                                XposedBridge.log("LC launcher: bg fixed "
                                                        + b.toShortString() + " -> " + w + "x" + h);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }, d);
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastRelayoutMs < 1500) return;
                        lastRelayoutMs = now;
                        new android.os.Handler(a.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                android.view.ViewGroup hs = hotseatRef != null ? hotseatRef.get() : null;
                                if (hs != null) {
                                    hs.requestLayout();
                                    XposedBridge.log("LC launcher: resume relayout");
                                }
                            }
                        }, 700);
                    } catch (Throwable ignored) {}
                }
            });
            if (hotseat == null) return;
            XposedBridge.hookAllMethods(hotseat, "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final android.view.ViewGroup hs = (android.view.ViewGroup) param.thisObject;
                        hotseatRef = new java.lang.ref.WeakReference<>(hs);
                        try {
                            android.graphics.drawable.Drawable bg = hs.getBackground();
                            if (bg != null && dockBgClass == null) {
                                dockBgClass = bg.getClass().getName();
                                XposedBridge.log("LC launcher: dockBg=" + dockBgClass);
                            }
                        } catch (Throwable ignored) {}
                        try {
                            android.graphics.drawable.Drawable bg2 = hs.getBackground();
                            if (bg2 != null && hs.getWidth() > 0 && hs.getHeight() > 0) {
                                android.graphics.Rect b2 = bg2.getBounds();
                                if (b2.width() < hs.getWidth() - 2 || b2.height() < hs.getHeight() - 2) {
                                    bg2.setBounds(0, 0, hs.getWidth(), hs.getHeight());
                                    hs.invalidate();
                                    XposedBridge.log("LC launcher: bg fix layout " + b2.toShortString());
                                }
                            }
                        } catch (Throwable ignored) {}
                        final android.view.View ose = findOse(hs);
                        if (ose == null) return;
                        try {
                            if (ose.getVisibility() != android.view.View.VISIBLE) {
                                ose.setVisibility(android.view.View.VISIBLE);
                                XposedBridge.log("LC launcher: ose was hidden -> visible");
                            }
                        } catch (Throwable ignored) {}
                        final float sc = readQsbScale(hs.getContext());
                        try {
                            if (!baseWidths.containsKey(ose) && ose.getWidth() > 0) {
                                baseWidths.put(ose, ose.getWidth());
                                baseHeights.put(ose, ose.getHeight());
                                XposedBridge.log("LC launcher: base " + ose.getWidth() + "x" + ose.getHeight());
                            }
                            Integer bw0 = baseWidths.get(ose);
                            Integer bh0 = baseHeights.get(ose);
                            if (bw0 != null && bh0 != null && bh0 > 0 && Math.abs(sc - 1.0f) > 0.001f) {
                                int want = Math.min(Math.round(bw0 * sc),
                                        Math.round(hs.getResources().getDisplayMetrics().widthPixels * 0.96f));
                                if (ose.getWidth() != want) {
                                    try {
                                        android.view.ViewGroup.LayoutParams lp = ose.getLayoutParams();
                                        if (lp != null && lp.width != want) {
                                            lp.width = want;
                                            ose.setLayoutParams(lp);
                                        }
                                    } catch (Throwable ignored) {}
                                    final android.view.View oseF = ose;
                                    final int wantF = want;
                                    final int hF = bh0;
                                    final android.view.ViewGroup hsF = hs;
                                    if (!sizePosted) {
                                        sizePosted = true;
                                        hs.post(new Runnable() {
                                            @Override public void run() {
                                                sizePosted = false;
                                                try {
                                                    android.view.ViewParent vpF = oseF.getParent();
                                                    int left = hsF.getWidth() - wantF;
                                                    left = left / 2;
                                                    oseF.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                                                                    wantF, android.view.View.MeasureSpec.EXACTLY),
                                                            android.view.View.MeasureSpec.makeMeasureSpec(
                                                                    hF, android.view.View.MeasureSpec.EXACTLY));
                                                    oseF.layout(left, oseF.getTop(), left + wantF, oseF.getTop() + hF);
                                                    XposedBridge.log("LC launcher: sized " + wantF + "x" + hF);
                                                } catch (Throwable ignored) {}
                                            }
                                        });
                                    }
                                }
                            }
                            if (!layoutListenerAdded) {
                                layoutListenerAdded = true;
                                final android.view.View root0 = hs.getRootView();
                                ose.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener() {
                                    @Override public void onLayoutChange(android.view.View v, int l, int t, int r, int b,
                                                                         int ol, int ot, int orr, int ob) {
                                        try {
                                            android.view.View g = findGlassView(root0);
                                            if (g != null) g.invalidate();
                                        } catch (Throwable ignored) {}
                                    }
                                });
                                XposedBridge.log("LC launcher: layout listener added");
                            }
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllConstructors(hotseat, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final android.view.ViewGroup hs = (android.view.ViewGroup) param.thisObject;
                    try {
                        hs.getContext().registerReceiver(new android.content.BroadcastReceiver() {
                            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                                baseWidths.clear();
                                hs.requestLayout();
                            }
                        }, new android.content.IntentFilter("com.anton.lockcenter.UPDATE_SETTINGS"),
                                android.content.Context.RECEIVER_EXPORTED);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LC launcher ERR " + t);
        }
    }

    private static volatile boolean glassHooked = false;
    private static volatile boolean layoutListenerAdded = false;
    private static volatile boolean sizePosted = false;
    private static volatile int tHits = 0;
    private static volatile int iHits = 0;

    private static void hookGlassPredicate(android.view.View root) {
        if (glassHooked || root == null) return;
        try {
            android.view.View ov = findGlassView(root);
            if (ov == null) return;
            java.lang.reflect.Field f = ov.getClass().getDeclaredField("g");
            f.setAccessible(true);
            Object kcObj = f.get(ov);
            if (kcObj == null) return;
            Class<?> kcCls = kcObj.getClass();
            XposedBridge.hookAllMethods(kcCls, "t", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        android.view.View v = (android.view.View) param.args[0];
                        if (v.getVisibility() == android.view.View.VISIBLE
                                && v.getWidth() > 0 && v.getHeight() > 0) {
                            param.setResult(Boolean.TRUE);
                        }

                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(kcCls, "i", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        android.view.ViewGroup vg = (android.view.ViewGroup) param.args[0];
                        android.graphics.RectF rf = (android.graphics.RectF) param.args[1];
                        android.view.View qsb = null;
                        try {
                            qsb = (android.view.View) XposedHelpers.getObjectField(vg, "mQsb");
                        } catch (Throwable ignored) {}
                        if (qsb == null || qsb.getVisibility() != android.view.View.VISIBLE) return;
                        if (qsb.getHeight() <= 0) return;
                        float qTop = qsb.getTop() + qsb.getTranslationY();
                        float qBottom = qsb.getBottom() + qsb.getTranslationY();
                        float qLeft = qsb.getLeft() + qsb.getTranslationX();
                        float qRight = qsb.getRight() + qsb.getTranslationX();
                        rf.top = Math.min(rf.top, qTop);
                        rf.bottom = Math.max(rf.bottom, qBottom);
                        rf.left = Math.min(rf.left, qLeft);
                        rf.right = Math.max(rf.right, qRight);

                    } catch (Throwable ignored) {}
                }
            });
            glassHooked = true;
            XposedBridge.log("LC launcher: glass predicate hooked " + kcCls.getName());
        } catch (Throwable t) {
            XposedBridge.log("LC launcher: glass hook ERR " + t);
        }
    }

    private static android.view.View findGlassView(android.view.View v) {
        String cn = v.getClass().getName();
        if (cn.endsWith(".ic") || cn.equals("ic")) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findGlassView(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static volatile boolean hostedTried = false;

    private static android.view.View hostedSearchView = null;

    private static void setupHostedSearchWidget(final android.content.Context ctx,
                                                final android.view.ViewGroup parent,
                                                final android.view.View stock) {
        if (hostedTried) {
            if (hostedSearchView != null) placeHosted(parent, stock);
            return;
        }
        hostedTried = true;
        try {
            android.appwidget.AppWidgetManager awm =
                    android.appwidget.AppWidgetManager.getInstance(ctx);
            String[][] candidates = {
                    {"com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.SearchWidgetProvider"},
                    {"com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider"},
                    {"com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.staticplugins.searchwidget.PremiumSearchWidgetProvider"},
            };
            android.appwidget.AppWidgetHost host = new android.appwidget.AppWidgetHost(ctx, 4242);
            host.startListening();
            for (String[] c : candidates) {
                android.content.ComponentName provider = new android.content.ComponentName(c[0], c[1]);
                int id = host.allocateAppWidgetId();
                boolean ok = awm.bindAppWidgetIdIfAllowed(id, provider);
                XposedBridge.log("LC widget bind " + c[1] + " = " + ok);
                if (!ok) continue;
                android.appwidget.AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
                if (info == null) continue;
                android.view.View wv = host.createView(ctx, id, info);
                try {
                    android.os.Bundle opts = new android.os.Bundle();
                    opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 1295);
                    opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 1295);
                    opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 221);
                    opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 221);
                    awm.updateAppWidgetOptions(id, opts);
                    XposedBridge.log("LC widget options set");
                } catch (Throwable t) {
                    XposedBridge.log("LC widget options ERR " + t);
                }
                hostedSearchView = wv;
                android.view.ViewGroup.LayoutParams lp =
                        new android.view.ViewGroup.LayoutParams(1295, 221);
                parent.addView(wv, lp);
                placeHosted(parent, stock);
                stock.setAlpha(0.01f);
                stock.setOnTouchListener(new android.view.View.OnTouchListener() {
                    @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) { return true; }
                });
                XposedBridge.log("LC widget added " + c[1]);
                return;
            }
            XposedBridge.log("LC widget: none bound");
        } catch (Throwable t) {
            XposedBridge.log("LC widget ERR " + t);
        }
    }

    private static void placeHosted(android.view.ViewGroup parent, android.view.View stock) {
        final android.view.View wv = hostedSearchView;
        if (wv == null || stock == null) return;
        wv.post(new Runnable() {
            @Override public void run() {
                try {
                    float sc = readQsbScale(parent.getContext());
                    int baseW = 1233;
                    Integer bw = baseWidths.get(stock);
                    if (bw != null && bw > 0) baseW = bw;
                    int w = Math.min(Math.round(baseW * sc),
                            Math.round(parent.getResources().getDisplayMetrics().widthPixels * 0.96f));
                    int h = stock.getHeight() > 0 ? stock.getHeight() : 221;
                    int left = parent.getWidth() / 2 - w / 2;
                    int top = stock.getTop();
                    if (wv.getWidth() == w && wv.getHeight() == h
                            && wv.getLeft() == left && wv.getTop() == top) return;
                    wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
                    wv.layout(left, top, left + w, top + h);
                    XposedBridge.log("LC widget placed " + w + "x" + h + " actual=" + wv.getWidth() + "x" + wv.getHeight());
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void initAutoBrightnessButton(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.systemui.brightness.ui.viewmodel.BrightnessSliderView",
                    lp.classLoader);
            XposedBridge.log("LC auto: slider class=" + (cls != null));
            if (cls == null) return;
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final android.view.View slider = (android.view.View) param.thisObject;
                    slider.post(new Runnable() {
                        @Override public void run() {
                            try { addAutoBrightnessButton(slider); } catch (Throwable t) {
                                XposedBridge.log("LC auto ERR " + t);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("LC auto hook ERR " + t);
        }
    }

    private static void addAutoBrightnessButton(final android.view.View slider) {
        if (slider.getTag() != null) return;
        slider.setTag(Boolean.TRUE);
        final android.content.Context ctx = slider.getContext();
        final android.view.ViewParent vp = slider.getParent();
        if (!(vp instanceof android.view.ViewGroup)) {
            XposedBridge.log("LC auto: parent not a group");
            return;
        }
        final android.view.ViewGroup parent = (android.view.ViewGroup) vp;
        final android.widget.ImageButton btn = new android.widget.ImageButton(ctx);
        int drawableId = ctx.getResources().getIdentifier(
                "ic_qs_brightness_auto", "drawable", ctx.getPackageName());
        if (drawableId == 0) drawableId = android.R.drawable.ic_menu_compass;
        btn.setImageResource(drawableId);
        btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        btn.setPadding(12, 12, 12, 12);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        btn.setBackground(bg);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                dp(ctx, 48), dp(ctx, 48));
        lp.gravity = android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL;
        parent.addView(btn, lp);
        btn.setX(0); btn.setY(0);
        btn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                try {
                    int mode = android.provider.Settings.System.getInt(ctx.getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                    android.provider.Settings.System.putInt(ctx.getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            mode == 1 ? 0 : 1);
                } catch (Throwable t) {
                    XposedBridge.log("LC auto click ERR " + t);
                }
            }
        });
        slider.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(android.view.View v, int l, int t, int r, int b,
                                                 int ol, int ot, int orr, int ob) {
                try {
                    int[] loc = new int[2];
                    v.getLocationInWindow(loc);
                    int[] ploc = new int[2];
                    parent.getLocationInWindow(ploc);
                    int size = dp(ctx, 48);
                    btn.setX(loc[0] - ploc[0] + v.getWidth() - size - dp(ctx, 4));
                    btn.setY(loc[1] - ploc[1] + (v.getHeight() - size) / 2f);
                } catch (Throwable ignored) {}
            }
        });
        XposedBridge.log("LC auto: button added");
    }

    private static int dp(android.content.Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    private static volatile boolean hostedDone = false;

    private static void initHostedSearch(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> hs = XposedHelpers.findClassIfExists("com.android.launcher3.Hotseat", lp.classLoader);
        XposedBridge.log("LC hosted: hotseat=" + (hs != null));
        if (hs == null) return;
        try {
            Class<?> ahv = XposedHelpers.findClassIfExists(
                    "android.appwidget.AppWidgetHostView", lp.classLoader);
            if (ahv != null) {
                XposedBridge.hookAllMethods(ahv, "updateAppWidget", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject != hostedViewRef) return;
                            android.view.View stock = stockViewRef;
                            android.view.View hv = hostedViewRef;
                            if (stock == null || hv == null) return;
                            hv.setAlpha(1f);
                            hv.setVisibility(android.view.View.VISIBLE);
                            stock.setAlpha(0.01f);
                            stock.setOnTouchListener(new android.view.View.OnTouchListener() {
                                @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) { return true; }
                            });
                            if (!hostedReady) {
                                hostedReady = true;
                                XposedBridge.log("LC hosted: ready, stock hidden");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("LC hosted: update hook installed");
            }
        } catch (Throwable t) {
            XposedBridge.log("LC hosted hook ERR " + t);
        }
        XposedBridge.hookAllMethods(hs, "onLayout", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    android.view.ViewGroup hotseat = (android.view.ViewGroup) param.thisObject;
                    if (hostedDone) return;
                    android.view.View stock = findByName(hotseat, "OseWidgetView");
                    if (stock == null || stock.getWidth() <= 0) return;
                    hostedDone = true;
                    placeGoogleWidget(hotseat, stock);
                } catch (Throwable t) {
                    XposedBridge.log("LC hosted layout ERR " + t);
                }
            }
        });
    }

    private static android.view.View findByName(android.view.ViewGroup g, String simple) {
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            if (c.getClass().getSimpleName().contains(simple)) return c;
            if (c instanceof android.view.ViewGroup) {
                android.view.View r = findByName((android.view.ViewGroup) c, simple);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int hostedWidth(android.content.Context ctx) {
        int def = Math.round(1440 * 0.882f);
        try {
            String v = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), "lockcenter_qsb_width");
            if (v != null) def = Integer.parseInt(v.trim());
        } catch (Throwable ignored) {}
        int screen = ctx.getResources().getDisplayMetrics().widthPixels;
        return Math.min(def, Math.round(screen * 0.96f));
    }

    private static void markHostedReadyIfRendered() {
        try {
            android.view.View hv = hostedViewRef;
            android.view.View stock = stockViewRef;
            if (hv == null || stock == null || hostedReady) return;
            boolean rendered = (hv instanceof android.view.ViewGroup)
                    && ((android.view.ViewGroup) hv).getChildCount() > 0;
            if (!rendered) return;
            hv.setAlpha(1f);
            hv.setVisibility(android.view.View.VISIBLE);
            stock.setAlpha(0.01f);
            stock.setOnTouchListener(new android.view.View.OnTouchListener() {
                @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) { return true; }
            });
            hostedReady = true;
            XposedBridge.log("LC hosted: ready, stock hidden");
        } catch (Throwable t) {
            XposedBridge.log("LC hosted ready ERR " + t);
        }
    }

    private static void placeGoogleWidget(final android.view.ViewGroup parent, final android.view.View stock) {
        try {
            final android.content.Context ctx = parent.getContext();
            android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(ctx);
            android.content.ComponentName provider = new android.content.ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.googlequicksearchbox.SearchWidgetProvider");
            android.appwidget.AppWidgetHost host = new android.appwidget.AppWidgetHost(ctx, 4242);
            host.startListening();
            int stored = 0;
            try {
                String sv = android.provider.Settings.Global.getString(
                        ctx.getContentResolver(), "lockcenter_widget_id");
                if (sv != null) stored = Integer.parseInt(sv.trim());
            } catch (Throwable ignored) {}
            int id = 0;
            if (stored > 0 && awm.getAppWidgetInfo(stored) != null) {
                id = stored;
                XposedBridge.log("LC hosted: reuse id " + id);
            } else {
                id = host.allocateAppWidgetId();
                boolean ok = awm.bindAppWidgetIdIfAllowed(id, provider);
                XposedBridge.log("LC hosted bind=" + ok + " id=" + id);
                if (!ok) return;
                try {
                    android.provider.Settings.Global.putString(ctx.getContentResolver(),
                            "lockcenter_widget_id", String.valueOf(id));
                } catch (Throwable ignored) {}
            }
            android.appwidget.AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info == null) return;
            final android.view.View wv = host.createView(ctx, id, info);
            int w = hostedWidth(ctx);
            try {
                float d = ctx.getResources().getDisplayMetrics().density;
                int wDp = Math.round(w / d);
                int hDp = Math.round(221 / d);
                android.os.Bundle opts = new android.os.Bundle();
                opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp);
                opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp);
                opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp);
                opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp);
                awm.updateAppWidgetOptions(id, opts);
                if (wv instanceof android.appwidget.AppWidgetHostView) {
                    ((android.appwidget.AppWidgetHostView) wv).updateAppWidgetSize(null, wDp, hDp, wDp, hDp);
                }
                XposedBridge.log("LC hosted size dp=" + wDp + "x" + hDp);
            } catch (Throwable t) {
                XposedBridge.log("LC hosted size ERR " + t);
            }
            int h = stock.getHeight() > 0 ? stock.getHeight() : 221;
            android.view.ViewGroup.LayoutParams lpv = new android.view.ViewGroup.LayoutParams(w, h);
            parent.addView(wv, lpv);
            wv.post(new Runnable() {
                @Override public void run() {
                    try {
                        int w2 = hostedWidth(ctx);
                        int h2 = stock.getHeight() > 0 ? stock.getHeight() : 221;
                        int left = parent.getWidth() / 2 - w2 / 2;
                        int top = stock.getTop();
                        wv.layout(left, top, left + w2, top + h2);
                    } catch (Throwable ignored) {}
                }
            });
            hostedViewRef = wv;
            stockViewRef = stock;
            try {
                stock.setAlpha(0.01f);
                stock.setOnTouchListener(new android.view.View.OnTouchListener() {
                    @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) { return true; }
                });
            } catch (Throwable ignored) {}
            try { wv.setVisibility(android.view.View.VISIBLE); } catch (Throwable ignored) {}
            XposedBridge.log("LC hosted added " + w + "x" + h);
            for (int delay : new int[]{400, 1200, 2500, 5000}) {
                wv.postDelayed(new Runnable() {
                    @Override public void run() { markHostedReadyIfRendered(); }
                }, delay);
            }
        } catch (Throwable t) {
            XposedBridge.log("LC hosted ERR " + t);
        }
    }

    private static volatile android.view.View hostedViewRef = null;
    private static volatile android.view.View stockViewRef = null;
    private static volatile boolean hostedReady = false;

    private static final String BROKEN_ICON_PKG = "com.smoothsaver.xposed";

    private static void initBrokenIconFix(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.log("LC iconfix: init in " + lp.packageName);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String pkg = null;
                    for (Object a : param.args) {
                        if (a instanceof String) { pkg = (String) a; break; }
                        if (a instanceof android.content.pm.PackageItemInfo) {
                            pkg = ((android.content.pm.PackageItemInfo) a).packageName; break;
                        }
                    }
                    if (!BROKEN_ICON_PKG.equals(pkg)) return;
                    android.content.Context ctx = null;
                    try {
                        Class<?> at = Class.forName("android.app.ActivityThread");
                        Object app = at.getMethod("currentApplication").invoke(null);
                        if (app instanceof android.content.Context) ctx = (android.content.Context) app;
                    } catch (Throwable ignored) {}
                    if (ctx == null) return;
                    param.setResult(ctx.getDrawable(android.R.drawable.sym_def_app_icon));
                    XposedBridge.log("LC iconfix: replaced icon for " + pkg);
                } catch (Throwable t) {
                    XposedBridge.log("LC iconfix hook ERR " + t);
                }
            }
        };
        Class<?> apm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lp.classLoader);
        if (apm != null) {
            XposedBridge.hookAllMethods(apm, "getApplicationIcon", hook);
            XposedBridge.hookAllMethods(apm, "getUserBadgedIcon", hook);
        }
        Class<?> pii = XposedHelpers.findClassIfExists(
                "android.content.pm.PackageItemInfo", lp.classLoader);
        if (pii != null) XposedBridge.hookAllMethods(pii, "loadIcon", hook);
        try {
            Class<?> d = android.graphics.drawable.Drawable.class;
            XC_MethodHook clamp = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof Integer && ((Integer) r) < 0) param.setResult(0);
                }
            };
            XposedBridge.hookAllMethods(d, "getIntrinsicWidth", clamp);
            XposedBridge.hookAllMethods(d, "getIntrinsicHeight", clamp);
            XposedBridge.log("LC iconfix: drawable clamp installed");
        } catch (Throwable t) {
            XposedBridge.log("LC iconfix clamp ERR " + t);
        }
        try {
            XC_MethodHook resHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        android.content.res.Resources res = (android.content.res.Resources) param.thisObject;
                        int id = (Integer) param.args[0];
                        String pkg = res.getResourcePackageName(id);
                        if (!BROKEN_ICON_PKG.equals(pkg)) return;
                        android.content.Context ctx = null;
                        try {
                            Class<?> at = Class.forName("android.app.ActivityThread");
                            Object app = at.getMethod("currentApplication").invoke(null);
                            if (app instanceof android.content.Context) ctx = (android.content.Context) app;
                        } catch (Throwable ignored) {}
                        if (ctx != null) {
                            param.setResult(ctx.getDrawable(android.R.drawable.sym_def_app_icon));
                            XposedBridge.log("LC iconfix: res drawable replaced " + id);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            Class<?> r = android.content.res.Resources.class;
            XposedBridge.hookAllMethods(r, "getDrawable", resHook);
            XposedBridge.hookAllMethods(r, "getDrawableForDensity", resHook);
            XposedBridge.log("LC iconfix: resources hooked");
        } catch (Throwable t) {
            XposedBridge.log("LC iconfix res ERR " + t);
        }
        XposedBridge.log("LC iconfix: hooks installed");
    }

    private static void initCarrierWfcFix(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> c = XposedHelpers.findClassIfExists("com.android.phone.CarrierConfigLoader", lp.classLoader);
        if (c == null) c = XposedHelpers.findClassIfExists(
                "com.android.internal.telephony.CarrierConfigLoader", lp.classLoader);
        XposedBridge.log("LC wfc: loader=" + (c != null));
        if (c == null) return;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object r = param.getResult();
                if (!(r instanceof android.os.PersistableBundle)) return;
                android.os.PersistableBundle b = (android.os.PersistableBundle) r;
                try {
                    b.putBoolean("carrier_wfc_ims_available_bool", true);
                    b.putBoolean("carrier_default_wfc_ims_enabled_bool", true);
                    b.putBoolean("carrier_wfc_supports_wifi_only_bool", true);
                    b.putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", true);
                    b.putBoolean("editable_wfc_mode_bool", true);
                    b.putBoolean("editable_wfc_roaming_mode_bool", true);
                    b.putInt("wfc_spn_format_idx_int", 4);
                } catch (Throwable ignored) {}
            }
        };
        XposedBridge.hookAllMethods(c, "getConfigForSubId", hook);
        try {
            Class<?> ccm = android.telephony.CarrierConfigManager.class;
            XposedBridge.hookAllMethods(ccm, "getConfigForSubId", hook);
            XposedBridge.hookAllMethods(ccm, "getConfig", hook);
            XposedBridge.log("LC wfc: ccm hooked");
        } catch (Throwable t) {
            XposedBridge.log("LC wfc ccm ERR " + t);
        }
        XposedBridge.log("LC wfc: hooks installed");
    }

    private static void initQsRowsZero(XC_LoadPackage.LoadPackageParam lp) {
        try {
            android.content.Context ctx = null;
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app instanceof android.content.Context) ctx = (android.content.Context) app;
            } catch (Throwable ignored) {}
            if (ctx != null) {
                final int gid = ctx.getResources().getIdentifier(
                        "quick_qs_paginated_grid_num_rows", "integer", ctx.getPackageName());
                XposedBridge.log("LC qsrows: global id=" + gid);
                if (gid != 0) {
                    XposedBridge.hookMethod(
                            android.content.res.Resources.class.getDeclaredMethod("getInteger", int.class),
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    if (((Integer) p.args[0]) == gid) p.setResult(0);
                                }
                            });
                    XposedBridge.log("LC qsrows: global override installed");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("LC qsrows global ERR " + t);
        }
        Class<?> repo = XposedHelpers.findClassIfExists(
                "com.android.systemui.qs.panels.data.repository.QuickQuickSettingsRowRepository",
                lp.classLoader);
        XposedBridge.log("LC qsrows: repo=" + (repo != null));
        if (repo == null) return;
        XposedBridge.hookAllConstructors(repo, new XC_MethodHook() {
            private XC_MethodHook.Unhook resHook;
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    android.content.Context ctx = null;
                    try {
                        Class<?> at = Class.forName("android.app.ActivityThread");
                        Object app = at.getMethod("currentApplication").invoke(null);
                        if (app instanceof android.content.Context) ctx = (android.content.Context) app;
                    } catch (Throwable ignored) {}
                    if (ctx == null) return;
                    final int id = ctx.getResources().getIdentifier(
                            "quick_qs_paginated_grid_num_rows", "integer", ctx.getPackageName());
                    if (id == 0) { XposedBridge.log("LC qsrows: id not found"); return; }
                    resHook = XposedBridge.hookMethod(
                            android.content.res.Resources.class.getDeclaredMethod("getInteger", int.class),
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    if (((Integer) p.args[0]) == id) p.setResult(0);
                                }
                            });
                    XposedBridge.log("LC qsrows: forcing 0 rows (id=" + id + ")");
                } catch (Throwable t) {
                    XposedBridge.log("LC qsrows hook ERR " + t);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try { if (resHook != null) resHook.unhook(); } catch (Throwable ignored) {}
                resHook = null;
            }
        });
    }

    private static void dumpTree(android.view.View v, StringBuilder sb, int depth) {
        if (depth > 3) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        String id = "";
        try { id = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
        sb.append(v.getClass().getName()).append(" id=").append(id)
          .append(" w=").append(v.getWidth()).append(" h=").append(v.getHeight()).append('\n');
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpTree(g.getChildAt(i), sb, depth + 1);
        }
    }

    private static void collectBg(android.view.View v, StringBuilder sb, int depth) {
        if (depth > 20) return;
        try {
            String cn0 = v.getClass().getName();
            boolean isGlass = cn0.endsWith(".ic") || cn0.equals("ic");
            int[] gl = new int[2];
            v.getLocationOnScreen(gl);
            if (isGlass) {
                sb.append("GLASS ").append(cn0)
                  .append(" l=").append(v.getLeft()).append(" t=").append(v.getTop())
                  .append(" r=").append(v.getRight()).append(" b=").append(v.getBottom())
                  .append(" w=").append(v.getWidth()).append(" h=").append(v.getHeight())
                  .append(" x=").append(gl[0]).append(" y=").append(gl[1])
                  .append(" vis=").append(v.getVisibility())
                  .append(" shown=").append(v.isShown()).append('\n');
            }
            int screenH = v.getResources().getDisplayMetrics().heightPixels;
            if (gl[1] > screenH * 0.6 && v.getVisibility() == android.view.View.VISIBLE) {
                String id = "";
                try { id = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
                sb.append(v.getClass().getSimpleName()).append(" id=").append(id)
                  .append(" w=").append(v.getWidth()).append(" h=").append(v.getHeight())
                  .append(" x=").append(gl[0]).append(" y=").append(gl[1])
                  .append(" bg=").append(v.getBackground() != null);
                android.graphics.drawable.Drawable d = v.getBackground();
                if (d != null) {
                    sb.append(" bgcls=").append(d.getClass().getSimpleName());
                    sb.append(" bounds=").append(d.getBounds().toShortString());
                }
                sb.append('\n');
            }
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectBg(g.getChildAt(i), sb, depth + 1);
        }
    }

    private static android.view.View findOse(android.view.ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            String n = c.getClass().getSimpleName();
            if (n.contains("OseWidgetView") || n.contains("AppWidgetHostView")) return c;
            if (c instanceof android.view.ViewGroup) {
                android.view.View r = findOse((android.view.ViewGroup) c);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static float readQsbScale(android.content.Context ctx) {
        float scale = 1.0f;
        try {
            String cfg = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), "lockcenter_cfg");
            if (cfg != null) {
                for (String part : cfg.split(",")) {
                    if (part.startsWith("qsb_scale=")) {
                        scale = Float.parseFloat(part.substring("qsb_scale=".length()).trim());
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (scale < 0.5f || scale > 1.6f) scale = 1.0f;
        return scale;
    }

    private static void applyDockSearchWidth(android.app.Activity a) {
        try {
            float cur = readQsbScale(a);
            android.view.View cached = cachedOse != null ? cachedOse.get() : null;
            if (cached != null && cached.isAttachedToWindow()
                    && Math.abs(appliedScale - cur) < 0.001f && cur > 0) {
                return;
            }
            android.view.View decor = a.getWindow().getDecorView();
            java.util.List<android.view.View> found = new java.util.ArrayList<>();
            collectSearchViews((android.view.ViewGroup) decor, found);
            float scale = readQsbScale(a);
            int screenH = decor.getResources().getDisplayMetrics().heightPixels;
            android.view.View plate = null;
            for (android.view.View v : found) {
                String idName = "";
                try { idName = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
                if (!"googleapp_search_plate".equals(idName)) continue;
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                if (loc[1] > screenH * 0.6) { plate = v; break; }
            }
            if (plate == null) {
                XposedBridge.log("LC launcher: plate not found, scale=" + scale);
                return;
            }
            android.view.View target = plate;
            StringBuilder chain = new StringBuilder();
            android.view.ViewParent p = plate.getParent();
            int depth = 0;
            while (p instanceof android.view.View && depth < 8) {
                android.view.View pv = (android.view.View) p;
                chain.append(pv.getClass().getSimpleName()).append('>');
                String pcn = pv.getClass().getSimpleName();
                if (pcn.contains("OseWidgetView")
                        || pv.getClass().getName().contains("AppWidgetHostView")
                        || pcn.contains("WidgetHost")) {
                    target = pv;
                    break;
                }
                if (target == plate) target = pv; // keep climbing to outermost dock child
                p = pv.getParent();
                depth++;
            }
            XposedBridge.log("LC launcher: chain=" + chain + " target=" + target.getClass().getSimpleName() + " scale=" + scale);
            Object tag = target.getTag();
            int original;
            if (tag instanceof Integer) {
                original = (Integer) tag;
            } else {
                original = target.getWidth();
                if (original <= 0) return;
                target.setTag(original);
            }
            int wanted = Math.round(original * scale);
            int screenW = decor.getResources().getDisplayMetrics().widthPixels;
            int maxW = Math.round(screenW * 0.98f);
            if (wanted > maxW) wanted = maxW;
            android.view.ViewGroup.LayoutParams lp = target.getLayoutParams();
            if (lp == null) return;
            if (target.getClass().getSimpleName().contains("OseWidgetView")) {
                target.setPivotX(target.getWidth() / 2f);
                target.setPivotY(target.getHeight() / 2f);
                target.setScaleX(scale);
                cachedOse = new java.lang.ref.WeakReference<>(target);
                appliedScale = scale;
                XposedBridge.log("LC launcher: ose scaleX=" + scale);
                return;
            }
            if (Math.abs(lp.width - wanted) > 2) {
                lp.width = wanted;
                target.setLayoutParams(lp);
                XposedBridge.log("LC launcher: width " + wanted + " (orig " + original + ") on " + target.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            XposedBridge.log("LC launcher apply ERR " + t);
        }
    }

    private static void collectSearchViews(android.view.ViewGroup g, java.util.List<android.view.View> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            String cls = c.getClass().getSimpleName();
            String idName = "";
            try {
                idName = c.getResources().getResourceEntryName(c.getId());
            } catch (Throwable ignored) {}
            String low = (cls + " " + idName).toLowerCase();
            if (low.contains("search") || low.contains("qsb")) out.add(c);
            if (c instanceof android.view.ViewGroup) collectSearchViews((android.view.ViewGroup) c, out);
        }
    }

    private static void applyQsbWidth(ViewGroup hotseat) {
        int width = hotseat.getWidth();
        if (width <= 0) return;
        float scale = 1.25f;
        try {
            scale = android.provider.Settings.Global.getFloat(
                    hotseat.getContext().getContentResolver(), "lc_qsb_scale", 1.25f);
        } catch (Throwable ignored) {}
        int target = Math.round(width * scale);
        View qsb = findQsb(hotseat);
        if (qsb == null) return;
        ViewGroup.LayoutParams lp = qsb.getLayoutParams();
        if (lp == null) return;
        if (lp.width != target) {
            lp.width = target;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
                ((ViewGroup.MarginLayoutParams) lp).setMarginEnd(0);
            }
            qsb.setLayoutParams(lp);
            XposedBridge.log("LC launcher: qsb width " + lp.width + " (hotseat " + width + ")");
        }
    }

    private static View findQsb(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.contains("Qsb") || n.contains("SearchDelegateView") || n.contains("SearchContainer")) return c;
            if (c instanceof ViewGroup) {
                View r = findQsb((ViewGroup) c);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ======== Partial-shade expanded header (strictly gated, no scrim/panel translation) ========
    private static volatile boolean lcPartialActive = false;
    private static final java.util.List<View> lcHeaderMotions =
            java.util.Collections.synchronizedList(new java.util.ArrayList<View>());
    private static final java.util.List<View> lcNotifStacks =
            java.util.Collections.synchronizedList(new java.util.ArrayList<View>());

    private static void lcSetClip(View v, boolean clip, int levels) {
        View cur = v;
        for (int i = 0; i < levels && cur != null; i++) {
            ViewParent vp = cur.getParent();
            if (!(vp instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) vp;
            try {
                g.setClipChildren(clip);
                g.setClipToPadding(clip);
            } catch (Throwable ignored) {}
            cur = g;
        }
    }

    private static volatile Object lcCtrl = null;
    private static boolean lcStackHooked;
    private static long lcLastDateLog = 0;

    private static void lcSetPartial(boolean active) {
        if (lcPartialActive == active) return;
        lcPartialActive = active;
        synchronized (lcHeaderMotions) {
            for (View h : lcHeaderMotions) {
                try {
                    java.lang.reflect.Method m = h.getClass().getMethod("setProgress", float.class);
                    m.invoke(h, active ? 1f : 0f);
                } catch (Throwable ignored) {}
                lcSetClip(h, !active, 2);
            }
        }
        float shiftPx = 0f;
        synchronized (lcNotifStacks) {
            if (active && !lcNotifStacks.isEmpty()) {
                shiftPx = 68f * lcNotifStacks.get(0).getResources().getDisplayMetrics().density;
            }
            for (View s : lcNotifStacks) {
                try { s.setTranslationY(shiftPx); } catch (Throwable ignored) {}
            }
        }
        XposedBridge.log("LC shade: partial=" + active);
    }

    private static void lcEvaluate(View header) {
        boolean on = false;
        try {
            Object c = lcCtrl;
            if (c != null && header.isShown()) {
                float qs = XposedHelpers.getFloatField(c, "qsExpandedFraction");
                float sh = XposedHelpers.getFloatField(c, "shadeExpandedFraction");
                on = qs < 0.02f && sh > 0.9f;
            }
        } catch (Throwable ignored) {}
        lcSetPartial(on);
        if (on && header instanceof ViewGroup) {
            try {
                int id = header.getResources().getIdentifier("date", "id", "com.android.systemui");
                View d = id != 0 ? header.findViewById(id) : null;
                if (d != null) {
                    if (d.getVisibility() != View.VISIBLE) d.setVisibility(View.VISIBLE);
                    if (d.getAlpha() < 1f) d.setAlpha(1f);
                    if (d instanceof TextView && ((TextView) d).getText().length() == 0) {
                        ((TextView) d).setText(new java.text.SimpleDateFormat("EEE, d MMM",
                                java.util.Locale.getDefault()).format(new java.util.Date()));
                    }
                    if (d.getWidth() == 0 && header.getWidth() > 0) {
                        int mw = View.MeasureSpec.makeMeasureSpec(header.getWidth() / 2, View.MeasureSpec.AT_MOST);
                        int mh = View.MeasureSpec.makeMeasureSpec(d.getHeight() > 0 ? d.getHeight() : 84, View.MeasureSpec.EXACTLY);
                        d.measure(mw, mh);
                        int l = d.getLeft(), tp = d.getTop();
                        d.layout(l, tp, l + d.getMeasuredWidth(), tp + d.getMeasuredHeight());
                    }
                    long t = System.currentTimeMillis();
                    if (t - lcLastDateLog > 1500) {
                        lcLastDateLog = t;
                        XposedBridge.log("LC shade: date " + d.getClass().getName() + " vis=" + d.getVisibility()
                                + " a=" + d.getAlpha() + " w=" + d.getWidth() + " h=" + d.getHeight()
                                + " x=" + d.getX() + " y=" + d.getY() + " hdrH=" + header.getHeight()
                                + " text=" + (d instanceof TextView ? ((TextView) d).getText() : "-"));
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void initPartialShadeHeader(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> motion = XposedHelpers.findClassIfExists(
                    "androidx.constraintlayout.motion.widget.MotionLayout", lp.classLoader);
            if (motion != null) {
                XposedBridge.hookAllConstructors(motion, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        final View v = (View) p.thisObject;
                        if (!v.getClass().getName().contains("NoRemeasureMotionLayout")) return;
                        synchronized (lcHeaderMotions) {
                            if (lcHeaderMotions.contains(v)) return;
                            lcHeaderMotions.add(v);
                        }
                        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override public boolean onPreDraw() { lcEvaluate(v); return true; }
                        });
                        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                            @Override public void onViewAttachedToWindow(View x) {}
                            @Override public void onViewDetachedFromWindow(View x) { lcSetPartial(false); }
                        });
                        XposedBridge.log("LC shade: header motion captured");
                    }
                });
                XposedBridge.hookAllMethods(motion, "setProgress", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (lcPartialActive && p.args.length > 0 && p.args[0] instanceof Float
                                && p.thisObject.getClass().getName().contains("NoRemeasureMotionLayout")) {
                            p.args[0] = 1f;
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("LC shade header ERR " + t);
        }

        try {
            String[] stackNames = {
                    "com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer",
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
            };
            for (String name : stackNames) {
                Class<?> c = XposedHelpers.findClassIfExists(name, lp.classLoader);
                if (c == null) continue;
                lcStackHooked = true; // shared container wraps the scroller: shift only one of them
                XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        View v = (View) p.thisObject;
                        synchronized (lcNotifStacks) {
                            if (!lcNotifStacks.contains(v)) lcNotifStacks.add(v);
                        }
                    }
                });
                break;
            }
        } catch (Throwable t) {
            XposedBridge.log("LC shade stack ERR " + t);
        }

        try {
            Class<?> hc = XposedHelpers.findClassIfExists(
                    "com.android.systemui.shade.ShadeHeaderController", lp.classLoader);
            if (hc != null) {
                XC_MethodHook h = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { lcCtrl = p.thisObject; }
                };
                XposedBridge.hookAllMethods(hc, "updateTransition$1", h);
                XposedBridge.hookAllMethods(hc, "updatePosition", h);
                XposedBridge.hookAllMethods(hc, "onViewAttached", h);
                XposedBridge.log("LC shade: header controller hooked");
            }
        } catch (Throwable t) {
            XposedBridge.log("LC shade expansion ERR " + t);
        }
    }

    private static void initSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        loadConfig(null);

        try {
            // Experimental: opt-in only (touch /data/adb/lockcenter_shade_on), default = stock shade.
            {
                initPartialShadeHeader(lpparam);
            }
        } catch (Throwable t) {
            XposedBridge.log("LC shade init ERR " + t);
        }

        try {
            initQsRowsZero(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("LC qsrows init ERR " + t);
        }

        try {
            hookMobileType(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookSplitTiles(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookInternetDialogManager(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookDataDialog(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookTileBehavior(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookKeyguard(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookDateTranslation(cl);
            hookDateTranslationY(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookDateLayout(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            // Центрирование таблетки уведомлений отключено (по просьбе пользователя).
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void registerReceiverIfNeeded(Context context) {
        if (receiverRegistered || context == null) return;
        try {
            IntentFilter filter = new IntentFilter("com.anton.lockcenter.UPDATE_SETTINGS");
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent != null) {
                        loadConfig(ctx);
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private static boolean isDateOnly(View v) {
        return v != null && v.getClass().getName().contains("DateSmartspaceView");
    }

    private static boolean isSmartspaceCard(View v) {
        return v != null && v.getClass().getName().contains("BcSmartspaceView");
    }

    private static View findSmallClock(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c.getClass().getName().endsWith(CLOCK_SUFFIX)) {
                return c;
            }
        }
        return null;
    }

    private static float dateContentDelta(View date) {
        if (!(date instanceof ViewGroup) || date.getWidth() <= 0) return 0f;
        float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE};
        collectTextBounds((ViewGroup) date, 0f, b);
        if (b[1] < b[0]) return 0f;
        return date.getWidth() / 2f - (b[0] + b[1]) / 2f;
    }

    private static void collectTextBounds(ViewGroup g, float offX, float[] b) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) continue;
            float l = offX + c.getLeft();
            if (c instanceof TextView) {
                b[0] = Math.min(b[0], l);
                b[1] = Math.max(b[1], l + c.getWidth());
            } else if (c instanceof ViewGroup) {
                collectTextBounds((ViewGroup) c, l, b);
            }
        }
    }

    private static void hookDateLayout(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedHelpers.findAndHookMethod(viewClass, "layout",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (!isDateOnly(v)) return;

                        View parent = (View) v.getParent();
                        if (parent == null || !parent.getClass().getName().endsWith(ROOT_SUFFIX)) return;

                        int l = (Integer) param.args[0];
                        int r = (Integer) param.args[2];
                        int width = r - l;
                        int screenWidth = v.getResources().getDisplayMetrics().widthPixels;

                        if (width > 0 && width < screenWidth - 100) {
                            float rootShift = parent.getLeft() + parent.getTranslationX();
                            int targetCenter = (int) ((screenWidth / 2f) - rootShift);
                            int targetLeft = targetCenter - (width / 2) + Math.round(dateContentDelta(v));

                            param.args[0] = targetLeft;
                            param.args[2] = targetLeft + width;
                        }
                    }
                });
    }

    private static void hookDateTranslation(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedBridge.hookAllMethods(viewClass, "setTranslationX", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (!isDateOnly(v)) return;

                View parent = (View) v.getParent();
                if (parent == null || !parent.getClass().getName().endsWith(ROOT_SUFFIX)) return;

                int screenWidth = v.getResources().getDisplayMetrics().widthPixels;
                int w = v.getWidth();
                if (w <= 0 || w >= screenWidth - 100) return;

                float rootShift = parent.getLeft() + parent.getTranslationX();
                float targetCenterInRoot = (screenWidth / 2f) - rootShift;
                int currentLeft = v.getLeft();
                float targetTx = (targetCenterInRoot - w / 2f + dateContentDelta(v)) - currentLeft;

                param.args[0] = targetTx;
            }
        });
    }

    private static void hookDateTranslationY(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedBridge.hookAllMethods(viewClass, "setTranslationY", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (!isDateOnly(v)) return;

                View parent = (View) v.getParent();
                if (parent == null || !parent.getClass().getName().endsWith(ROOT_SUFFIX)) return;

                ViewGroup root = (ViewGroup) parent;
                View smallClock = findSmallClock(root);

                if (smallClock != null && smallClock.getVisibility() == View.VISIBLE && smallClock.getAlpha() > 0.05f) {
                    float clockTy = smallClock.getTranslationY();
                    param.args[0] = clockTy + (float) dateExtraGapPx;
                }
            }
        });
    }

    private static void hookKeyguard(ClassLoader cl) {
        Class<?> constraintLayout = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintLayout", cl);
        Class<?> rootClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);

        if (constraintLayout != null) {
            XC_MethodHook before = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && param.thisObject.getClass().getName().endsWith(ROOT_SUFFIX)) {
                        fixKeyguard((ViewGroup) param.thisObject, true);
                    }
                }
            };
            XposedBridge.hookAllMethods(constraintLayout, "onMeasure", before);
            if (rootClass != null) {
                XposedBridge.hookAllMethods(rootClass, "onMeasure", before);
            }
        }

        if (rootClass != null) {
            XposedBridge.hookAllConstructors(rootClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final ViewGroup root = (ViewGroup) param.thisObject;
                    loadConfig(root.getContext());
                    registerReceiverIfNeeded(root.getContext());
                    if (!HOOKED.add(root)) return;
                    root.getViewTreeObserver().addOnPreDrawListener(
                            new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    try {
                                        long now = System.currentTimeMillis();
                                        if (now - lastCheckTime > 2000) {
                                            lastCheckTime = now;
                                            loadConfig(root.getContext());
                                        }

                                        fixKeyguard(root, false);
                                        forceCenterDateAndClock(root);
                                    } catch (Throwable ignored) {}
                                    return true;
                                }
                            });
                }
            });
        }
    }

    private static void fixKeyguard(ViewGroup root, boolean inMeasure) {
        View clock = null;
        View dateView = null;
        View cardView = null;
        View largeClock = null;

        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = c;
            else if (n.endsWith(LARGE_CLOCK_SUFFIX)) largeClock = c;
            else if (isDateOnly(c)) dateView = c;
            else if (isSmartspaceCard(c)) cardView = c;
        }

        if (largeClock != null) {
            attachLargeClockScaler(largeClock);
        }

        boolean isSmallClockActive = (clock != null && clock.getVisibility() == View.VISIBLE && clock.getAlpha() > 0.05f);

        if (clock != null) {
            if (clock.getId() == View.NO_ID) {
                clock.setId(View.generateViewId());
            }
            enforceCentered(clock, root, inMeasure);
            attachClockScaler(clock);
        }

        if (dateView != null) {
            attachDateScaler(dateView);

            if (dateView.getId() == View.NO_ID) {
                dateView.setId(View.generateViewId());
            }

            if (isSmallClockActive && clock != null) {
                ViewGroup.LayoutParams lp = dateView.getLayoutParams();
                if (lp != null) {
                    int cid = clock.getId();

                    setInt(lp, "baselineToBaseline", UNSET);
                    setInt(lp, "baselineToTop", UNSET);
                    setInt(lp, "baselineToBottom", UNSET);

                    setInt(lp, "startToEnd", UNSET);
                    setInt(lp, "leftToRight", UNSET);
                    setInt(lp, "topToTop", UNSET);
                    setInt(lp, "bottomToTop", UNSET);
                    setInt(lp, "bottomToBottom", UNSET);

                    setInt(lp, "topToBottom", cid);

                    setInt(lp, "startToStart", PARENT);
                    setInt(lp, "endToEnd", PARENT);
                    setFloat(lp, "horizontalBias", 0.5f);

                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                        m.setMarginStart(0);
                        m.setMarginEnd(0);
                        m.leftMargin = 0;
                        m.rightMargin = 0;
                        m.topMargin = 20;
                    }

                    apply(dateView, lp, root, inMeasure);
                }
            }
        }

        if (cardView != null && dateView != null) {
            if (cardView.getId() == View.NO_ID) {
                cardView.setId(View.generateViewId());
            }

            ViewGroup.LayoutParams lp = cardView.getLayoutParams();
            if (lp != null && isSmallClockActive) {
                int did = dateView.getId();

                setInt(lp, "baselineToBaseline", UNSET);
                setInt(lp, "startToEnd", UNSET);
                setInt(lp, "leftToRight", UNSET);
                setInt(lp, "topToTop", UNSET);
                setInt(lp, "topToBottom", did);

                setInt(lp, "startToStart", PARENT);
                setInt(lp, "endToEnd", PARENT);
                setFloat(lp, "horizontalBias", 0.5f);

                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    m.topMargin = 12 + cardTopMarginPx;
                }

                apply(cardView, lp, root, inMeasure);
            }
        }

        if (isSmallClockActive) {
            int anchorId = View.NO_ID;
            if (cardView != null && cardView.getVisibility() == View.VISIBLE && cardView.getHeight() > 0) {
                anchorId = cardView.getId();
            } else if (dateView != null) {
                anchorId = dateView.getId();
            }

            if (anchorId != View.NO_ID) {
                pointPlaceholderAt(root, anchorId, inMeasure);
            }
        }
    }

    private static void forceCenterDateAndClock(ViewGroup root) {
        int displayWidth = root.getResources().getDisplayMetrics().widthPixels;
        if (displayWidth <= 0) return;

        float rootShift = root.getLeft() + root.getTranslationX();
        float targetCenterInRoot = (displayWidth / 2f) - rootShift;

        View clock = null;
        View dateView = null;
        View cardView = null;
        View largeClock = null;

        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;

            String n = v.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = v;
            else if (n.endsWith(LARGE_CLOCK_SUFFIX)) largeClock = v;
            else if (isDateOnly(v)) dateView = v;
            else if (isSmartspaceCard(v)) cardView = v;

            if (isDateOnly(v) || n.endsWith(CLOCK_SUFFIX)) {
                int w = v.getWidth();
                if (w > 0 && w < displayWidth - 100) {
                    int left = v.getLeft();
                    float targetTx = (targetCenterInRoot - w / 2f
                            + (isDateOnly(v) ? dateContentDelta(v) : 0f)) - left;
                    if (Math.abs(v.getTranslationX() - targetTx) > 0.5f) {
                        v.setTranslationX(targetTx);
                    }
                }
            }
        }

        boolean isSmallClockActive = (clock != null && clock.getVisibility() == View.VISIBLE && clock.getAlpha() > 0.05f);

        if (isSmallClockActive) {
            float clockTy = clock.getTranslationY();

            if (dateView != null && dateView.getVisibility() == View.VISIBLE) {
                float desiredDateTy = clockTy + (float) dateExtraGapPx;
                if (Math.abs(dateView.getTranslationY() - desiredDateTy) > 0.5f) {
                    dateView.setTranslationY(desiredDateTy);
                }
            }

            if (cardView != null && cardView.getVisibility() == View.VISIBLE) {
                float desiredCardTy = clockTy + (float) (dateExtraGapPx + cardTopMarginPx);
                if (Math.abs(cardView.getTranslationY() - desiredCardTy) > 0.5f) {
                    cardView.setTranslationY(desiredCardTy);
                }
            }

            if (placeholderId != 0) {
                View ph = root.findViewById(placeholderId);
                if (ph != null) {
                    float baseOffset = clockTy + dateExtraGapPx + (cardView != null && cardView.getVisibility() == View.VISIBLE ? cardTopMarginPx : 0);
                    float desiredPhTy = baseOffset + notifTopMarginPx;
                    if (Math.abs(ph.getTranslationY() - desiredPhTy) > 0.5f) {
                        ph.setTranslationY(desiredPhTy);
                    }
                }
            }
        } else {
            if (dateView != null && dateView.getVisibility() == View.VISIBLE) {
                float desiredDateTy = 0f;
                if (largeClock != null && largeClock.getVisibility() == View.VISIBLE && largeClock.getHeight() > 0) {
                    float largeExpandDown = (largeClock.getHeight() * (largeClockScaleY - 1f)) / 2f;
                    desiredDateTy = largeClock.getTranslationY() + Math.max(0f, largeExpandDown) + 20f;
                }
                if (Math.abs(dateView.getTranslationY() - desiredDateTy) > 0.5f) {
                    dateView.setTranslationY(desiredDateTy);
                }
            }
        }
    }

    private static void attachLargeClockScaler(final View large) {
        if (!HOOKED.add(large)) return;
        large.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (large.getVisibility() != View.VISIBLE || large.getWidth() == 0) return true;
                            large.setPivotX(large.getWidth() / 2f);
                            large.setPivotY(large.getHeight() / 2f);
                            float cx = large.getScaleX();
                            if (cx != lastX || Math.abs(lastX - largeClockScaleX) > 0.01f) {
                                lastX = largeClockScaleX;
                                large.setScaleX(lastX);
                            }
                            float cy = large.getScaleY();
                            if (cy != lastY || Math.abs(lastY - largeClockScaleY) > 0.01f) {
                                lastY = largeClockScaleY;
                                large.setScaleY(lastY);
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                });
    }

    private static void attachDateScaler(final View date) {
        if (!HOOKED.add(date)) return;
        date.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (date.getVisibility() != View.VISIBLE || date.getWidth() == 0) return true;
                            date.setPivotX(date.getWidth() / 2f - dateContentDelta(date));
                            date.setPivotY(date.getHeight() / 2f);
                            float cx = date.getScaleX();
                            if (cx != lastX || Math.abs(lastX - dateScaleX) > 0.01f) {
                                lastX = dateScaleX;
                                date.setScaleX(lastX);
                            }
                            float cy = date.getScaleY();
                            if (cy != lastY || Math.abs(lastY - dateScaleY) > 0.01f) {
                                lastY = dateScaleY;
                                date.setScaleY(lastY);
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                });
    }

    private static void attachClockScaler(final View clock) {
        if (!HOOKED.add(clock)) return;
        clock.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (clock.getVisibility() != View.VISIBLE || clock.getWidth() == 0) return true;
                            clock.setPivotX(clock.getWidth() / 2f);
                            clock.setPivotY(clock.getHeight() / 2f);
                            float cx = clock.getScaleX();
                            if (cx != lastX || Math.abs(lastX - clockScaleX) > 0.01f) {
                                lastX = clockScaleX;
                                clock.setScaleX(lastX);
                            }
                            float cy = clock.getScaleY();
                            if (cy != lastY || Math.abs(lastY - clockScaleY) > 0.01f) {
                                lastY = clockScaleY;
                                clock.setScaleY(lastY);
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                });
    }

    private static void pointPlaceholderAt(ViewGroup root, int anchorId, boolean inMeasure) {
        if (anchorId == View.NO_ID) return;
        if (placeholderId == 0) {
            placeholderId = root.getResources().getIdentifier("nssl_placeholder", "id", "com.android.systemui");
        }
        if (placeholderId == 0) return;
        View ph = root.findViewById(placeholderId);
        if (ph == null || ph.getLayoutParams() == null) return;
        ViewGroup.LayoutParams lp = ph.getLayoutParams();
        setInt(lp, "topToBottom", anchorId);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.topMargin = notifTopMarginPx;
        }
        apply(ph, lp, root, inMeasure);
    }

    private static void enforceCentered(View v, ViewGroup parent, boolean inMeasure) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || !lp.getClass().getName().contains("ConstraintLayout")) return;
        String[] clear = {"leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
                "startToEnd", "endToStart"};
        for (String f : clear) setInt(lp, f, UNSET);
        setInt(lp, "startToStart", PARENT);
        setInt(lp, "endToEnd", PARENT);
        setFloat(lp, "horizontalBias", 0.5f);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.setMarginStart(0);
            m.setMarginEnd(0);
            m.leftMargin = 0;
            m.rightMargin = 0;
        }
        apply(v, lp, parent, inMeasure);
    }

    private static void apply(View v, ViewGroup.LayoutParams lp, ViewGroup parent, boolean inMeasure) {
        if (inMeasure) {
            try {
                lp.getClass().getMethod("resolveLayoutDirection", int.class)
                        .invoke(lp, parent.getLayoutDirection());
            } catch (Throwable ignored) {}
            v.forceLayout();
        } else {
            v.setLayoutParams(lp);
        }
    }

    private static boolean setInt(Object lp, String name, int value) {
        try {
            Field f = lp.getClass().getField(name);
            if (f.getInt(lp) == value) return false;
            f.setInt(lp, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setFloat(Object lp, String name, float value) {
        try {
            Field f = lp.getClass().getField(name);
            if (f.getFloat(lp) == value) return false;
            f.setFloat(lp, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------ shelf pill + AOD icons

    private static void hookPreDraw(ClassLoader cl, String className, final boolean isShelf) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) return;
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final View v = (View) param.thisObject;
                if (!HOOKED.add(v)) return;
                v.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                try {
                                    int screenWidth = v.getResources().getDisplayMetrics().widthPixels;
                                    if (isShelf) {
                                        centerShelf((ViewGroup) v, screenWidth);
                                    } else if (isAodContainer(v)) {
                                        centerAodIcons((ViewGroup) v, screenWidth);
                                    }
                                } catch (Throwable ignored) {}
                                return true;
                            }
                        });
            }
        });
    }

    private static boolean isAodContainer(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        try {
            return "aod_notification_icon_container".equals(
                    v.getResources().getResourceEntryName(id));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void centerAodIcons(ViewGroup c, int screenWidth) {
        if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) return;

        int displayWidth = c.getResources().getDisplayMetrics().widthPixels;
        int[] cLoc = new int[2];
        c.getLocationOnScreen(cLoc);

        float minL = Float.MAX_VALUE;
        float maxR = -Float.MAX_VALUE;
        int n = 0;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE || ch.getWidth() == 0
                    || ch.getAlpha() < 0.05f) continue;
            float l = ch.getLeft() + ch.getTranslationX();
            minL = Math.min(minL, l);
            maxR = Math.max(maxR, l + ch.getWidth());
            n++;
        }
        if (n == 0) return;

        float groupCenterOnScreen = cLoc[0] + minL + (maxR - minL) / 2f;
        float targetCenter = displayWidth / 2f;
        float shift = targetCenter - groupCenterOnScreen;

        if (Math.abs(shift) > 0.5f) {
            for (int i = 0; i < c.getChildCount(); i++) {
                View ch = c.getChildAt(i);
                ch.setTranslationX(ch.getTranslationX() + shift);
            }
        }
    }

    private static void centerShelf(ViewGroup shelf, int screenWidth) {
        if (shelf.getVisibility() != View.VISIBLE || shelf.getWidth() == 0
                || shelf.getAlpha() < 0.01f) return;
        View parent = (View) shelf.getParent();
        if (parent == null) return;

        int full = shelf.getWidth();
        int pill = pillWidth(shelf);
        float target = 0f;
        if (pill > 0 && full - pill > 100) {
            target = (parent.getWidth() - pill) / 2f - shelf.getLeft();
        }
        if (Math.abs(shelf.getTranslationX() - target) > 1f) {
            shelf.setTranslationX(target);
        }
    }

    private static int pillWidth(ViewGroup shelf) {
        View bg = null;
        ViewGroup icons = null;
        for (int i = 0; i < shelf.getChildCount(); i++) {
            View c = shelf.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith("NotificationShelfBackgroundView")) bg = c;
            else if (n.endsWith("NotificationShelfIconContainer") && c instanceof ViewGroup) {
                icons = (ViewGroup) c;
            }
        }
        if (bg != null) {
            try {
                int w = XposedHelpers.getIntField(bg, "mActualWidth");
                if (w > 0) return w;
            } catch (Throwable ignored) {}
        }
        if (icons != null) {
            float minL = Float.MAX_VALUE;
            float maxR = -Float.MAX_VALUE;
            int n = 0;
            for (int i = 0; i < icons.getChildCount(); i++) {
                View ch = icons.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE || ch.getWidth() == 0) continue;
                float l = ch.getLeft() + ch.getTranslationX();
                minL = Math.min(minL, l);
                maxR = Math.max(maxR, l + ch.getWidth());
                n++;
            }
            if (n > 0) return (int) (maxR + minL);
        }
        return 0;
    }
}
