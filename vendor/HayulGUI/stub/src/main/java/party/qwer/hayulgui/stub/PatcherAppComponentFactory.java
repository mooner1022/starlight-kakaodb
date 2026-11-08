package party.qwer.hayulgui.stub;

import android.app.AppComponentFactory;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.pm.ApplicationInfo;
import android.content.Intent;

import java.lang.reflect.Constructor;

/**
 * HayulGUI stub entry point (clean-room SigKill pattern).
 *
 * <p>설치된 APK manifest 가 이 클래스를 appComponentFactory 로 지정하면 프로세스
 * 시작 시 가장 먼저 인스턴스화된다. 여기서 (1) 원본 서명검증 우회 훅을 설치하고,
 * (2) 원래 appComponentFactory 가 있었다면 생성하여 호출을 위임한다.
 *
 * <p>override 의 API 수준을 애매하게 만들지 않기 위해 framework 기본 동작과
 * delegate 로 위임만 한다. (delegate 는 원래 manifest 가 선언했던 factory)
 */
public final class PatcherAppComponentFactory extends AppComponentFactory {

    private AppComponentFactory delegate;   // 원본 manifest 가 선언했던 factory (있을 때만)
    private volatile boolean hooksInstalled;

    public PatcherAppComponentFactory() {
        StubConfig.load();
        String name = StubConfig.originalFactory;
        if (name != null && !name.isEmpty() && !name.equals(PatcherAppComponentFactory.class.getName())) {
            try {
                Class<?> cls = Class.forName(name);
                Constructor<?> ctor = cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                delegate = (AppComponentFactory) ctor.newInstance();
            } catch (Throwable ignored) {
                delegate = null;
            }
        }
        installHooks();
    }

    private void installHooks() {
        if (hooksInstalled) return;
        hooksInstalled = true;
        SigHook hook = new SigHook(StubConfig.originalSignature);
        hook.installAsync();
    }

    // ---- delegate 위임: 위임 대상이 없으면 super(=framework 기본 동작) ----

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo appInfo) {
        if (delegate != null) {
            try { return delegate.instantiateClassLoader(cl, appInfo); } catch (Throwable ignored) { }
        }
        return super.instantiateClassLoader(cl, appInfo);
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (delegate != null) {
            try { return delegate.instantiateApplication(cl, className); }
            catch (Throwable ignored) { }
        }
        return super.instantiateApplication(cl, className);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (delegate != null) {
            try { return delegate.instantiateReceiver(cl, className, intent); }
            catch (Throwable ignored) { }
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public android.app.Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (delegate != null) {
            try { return delegate.instantiateService(cl, className, intent); }
            catch (Throwable ignored) { }
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public android.content.ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (delegate != null) {
            try { return delegate.instantiateProvider(cl, className); }
            catch (Throwable ignored) { }
        }
        return super.instantiateProvider(cl, className);
    }

}
