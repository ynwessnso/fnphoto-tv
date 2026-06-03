package com.fnphoto.tv.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp 拦截器：检测 401 响应时自动重新登录并重试请求
 */
public class AuthInterceptor implements Interceptor {
    private static final String TAG = "AuthInterceptor";

    private final Context context;
    private final Object reloginLock = new Object();
    private AtomicBoolean isRelogining = new AtomicBoolean(false);
    private CountDownLatch reloginLatch = null;
    private AtomicReference<String> newToken = new AtomicReference<>(null);

    public AuthInterceptor(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        if (response.code() == 401) {
            Log.w(TAG, "收到 401 响应，尝试重新登录: " + request.url());
            response.close();

            String token = doRelogin();
            if (token != null) {
                Request newRequest = request.newBuilder()
                        .header("accesstoken", token)
                        .build();
                return chain.proceed(newRequest);
            } else {
                Log.e(TAG, "重新登录失败");
                return response;
            }
        }

        return response;
    }

    private String doRelogin() {
        synchronized (reloginLock) {
            if (isRelogining.get()) {
                Log.d(TAG, "等待其他线程完成重新登录...");
                CountDownLatch latch = reloginLatch;
                if (latch != null) {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return newToken.get();
            }

            isRelogining.set(true);
            reloginLatch = new CountDownLatch(1);
            newToken.set(null);

            try {
                SharedPreferences prefs = context.getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
                String nasUrl = prefs.getString("saved_url", "");
                String user = prefs.getString("saved_user", "");
                String pass = prefs.getString("saved_pass", "");

                if (nasUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    Log.e(TAG, "没有保存的登录凭据，无法重新登录");
                    return null;
                }

                Log.d(TAG, "开始重新登录...");
                final String[] resultToken = {null};
                final String[] resultSecret = {null};
                final CountDownLatch loginLatch = new CountDownLatch(1);

                FnWebSocketClient wsClient = new FnWebSocketClient();
                wsClient.startLogin(nasUrl, user, pass, new FnWebSocketClient.LoginCallback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        try {
                            resultToken[0] = response.getString("token");
                            resultSecret[0] = response.getString("secret");
                            Log.d(TAG, "重新登录成功");
                        } catch (Exception e) {
                            Log.e(TAG, "解析登录响应失败", e);
                        }
                        loginLatch.countDown();
                    }

                    @Override
                    public void onError(String msg) {
                        Log.e(TAG, "重新登录失败: " + msg);
                        loginLatch.countDown();
                    }
                });

                try {
                    loginLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                if (resultToken[0] != null) {
                    prefs.edit()
                            .putString("api_token", resultToken[0])
                            .putString("secret", resultSecret[0])
                            .apply();
                    newToken.set(resultToken[0]);
                    return resultToken[0];
                }

                return null;
            } finally {
                isRelogining.set(false);
                CountDownLatch latch = reloginLatch;
                reloginLatch = null;
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    }
}
