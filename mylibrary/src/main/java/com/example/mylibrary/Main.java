package com.example.mylibrary;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressLint({"SoonBlockedPrivateApi", "BlockedPrivateApi"})
public class Main {
    public static final String TAG = "日志";
    public static Context context = null;
    public static Handler handler;

    public static WindowManager windowManager = null;
    public static Map<Surface, SurfaceControl> surfaceControlSurfaceMap = new HashMap<>();
    public static Map<Integer, SurfaceControl> mirrorSurfaceMap = new HashMap<>();

    // 缓存的反射方法，避免每次调用重复查找
    private static Method sMirrorSurfaceMethod;
    private static Method sSetFlagsMethod;
    private static Method sSetLayerStackMethod;
    private static Method sReparentMethod;
    private static Method sSetTrustedOverlayMethod;

    static {
        try {
            Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
            sSetFlagsMethod = builderClass.getDeclaredMethod("setFlags", int.class);
            sSetFlagsMethod.setAccessible(true);
            sMirrorSurfaceMethod = SurfaceControl.class.getDeclaredMethod("mirrorSurface", SurfaceControl.class);
            sMirrorSurfaceMethod.setAccessible(true);
            Class<?> txClass = SurfaceControl.Transaction.class;
            sSetLayerStackMethod = txClass.getDeclaredMethod("setLayerStack", SurfaceControl.class, int.class);
            sSetLayerStackMethod.setAccessible(true);
            sReparentMethod = txClass.getDeclaredMethod("reparent", SurfaceControl.class, SurfaceControl.class);
            sReparentMethod.setAccessible(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sSetTrustedOverlayMethod = txClass.getDeclaredMethod("setTrustedOverlay", SurfaceControl.class, boolean.class);
                sSetTrustedOverlayMethod.setAccessible(true);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Context getSystemContext() {
        try {
            Class<?> atClazz = Class.forName("android.app.ActivityThread");
            Method systemMain = atClazz.getMethod("systemMain");
            Object activityThread = systemMain.invoke(null);
            Method getSystemContext = atClazz.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Context createContext() {
        Resources systemRes = Resources.getSystem();
        Field systemResField = null;
        try {
            // This class only exists on LG ROMs with broken implementations
            Class.forName("com.lge.systemservice.core.integrity.IntegrityManager");
            // If control flow goes here, we need the resource hack
            Resources wrapper = new ResourcesWrapper(systemRes);
            systemResField = Resources.class.getDeclaredField("mSystem");
            systemResField.setAccessible(true);
            systemResField.set(null, wrapper);
        } catch (ReflectiveOperationException ignored) {
        }

        Context systemContext = getSystemContext();
        Context context = null;
        try {
            context = systemContext.createPackageContext("com.android.shell", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            context = systemContext;
        }

        // Restore the system resources object after classloader is available
        if (systemResField != null) {
            try {
                systemResField.set(null, systemRes);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return context;
    }


    public static void main(String[] args) {
        Looper.prepareMainLooper();
        context = createContext();
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
    }

    public static void registerDisplayListener(Surface surface, int width, int height) {
        var displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                try {
                    SurfaceControl mirrorSurface = (SurfaceControl) sMirrorSurfaceMethod.invoke(null, surfaceControlSurfaceMap.get(surface));
                    SurfaceControl.Builder b = new SurfaceControl.Builder();
                    b.setName(UUID.randomUUID().toString());
                    b.setFormat(PixelFormat.RGBA_8888);
                    sSetFlagsMethod.invoke(b, 0);
                    b.setBufferSize(width, height);
                    var mirroredSurfaceControl = b.build();
                    SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
                    sSetLayerStackMethod.invoke(transaction, mirroredSurfaceControl, displayId);
                    transaction.setLayer(mirroredSurfaceControl, Integer.MAX_VALUE);
                    transaction.apply();
                    sSetLayerStackMethod.invoke(transaction, mirrorSurface, displayId);
                    sReparentMethod.invoke(transaction, mirrorSurface, mirroredSurfaceControl);
                    transaction.apply();
                    transaction.close();
                    mirrorSurfaceMap.put(displayId, mirrorSurface);
                } catch (Exception e) {
                    Log.d(TAG, "mirrorSurfaceMethod error " + e);
                }
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                SurfaceControl mirrorSurface = mirrorSurfaceMap.remove(displayId);
                if (mirrorSurface != null) {
                    SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                    try {
                        sReparentMethod.invoke(t, mirrorSurface, (Object) null);
                    } catch (ReflectiveOperationException e) {
                        Log.d(TAG, "reparent mirror error " + e);
                    }
                    t.apply();
                    t.close();
                    mirrorSurface.release();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        }, handler);
    }

    public static void loop() {
        Looper.loop();
    }

    public static int[] getDisplayInfo() {
        Display display = windowManager.getDefaultDisplay();
        Point size = new android.graphics.Point();
        display.getRealSize(size);
        return new int[]{size.x, size.y, display.getRotation()};
    }

    @SuppressLint({"SoonBlockedPrivateApi", "BlockedPrivateApi"})
    public static Surface createNativeWindow(int width, int height, boolean isHide, boolean isSecure) {
        SurfaceControl.Builder builder = new SurfaceControl.Builder();
        builder.setName(UUID.randomUUID().toString());
        builder.setFormat(PixelFormat.RGBA_8888);
        try {
            int flags = isSecure ? 0x80 : (isHide ? 0x40 : 0x0);
            sSetFlagsMethod.invoke(builder, flags);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        int rotation = getDisplayInfo()[2];
        if (rotation == 1 || rotation == 3) {
            builder.setBufferSize(width, height);
        } else {
            builder.setBufferSize(height, width);
        }
        var surfaceControl = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            try {
                sSetTrustedOverlayMethod.invoke(transaction, surfaceControl, true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            transaction.apply();
            transaction.close();
        }

        var surface = new Surface(surfaceControl);
        surfaceControlSurfaceMap.put(surface, surfaceControl);
        return surface;
    }

    public static void destroyNativeWindow(Surface surface) {
        if (surface == null) return;
        SurfaceControl sc = surfaceControlSurfaceMap.remove(surface);
        surface.release();
        if (sc != null) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            try {
                sReparentMethod.invoke(t, sc, (Object) null);
            } catch (ReflectiveOperationException e) {
                Log.d(TAG, "reparent error " + e);
            }
            t.apply();
            t.close();
            sc.release();
        }
    }

    public static void destroyNativeWindowOnMainThread(Surface surface) {
        handler.post(() -> destroyNativeWindow(surface));
    }

    public static void destroyAll() {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        // 先从父节点移除镜像层（依赖于原始 SurfaceControl）
        for (SurfaceControl mirrorSc : mirrorSurfaceMap.values()) {
            if (mirrorSc != null) {
                try {
                    sReparentMethod.invoke(t, mirrorSc, (Object) null);
                } catch (ReflectiveOperationException e) {
                    Log.d(TAG, "reparent mirror error " + e);
                }
            }
        }

        // 再从父节点移除原始 SurfaceControl
        for (Map.Entry<Surface, SurfaceControl> entry : surfaceControlSurfaceMap.entrySet()) {
            SurfaceControl sc = entry.getValue();
            if (sc != null) {
                try {
                    sReparentMethod.invoke(t, sc, (Object) null);
                } catch (ReflectiveOperationException e) {
                    Log.d(TAG, "reparent error " + e);
                }
            }
        }

        t.apply();
        t.close();

        // 释放镜像层
        for (SurfaceControl mirrorSc : mirrorSurfaceMap.values()) {
            if (mirrorSc != null) mirrorSc.release();
        }
        mirrorSurfaceMap.clear();

        // 释放原始 Surface 和 SurfaceControl
        for (Map.Entry<Surface, SurfaceControl> entry : surfaceControlSurfaceMap.entrySet()) {
            Surface surface = entry.getKey();
            SurfaceControl sc = entry.getValue();
            if (surface != null) surface.release();
            if (sc != null) sc.release();
        }
        surfaceControlSurfaceMap.clear();
    }

    public static void destroyAllOnMainThread() {
        handler.post(Main::destroyAll);
    }

    static class ResourcesWrapper extends Resources {
        public ResourcesWrapper(Resources res) throws ReflectiveOperationException {
            super(res.getAssets(), res.getDisplayMetrics(), res.getConfiguration());
            Method getImpl = Resources.class.getDeclaredMethod("getImpl");
            getImpl.setAccessible(true);
            Method setImpl = Resources.class.getDeclaredMethod("setImpl", getImpl.getReturnType());
            setImpl.setAccessible(true);
            Object impl = getImpl.invoke(res);
            setImpl.invoke(this, impl);
        }

        @Override
        public boolean getBoolean(int id) {
            try {
                return super.getBoolean(id);
            } catch (NotFoundException e) {
                return false;
            }
        }
    }

}
