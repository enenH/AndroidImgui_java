package com.example.mylibrary;

import static android.hardware.display.DisplayManager.EVENT_TYPE_DISPLAY_ADDED;
import static android.hardware.display.DisplayManager.EVENT_TYPE_DISPLAY_CHANGED;
import static android.hardware.display.DisplayManager.EVENT_TYPE_DISPLAY_REMOVED;


import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.Position;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.wrappers.ClipboardManager;

import com.genymobile.scrcpy.wrappers.ServiceManager;


import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import eu.chainfire.libcfsurface.SurfaceHost;

public class Main extends ContextWrapper implements Callable<Object[]> {

    public static final int PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY = 1 << 20;

    public static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000;
    public static final int SHELL_UID = 2000;
    public static final String TAG = "日志";
    public static Context context = null;

    public static WindowManager windowManager = null;
    public static Map<Surface, SurfaceControl> surfaceControlSurfaceMap = new HashMap<>();

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
            context = systemContext.createPackageContext(FakeContext.PACKAGE_NAME, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
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

    private final Map<Integer, SurfaceControl> mirrorSurfaceMap = new HashMap<>();

    public Main() {
        super(null);


        context = createContext();
        attachBaseContext(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        //controller = new Controller(null, null, new Options());
       /* injectTouchEvent ( 0, 0, 100, 100);
         injectTouchEvent( 1, 0, 100, 100);*/


       /* Log.d(TAG, "日志:  pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
        try {
            Method preloadFont = Typeface.class.getMethod("loadPreinstalledSystemFontMap");
            preloadFont.invoke(null);
        } catch (Exception e) {
            Log.d(TAG, "AIDLService: onCreate | Err: " + e.getMessage());
        }
        var surface = createNativeWindow(500, 500, false, false);
        var canvas = surface.lockCanvas(null);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setTextSize(50);
        canvas.drawText("Hello World", 100, 100, paint);
        surface.unlockCanvasAndPost(canvas);*/

        var displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @TargetApi(Build.VERSION_CODES.Q)
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
                    transaction.setLayer( mirroredSurfaceControl, Integer.MAX_VALUE);
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

            @TargetApi(Build.VERSION_CODES.Q)
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
            Log.e("IPC", "Error in IPCMain", e);
        }
        // Main thread event loop
        // Looper.loop();
    }

    public static Controller controller = null;

    public static void loop() {
        Looper.loop();
    }

    public static void injectTouchEvent(int action, long pointerId, int x, int y) {
        return;
       /* Log.d( TAG, "injectTouchEvent: 111");
        if (controller == null) {
             Log.d( TAG, "injectTouchEvent: 222");

        }
        Log.d( TAG, "injectTouchEvent: 333");
        if ( controller == null) {
            Log .e(TAG, "injectTouchEvent: controller is null");
        }*/
        /*var size = ServiceManager.getDisplayManager().getDisplayInfo(0).getSize();
        Log.d( TAG, "injectTouchEvent: x=" + x + " y=" + y + " w=" + size.getWidth() + " h=" + size.getHeight());
        controller.injectTouch(action, pointerId, new Position(x, y, size.getWidth(), size.getHeight()), 1.f, 0, 0);*/
    }

    public static String getClipboardText() {
        ClipboardManager manager = ServiceManager.getClipboardManager();
        if (manager == null) {
            return "";
        }
        var text = manager.getText();
        return text == null ? "" : text.toString();
    }

    public static boolean setClipboardText(String text) {
        ClipboardManager manager = ServiceManager.getClipboardManager();
        if (manager == null) {
            return false;
        }
        return manager.setText(text);
    }

    public static View getView(int width, int height, boolean hide, boolean secure) {
        View v;
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2 ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            v = new TextureView(context);
        } else {
            try {
                var surfaceView = new SurfaceView(context);
                surfaceView.setZOrderOnTop(true);
                surfaceView.getHolder().setFormat(PixelFormat.TRANSPARENT);
                v = surfaceView;

            } catch (Exception e) {
                v = new TextureView(context);
            }
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        params.type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.format = PixelFormat.RGBA_8888;
        if (width == -1 || height == -1) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
        } else {
            params.width = width;
            params.height = height;
        }
        params.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | //布局充满整个屏幕 忽略应用窗口限制
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |//不接受触控
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | //不接受焦点
                        //WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | //允许有触摸属性
                        //WindowManager.LayoutParams.FLAG_SPLIT_TOUCH | //接受多点触控
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | //硬件加速
                        WindowManager.LayoutParams.FLAG_FULLSCREEN | //全屏
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; //忽略屏幕边界

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            try {
                Field privateFlags = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
                privateFlags.setAccessible(true);
                privateFlags.setInt(params, privateFlags.getInt(params) |
                        PRIVATE_FLAG_TRUSTED_OVERLAY);
            } catch (Exception ignored) {

            }
        }

