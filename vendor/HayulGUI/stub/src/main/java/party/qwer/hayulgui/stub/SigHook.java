package party.qwer.hayulgui.stub;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * SigKill 패턴의 clean-room 재구현: 대상 패키지 self 의 pm 응답 서명을
 * 원본 인증서(hayulgui.cfg 의 sig= 값)로 위조한다.
 *
 * <p>두 갈래를 훅한다:
 * <ol>
 *   <li>{@code ActivityThread.sPackageManager} 싱글턴 — 앱 코드가 쓰는 대부분 경로</li>
 *   <li>{@code ServiceManager} 캐시 안의 "package" 바인더 — getService 우회 경로(best effort)</li>
 * </ol>
 * 응답 객체의 실행타입이 PackageInfo 이면 target 패키지일 때만 signatures /
 * signingInfo 를 갈아낀다.
 */
final class SigHook {

    private static final String TAG = "HayulGUI";

    /** ServiceManager 캐시 바인더 래핑 — 안전성 검증 전까지 off (crash cause). */
    private static final boolean ENABLE_SERVICE_CACHE_HOOK = false;

    private final byte[] certDer;
    private volatile String targetPackage;
    private volatile boolean installed;

    SigHook(byte[] originalCertDer) {
        this.certDer = originalCertDer;
    }

    /** currentApplication() 이 생성될 때까지 대기했다가 훅을 설치한다. */
    void installAsync() {
        if (certDer == null || certDer.length == 0) {
            Log.w(TAG, "stub: no original signature configured, hook skipped");
            return;
        }
        Thread t = new Thread(() -> {
            enableHiddenApi();
            for (int i = 0; i < 12000 && !installed; i++) {
                try {
                    Class<?> at = Class.forName("android.app.ActivityThread");
                    Object app = at.getMethod("currentApplication").invoke(null);
                    if (app != null) {
                        install(app);
                        installed = true;
                        Log.i(TAG, "stub: signature hook installed");
                    }
                } catch (Throwable ignored) {
                }
                if (!installed) {
                    try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                }
            }
            if (!installed) Log.w(TAG, "stub: signature hook install timed out");
        }, "HayulSigHook");
        t.setDaemon(true);
        t.start();
    }

    private void install(Object currentApplication) throws Throwable {
        try {
            targetPackage = (String) currentApplication.getClass()
                    .getMethod("getPackageName").invoke(currentApplication);
        } catch (Throwable ignored) {
        }
        // (1) ActivityThread.sPackageManager
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Field sf = activityThread.getDeclaredField("sPackageManager");
        sf.setAccessible(true);
        Object pm = sf.get(null);
        if (pm != null && !Proxy.isProxyClass(pm.getClass())) {
            Object wrapper = Proxy.newProxyInstance(
                    getClass().getClassLoader(), pm.getClass().getInterfaces(),
                    new Handler(pm));
            sf.set(null, wrapper);
        }

        // (2) ServiceManager cache hook — OFF by default.
        // The cache entry must stay an IBinder; a Proxy implementing
        // IPackageManager crashes OEM code that casts it back (e.g. Oplus
        // OplusCommonManager -> "$Proxy5 cannot be cast to IBinder"). The
        // sPackageManager wrapper above already covers app-level PM calls.
        if (ENABLE_SERVICE_CACHE_HOOK) hookServiceManagerCache();
    }

    /** ServiceManager 내부 캐시(sCache / sServiceCache)의 "package" 엔트리를 교체. */
    private void hookServiceManagerCache() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            try {
                // API 26~28: static HashMap<String, IBinder> sCache
                java.lang.reflect.Field f = sm.getDeclaredField("sCache");
                f.setAccessible(true);
                Object map = f.get(null);
                if (map instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) map;
                    Object binder = m.get("package");
                    Object wrapped = wrapBinder(binder);
                    if (wrapped != null) m.put("package", wrapped);
                }
            } catch (NoSuchFieldException newer) {
                // API 29+: static List<CachedService> sServiceCache, 각 항목은 mService
                java.lang.reflect.Field f = sm.getDeclaredField("sServiceCache");
                f.setAccessible(true);
                Object list = f.get(null);
                if (list instanceof List) {
                    for (Object entry : (List<?>) list) {
                        java.lang.reflect.Field ms = entry.getClass().getDeclaredField("mService");
                        ms.setAccessible(true);
                        Object binder = ms.get(entry);
                        if (binder != null) {
                            Object wrapped = wrapBinder(binder);
                            if (wrapped != null) ms.set(entry, wrapped);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "stub: ServiceManager cache hook unavailable: " + t);
        }
    }

    private Object wrapBinder(Object binder) {
        if (binder == null || Proxy.isProxyClass(binder.getClass())) return null;
        try {
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = stub.getMethod("asInterface", android.os.IBinder.class);
            Object iface = asInterface.invoke(null, binder);
            if (iface == null) return null;
            return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    iface.getClass().getInterfaces(),
                    new Handler(iface));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 반환 타입이 PackageInfo 이면 대상 패키지일 때만 서명을 위조한다. */
    private final class Handler implements InvocationHandler {
        private final Object orig;

        Handler(Object orig) { this.orig = orig; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object ret = method.invoke(orig, args);
            if (ret instanceof PackageInfo) {
                PackageInfo pi = (PackageInfo) ret;
                if (targetPackage == null || targetPackage.equals(pi.packageName)) {
                    spoof(pi);
                }
            }
            return ret;
        }
    }

    private void spoof(PackageInfo pi) {
        try {
            Signature sig = new Signature(certDer);
            pi.signatures = new Signature[]{sig};
            // API28+ signingInfo 도 가능하면 함께 위조 (실패해도 signatures만으로도 상당수 경로 커버)
            java.lang.reflect.Field siField = PackageInfo.class.getDeclaredField("signingInfo");
            siField.setAccessible(true);
            Object si = siField.get(pi);
            if (si != null) {
                Object replaced = newSigningInfo(si.getClass(), sig);
                if (replaced != null) siField.set(pi, replaced);
            }
        } catch (Throwable t) {
            Log.d(TAG, "stub: spoof failed: " + t);
        }
    }

    private Object newSigningInfo(Class<?> cls, Signature sig) {
        Signature[] sigs = new Signature[]{sig};
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            try {
                c.setAccessible(true);
                if (p.length == 1 && p[0].equals(Signature[].class)) return c.newInstance((Object) sigs);
                if (p.length == 1 && p[0].isArray() && p[0].getComponentType() == byte[].class) {
                    return c.newInstance((Object) new byte[][]{certDer});
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** hidden-api 정책(VMRuntime.setHiddenApiExemptions) 우회 — 표준 트릭. */
    private static void enableHiddenApi() {
        try {
            Method gdm = Class.class.getDeclaredMethod("getDeclaredMethod",
                    String.class, Class[].class);
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = (Method) gdm.invoke(vm, "getRuntime", new Class[0]);
            Method setExempt = (Method) gdm.invoke(vm, "setHiddenApiExemptions",
                    new Class[]{String[].class});
            setExempt.invoke(getRuntime.invoke(null), (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // 정책이 완강하면 signatures 필드 경로만으로 동작.
        }
    }
}
