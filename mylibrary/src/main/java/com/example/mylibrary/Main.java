package com.example.mylibrary;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

public class Main extends ContextWrapper implements Callable<Object[]> {

    public static final int PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY = 1 << 20;

    public static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000;
    public static final int SHELL_UID = 2000;
    public static final String TAG = "日志";
    public static Context context = null;

    public static WindowManager windowManager = null;
    public static Map<Surface, SurfaceControl> surfaceControlSurfaceMap = new HashMap<>();
    private final Map<Integer, SurfaceControl> mirrorSurfaceMap = new HashMap<>();

    public static Handler handler;

    public static Surface firstSurface = null;
    public static int firstSurfaceWidth = 0;
    public static int firstSurfaceHeight = 0;

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

    @SuppressLint("PrivateApi")
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

    public Main() {
        super(null);

        context = createContext();
        attachBaseContext(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        var displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                Log.d(TAG, "onDisplayAdded: " + displayId);
                if (firstSurface == null)
                    return;

                try {
                    Method mirrorSurfaceMethod = SurfaceControl.class
                            .getDeclaredMethod("mirrorSurface", SurfaceControl.class);
                    mirrorSurfaceMethod.setAccessible(true);
                    SurfaceControl mirrorSurface = (SurfaceControl) mirrorSurfaceMethod.invoke(null, surfaceControlSurfaceMap.get(firstSurface));
                    SurfaceControl.Builder b = new SurfaceControl.Builder();
                    b.setName(UUID.randomUUID().toString());
                    b.setFormat(PixelFormat.RGBA_8888);
                    Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
                    Method setFlagsMethod = builderClass.getDeclaredMethod("setFlags", int.class);
                    setFlagsMethod.setAccessible(true);
                    setFlagsMethod.invoke(b, 0);
                    b.setBufferSize(firstSurfaceWidth, firstSurfaceWidth);
                    var mirroredSurfaceControl = b.build();
                    SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
                    //public Transaction setLayerStack(SurfaceControl sc, int layerStack)
                    Method setLayerStackMethod = SurfaceControl.Transaction.class
                            .getDeclaredMethod("setLayerStack", SurfaceControl.class, int.class);
                    setLayerStackMethod.setAccessible(true);
                    setLayerStackMethod.invoke(transaction, mirroredSurfaceControl, displayId);
                    transaction.setLayer(mirroredSurfaceControl, Integer.MAX_VALUE);
                    transaction.apply();

                    setLayerStackMethod.invoke(transaction, mirrorSurface, displayId);
                    Method reparentMethod = SurfaceControl.Transaction.class
                            .getDeclaredMethod("reparent", SurfaceControl.class, SurfaceControl.class);
                    reparentMethod.setAccessible(true);
                    reparentMethod.invoke(transaction, mirrorSurface, mirroredSurfaceControl);
                    transaction.apply();
                    transaction.close();
                    mirrorSurfaceMap.put(displayId, mirrorSurface);
                } catch (Exception e) {
                    Log.d(TAG, "mirrorSurfaceMethod error " + e);
                }
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                Log.d(TAG, "onDisplayRemoved: " + displayId);
            }

            @Override
            public void onDisplayChanged(int displayId) {
                Log.d(TAG, "onDisplayChanged: " + displayId);
                if (firstSurface == null) return;
                try {
                    SurfaceControl mirrorSurface = mirrorSurfaceMap.remove(displayId);
                    if (mirrorSurface != null) {
                        mirrorSurface.release();
                    }
                } catch (Exception e) {
                    Log.d(TAG, "remove mirror error " + e);
                }
            }
        }, handler);
    }

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        try {
            new Main();
        } catch (Exception e) {
            Log.e(TAG, "Error in IPCMain", e);
        }
        // Main thread event loop
        // Looper.loop();
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

    public static Surface createNativeWindow(int width, int height, boolean isHide, boolean isSecure) {
        SurfaceControl.Builder builder = new SurfaceControl.Builder();
        builder.setName(UUID.randomUUID().toString());
        builder.setFormat(PixelFormat.RGBA_8888);
        if (Build.VERSION.SDK_INT <= 30) {
            try {

                Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
                Method setMetadataMethod = builderClass.getDeclaredMethod("setMetadata", int.class, int.class);
                setMetadataMethod.setAccessible(true);
                if (isHide && !isSecure)
                    setMetadataMethod.invoke(builder, 2, 441731);
                Method setFlagsMethod = builderClass.getDeclaredMethod("setFlags", int.class);
                setFlagsMethod.setAccessible(true);
                setFlagsMethod.invoke(builder, isSecure ? 0x80 : 0x0);
            } catch (ClassNotFoundException | IllegalAccessException |
                     NoSuchMethodException | InvocationTargetException ignored) {
            }
        } else {
            try {

                Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
                Method setFlagsMethod = builderClass.getDeclaredMethod("setFlags", int.class);
                setFlagsMethod.setAccessible(true);
                setFlagsMethod.invoke(builder, isSecure ? 0x80 : isHide ? 0x40 : 0x0);
            } catch (ClassNotFoundException | IllegalAccessException |
                     NoSuchMethodException | InvocationTargetException ignored) {
            }
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
            //transaction.setLayer(surfaceControl, Integer.MAX_VALUE);
            //public Transaction setTrustedOverlay(SurfaceControl sc, boolean isTrustedOverlay) {
            try {
                Method setTrustedOverlayMethod = SurfaceControl.Transaction.class
                        .getDeclaredMethod("setTrustedOverlay", SurfaceControl.class, boolean.class);
                setTrustedOverlayMethod.setAccessible(true);
                setTrustedOverlayMethod.invoke(transaction, surfaceControl, true);
            } catch (Exception e) {
                System.out.println("setTrustedOverlayMethod error " + e);
            }
            transaction.apply();
            transaction.close();
        }

        var surface = new Surface(surfaceControl);
        surfaceControlSurfaceMap.put(surface, surfaceControl);
        if (firstSurface == null) {
            firstSurface = surface;
            firstSurfaceHeight = height;
            firstSurfaceWidth = width;
        }
        return surface;
    }

    public static void destroyNativeWindow(Surface surface) {
        if (surface == null) {
            return;
        }
        surface.release();
        SurfaceControl surfaceControl = surfaceControlSurfaceMap.get(surface);
        if (surfaceControl == null) {
            return;
        }
        surfaceControl.release();
    }

    @Override
    public Object[] call() throws Exception {
        return new Object[0];
    }

    static class ResourcesWrapper extends Resources {

        @SuppressLint("PrivateApi")
        @SuppressWarnings("JavaReflectionMemberAccess")
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