        if (hide) {
            try {
                Field privateFlags = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
                privateFlags.setAccessible(true);
                privateFlags.setInt(params, privateFlags.getInt(params) |
                        PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY);
            } catch (Exception ignored) {
            }
        }
        if (secure && !hide) {
            params.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;//覆盖刘海
        }

        windowManager.addView(v, params);
        return v;
    }

    public static Surface getSurface(View view) {
        if (view == null) {
            return null;
        }
        if (view instanceof SurfaceView surfaceView) {
            if (surfaceView.getHolder().getSurface().isValid()) {
                return surfaceView.getHolder().getSurface();
            }
            return null;
        }
        TextureView textureView = (TextureView) view;
        if (textureView.isAvailable()) {
            return new Surface(textureView.getSurfaceTexture());
        }
        return null;
    }

    public static void removeView(View view) {
        if (view == null) {
            return;
        }
        handler.post(() -> windowManager.removeViewImmediate(view));

        Looper.getMainLooper().quit();
    }

    public static int[] getDisplayInfo() {
        android.view.Display display = windowManager.getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        return new int[]{size.x, size.y, display.getRotation()};
    }

    @TargetApi(Build.VERSION_CODES.Q)
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

    @TargetApi(Build.VERSION_CODES.Q)
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

    public static SurfaceHost mSurfaceHost;

    public static Surface createNativeWindow2(int width, int height, boolean isHide, boolean isSecure) {
        if (SurfaceHost.mContext == null) {
            SurfaceHost.mContext = createContext();
        }
        mSurfaceHost = new SurfaceHost();
        mSurfaceHost.initSurface();
        mSurfaceHost.updateSurfaceVisibility();
        return mSurfaceHost.mSurface;
    }

    public static List<Integer> getUserIds() {
        List<Integer> result = new ArrayList<>();
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        List<UserHandle> userProfiles = um.getUserProfiles();
        for (UserHandle userProfile : userProfiles) {
            int userId = userProfile.hashCode();
            result.add(userProfile.hashCode());
        }
        return result;
    }

    public static ArrayList<PackageInfo> getInstalledPackagesAll(int flags) {
        ArrayList<PackageInfo> packages = new ArrayList<>();
        for (Integer userId : getUserIds()) {
            packages.addAll(getInstalledPackagesAsUser(flags, userId));
        }
        packages.get(0).applicationInfo.loadLabel(context.getPackageManager());
        return packages;
    }

    public static List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
        try {
            PackageManager pm = context.getPackageManager();
            Method getInstalledPackagesAsUser = pm.getClass().getDeclaredMethod("getInstalledPackagesAsUser", int.class, int.class);
            return (List<PackageInfo>) getInstalledPackagesAsUser.invoke(pm, flags, userId);

        } catch (Throwable e) {
            Log.e(TAG, "err", e);
        }
        return new ArrayList<>();
    }

    private static Bitmap getBitmapFromDrawable(Drawable drawable) {
        final Bitmap bmp = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bmp);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bmp;
    }

    public static MyPackageInfo[] getInstalledPackagesAll() {
        var pm = context.getPackageManager();
        List<PackageInfo> packageInfos = getInstalledPackagesAll(0);
        MyPackageInfo[] myPackageInfos = new MyPackageInfo[packageInfos.size()];
        for (int i = 0; i < packageInfos.size(); i++) {
            PackageInfo packageInfo = packageInfos.get(i);
            MyPackageInfo myPackageInfo = new MyPackageInfo();
            myPackageInfo.packageName = packageInfo.packageName;
            myPackageInfo.label = packageInfo.applicationInfo.loadLabel(pm).toString();
            myPackageInfos[i] = myPackageInfo;
        }
        return myPackageInfos;
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
