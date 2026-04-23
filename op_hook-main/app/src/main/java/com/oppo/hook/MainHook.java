package io.github.oppohook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "OPPOTokenHook";
    private static final String TARGET_PACKAGE = "com.oppo.store";
    private static final String TOKEN_FILE = "/sdcard/Download/oppo_tokens.json";
    private static final Set<String> capturedTokens = new HashSet<>();
    private static volatile Context appContext;
    private static volatile boolean hasShownTokenDialog;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Hooking " + TARGET_PACKAGE);
        log("开始 Hook OPPO 商城...");

        // Hook Application onCreate
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation",
            lpparam.classLoader,
            "callApplicationOnCreate",
            Application.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.args[0];
                    if (context != null) {
                        appContext = context.getApplicationContext() != null
                            ? context.getApplicationContext()
                            : context;
                    }
                    hookAll(context, lpparam.classLoader);
                }
            }
        );
    }

    private void hookAll(Context context, ClassLoader classLoader) {
        log("Application 已创建，开始 Hook...");

        // 1. Hook WebView Cookie (3-arg version)
        hookWebViewCookie3Arg(classLoader);

        // 2. Hook WebView loadUrl
        hookWebViewLoadUrl(classLoader);

        // 3. Hook WebView evaluateJavascript
        hookWebViewEvaluateJavascript(classLoader);

        // 4. Hook WebViewClient
        hookWebViewClient(classLoader);

        // 5. Hook WebChromeClient (for RainbowBridge prompt)
        hookWebChromeClient(classLoader);

        // 6. Hook OkHttp Request Builder
        hookOkHttpRequestBuilder(classLoader);

        // 7. Hook HttpURLConnection
        hookHttpURLConnection(classLoader);

        // 8. Hook SharedPreferences
        hookSharedPreferences(classLoader);

        // 9. Hook CookieManager concrete implementation
        hookCookieManagerImpl(classLoader);

        // 10. Hook all methods with TOKENSID in name
        hookTokenRelatedMethods(classLoader);

        log("所有 Hook 已设置");
        showToast(context, "OPPO Token Hook 已激活");
    }

    private void hookWebViewCookie3Arg(ClassLoader classLoader) {
        try {
            Class<?> cmClass = XposedHelpers.findClass("android.webkit.CookieManager", classLoader);
            java.lang.reflect.Method setCookieMethod = XposedHelpers.findMethodExactIfExists(
                cmClass,
                "setCookie",
                String.class,
                String.class,
                android.webkit.ValueCallback.class
            );

            if (setCookieMethod != null && !java.lang.reflect.Modifier.isAbstract(setCookieMethod.getModifiers())) {
                XposedBridge.hookMethod(setCookieMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 2 && param.args[1] instanceof String) {
                            String cookie = (String) param.args[1];
                            if (cookie != null && (cookie.contains("TOKENSID") || cookie.contains("TOKEN_"))) {
                                log("WebView.setCookie(3arg): " + cookie);
                                extractAndSaveToken(cookie, "WebView.setCookie(3arg)");
                            }
                        }
                    }
                });
                log("WebView Cookie 3-arg Hook 已设置");
            } else {
                log("WebView Cookie 3-arg Hook 跳过: 未找到可 Hook 的具体实现");
            }
        } catch (Throwable t) {
            log("WebView Cookie 3-arg Hook 失败: " + t.getMessage());
        }
    }

    private void hookWebViewLoadUrl(ClassLoader classLoader) {
        try {
            Class<?> webViewClass = XposedHelpers.findClass("android.webkit.WebView", classLoader);
            XposedBridge.hookAllMethods(webViewClass, "loadUrl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 1 && param.args[0] instanceof String) {
                        String url = (String) param.args[0];
                        if (url != null && (url.contains("TOKENSID") || url.contains("TOKEN_") || url.contains("token"))) {
                            log("WebView.loadUrl: " + url.substring(0, Math.min(200, url.length())));
                            extractAndSaveToken(url, "WebView.loadUrl");
                        }
                    }
                }
            });
            log("WebView loadUrl Hook 已设置");
        } catch (Throwable t) {
            log("WebView loadUrl Hook 失败: " + t.getMessage());
        }
    }

    private void hookWebViewEvaluateJavascript(ClassLoader classLoader) {
        try {
            Class<?> webViewClass = XposedHelpers.findClass("android.webkit.WebView", classLoader);
            XposedBridge.hookAllMethods(webViewClass, "evaluateJavascript", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 1 && param.args[0] instanceof String) {
                        String js = (String) param.args[0];
                        if (js != null && (js.contains("TOKENSID") || js.contains("TOKEN_") || js.contains("cookie"))) {
                            log("WebView.evaluateJavascript: " + js.substring(0, Math.min(200, js.length())));
                            extractAndSaveToken(js, "WebView.evaluateJavascript");
                        }
                    }
                }
            });
            log("WebView evaluateJavascript Hook 已设置");
        } catch (Throwable t) {
            log("WebView evaluateJavascript Hook 失败: " + t.getMessage());
        }
    }

    private void hookWebViewClient(ClassLoader classLoader) {
        try {
            Class<?> wvcClass = XposedHelpers.findClass("android.webkit.WebViewClient", classLoader);
            XposedBridge.hookAllMethods(wvcClass, "onPageFinished", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 2 && param.args[1] instanceof String) {
                        String url = (String) param.args[1];
                        log("WebViewClient.onPageFinished: " + url);
                    }
                }
            });
            log("WebViewClient Hook 已设置");
        } catch (Throwable t) {
            log("WebViewClient Hook 失败: " + t.getMessage());
        }
    }

    private void hookWebChromeClient(ClassLoader classLoader) {
        try {
            Class<?> whcClass = XposedHelpers.findClass("android.webkit.WebChromeClient", classLoader);
            XposedBridge.hookAllMethods(whcClass, "onJsPrompt", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 3 && param.args[2] instanceof String) {
                        String msg = (String) param.args[2];
                        if (msg != null && (msg.contains("TOKENSID") || msg.contains("TOKEN_") || msg.contains("rainbow://"))) {
                            log("WebChromeClient.onJsPrompt: " + msg.substring(0, Math.min(200, msg.length())));
                        }
                    }
                }
            });
            log("WebChromeClient Hook 已设置");
        } catch (Throwable t) {
            log("WebChromeClient Hook 失败: " + t.getMessage());
        }
    }

    private void hookOkHttpRequestBuilder(ClassLoader classLoader) {
        try {
            Class<?> builderClass = XposedHelpers.findClass("okhttp3.Request$Builder", classLoader);
            XposedBridge.hookAllMethods(builderClass, "addHeader", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 2 && param.args[1] instanceof String) {
                        String name = (String) param.args[0];
                        String value = (String) param.args[1];
                        if ("TOKENSID".equalsIgnoreCase(name) || value.contains("TOKEN_")) {
                            log("OkHttp addHeader: " + name + " = " + value.substring(0, Math.min(100, value.length())));
                            extractAndSaveToken(value, "OkHttp.addHeader");
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(builderClass, "header", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 2 && param.args[1] instanceof String) {
                        String name = (String) param.args[0];
                        String value = (String) param.args[1];
                        if ("TOKENSID".equalsIgnoreCase(name) || value.contains("TOKEN_")) {
                            log("OkHttp header: " + name + " = " + value.substring(0, Math.min(100, value.length())));
                            extractAndSaveToken(value, "OkHttp.header");
                        }
                    }
                }
            });
            log("OkHttp Request Builder Hook 已设置");
        } catch (Throwable t) {
            log("OkHttp Request Builder Hook 失败: " + t.getMessage());
        }
    }

    private void hookHttpURLConnection(ClassLoader classLoader) {
        try {
            Class<?> connClass = XposedHelpers.findClass("java.net.HttpURLConnection", classLoader);
            XposedBridge.hookAllMethods(connClass, "setRequestProperty", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 2 && param.args[1] instanceof String) {
                        String name = (String) param.args[0];
                        String value = (String) param.args[1];
                        if ("TOKENSID".equalsIgnoreCase(name) || value.contains("TOKEN_")) {
                            log("HttpURLConnection.setRequestProperty: " + name + " = " + value.substring(0, Math.min(100, value.length())));
                            extractAndSaveToken(value, "HttpURLConnection");
                        }
                    }
                }
            });
            log("HttpURLConnection Hook 已设置");
        } catch (Throwable t) {
            log("HttpURLConnection Hook 失败: " + t.getMessage());
        }
    }

    private void hookSharedPreferences(ClassLoader classLoader) {
        try {
            Class<?> prefsClass = XposedHelpers.findClass("android.app.SharedPreferencesImpl", classLoader);
            XposedBridge.hookAllMethods(prefsClass, "getString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String result = (String) param.getResult();
                    if (key != null && key.toLowerCase().contains("token")) {
                        log("SharedPreferences.getString: " + key + " = " +
                            (result != null ? result.substring(0, Math.min(50, result.length())) + "..." : "null"));
                        if (result != null && result.contains("TOKEN_")) {
                            extractAndSaveToken(result, "SharedPreferences." + key);
                        }
                    }
                }
            });
            // Also hook putString
            XposedBridge.hookAllMethods(prefsClass, "putString", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.args[1];
                    if (key != null && key.toLowerCase().contains("token")) {
                        log("SharedPreferences.putString: " + key + " = " +
                            (value != null ? value.substring(0, Math.min(50, value.length())) + "..." : "null"));
                        if (value != null && value.contains("TOKEN_")) {
                            extractAndSaveToken(value, "SharedPreferences.put." + key);
                        }
                    }
                }
            });
            log("SharedPreferences Hook 已设置");
        } catch (Throwable t) {
            log("SharedPreferences Hook 失败: " + t.getMessage());
        }
    }

    private void hookCookieManagerImpl(ClassLoader classLoader) {
        try {
            // Try to find the concrete implementation
            Class<?> cmClass = XposedHelpers.findClass("android.webkit.CookieManager", classLoader);
            // Hook getInstance to find the implementation class
            XposedBridge.hookAllMethods(cmClass, "getInstance", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object instance = param.getResult();
                    if (instance != null) {
                        String implClass = instance.getClass().getName();
                        log("CookieManager implementation: " + implClass);
                    }
                }
            });
            log("CookieManager getInstance Hook 已设置");
        } catch (Throwable t) {
            log("CookieManager getInstance Hook 失败: " + t.getMessage());
        }
    }

    private void hookTokenRelatedMethods(ClassLoader classLoader) {
        // Hook common OPPO store classes
        String[] classes = {
            "com.oppo.store.common.CookieManager",
            "com.oppo.store.account.AccountManager",
            "com.oppo.store.network.CookieInterceptor",
            "com.oppo.store.webview.WebViewManager",
            "com.oppo.store.auth.AuthManager",
            "com.oppo.store.user.UserManager",
            "com.oppo.store.bean.TokenBean",
            "com.oppo.store.model.TokenModel",
            "com.oppo.store.network.TokenInterceptor",
            "com.oppo.store.auth.TokenManager",
            "com.oppo.store.cookie.CookieManager",
            "com.oppo.store.util.TokenUtil",
            "com.oppo.store.common.TokenHelper",
        };

        for (String className : classes) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof String) {
                                        String arg = (String) param.args[i];
                                        if (arg.contains("TOKENSID") || arg.contains("TOKEN_")) {
                                            log(className + "." + method.getName() + " arg[" + i + "]: " +
                                                arg.substring(0, Math.min(100, arg.length())));
                                            extractAndSaveToken(arg, className + "." + method.getName());
                                        }
                                    }
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object result = param.getResult();
                                if (result instanceof String) {
                                    String str = (String) result;
                                    if (str.contains("TOKENSID") || str.contains("TOKEN_")) {
                                        log(className + "." + method.getName() + " returned: " +
                                            str.substring(0, Math.min(100, str.length())));
                                        extractAndSaveToken(str, className + "." + method.getName() + ".return");
                                    }
                                }
                            }
                        });
                    } catch (Throwable t) {
                        // ignore
                    }
                }
                log(className + " Hook 已设置");
            } catch (Throwable t) {
                // class not found
            }
        }
    }

    private void extractAndSaveToken(String cookieOrToken, String source) {
        try {
            String token = null;

            if (cookieOrToken.contains("TOKENSID=")) {
                int start = cookieOrToken.indexOf("TOKENSID=") + 9;
                int end = findTokenEnd(cookieOrToken, start);
                token = cookieOrToken.substring(start, end).trim();
            } else if (cookieOrToken.startsWith("TOKEN_")) {
                token = cookieOrToken.trim();
            } else if (cookieOrToken.contains("TOKEN_")) {
                int start = cookieOrToken.indexOf("TOKEN_");
                int end = findTokenEnd(cookieOrToken, start);
                token = cookieOrToken.substring(start, end).trim();
            }

            if (token != null) {
                token = sanitizeToken(token);
            }

            if (token != null && !token.isEmpty() && !capturedTokens.contains(token)) {
                capturedTokens.add(token);
                log("提取到 Token(" + source + "): " + token.substring(0, Math.min(50, token.length())) + "...");
                saveToken(token, source);
                showTokenDialog(token, source);
            }
        } catch (Throwable t) {
            log("提取 Token 失败: " + t.getMessage());
        }
    }

    private int findTokenEnd(String content, int start) {
        int end = content.length();
        char[] delimiters = new char[] {' ', ';', '"', '\'', ',', ')', '}', '\n', '\r', '\t'};
        for (char delimiter : delimiters) {
            int index = content.indexOf(delimiter, start);
            if (index != -1 && index < end) {
                end = index;
            }
        }
        return end;
    }

    private String sanitizeToken(String token) {
        String cleaned = token.trim();
        while (!cleaned.isEmpty()) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if (last == '"' || last == '\'' || last == ',' || last == ')' || last == '}' || last == ']') {
                cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
            } else {
                break;
            }
        }
        return cleaned;
    }

    private void saveToken(String token, String source) {
        try {
            File file = new File(TOKEN_FILE);
            boolean exists = file.exists();

            PrintWriter writer = new PrintWriter(new FileWriter(file, true));

            if (!exists) {
                writer.println("[");
            } else {
                long length = file.length();
                if (length > 2) {
                    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw");
                    raf.setLength(length - 2);
                    raf.close();
                    writer.println(",");
                }
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());

            writer.println("  {");
            writer.println("    \"token\": \"" + token + "\",");
            writer.println("    \"source\": \"" + source + "\",");
            writer.println("    \"captured_at\": \"" + timestamp + "\"");
            writer.println("  }");
            writer.println("]");
            writer.close();

            log("Token 已保存到: " + TOKEN_FILE);
            log("Source: " + source);

        } catch (Throwable t) {
            log("保存 Token 失败: " + t.getMessage());
        }
    }

    private void showTokenDialog(String token, String source) {
        Context context = appContext;
        if (context == null) {
            showToast(null, "已捕获 OPPO Token");
            return;
        }

        synchronized (MainHook.class) {
            if (hasShownTokenDialog) {
                return;
            }
            hasShownTokenDialog = true;
        }

        try {
            Intent intent = new Intent();
            intent.setClassName("io.github.oppohook", "io.github.oppohook.TokenDisplayActivity");
            intent.putExtra("extra_token", token);
            intent.putExtra("extra_source", source);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Throwable t) {
            hasShownTokenDialog = false;
            log("启动 Token 展示页失败: " + t.getMessage());
            showToast(context, "已捕获 OPPO Token");
        }
    }

    private void showToast(Context context, String message) {
        try {
            Context toastContext = context != null ? context : appContext;
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                if (toastContext != null) {
                    Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable t) {
            log("显示 Toast 失败: " + t.getMessage());
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
        XposedBridge.log(TAG + ": " + message);

        try {
            File logFile = new File("/sdcard/Download/oppo_hook.log");
            PrintWriter writer = new PrintWriter(new FileWriter(logFile, true));
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date());
            writer.println("[" + timestamp + "] " + message);
            writer.close();
        } catch (Throwable t) {
            // ignore
        }
    }
}
