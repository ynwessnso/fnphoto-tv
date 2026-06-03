package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.fnphoto.tv.api.FnAuthUtils;
import com.fnphoto.tv.api.FnHttpApi;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends FragmentActivity {
    private static final String TAG = "MainActivity";
    private DrawerLayout drawerLayout;
    private VerticalGridView menuGrid;
    private MenuAdapter menuAdapter;
    private FnHttpApi api;
    private String token;
    private String secret;
    private String baseUrl;

    // 默认菜单顺序
    public static final String[] DEFAULT_MENU_ORDER = {
        "shared_to_me", "gallery", "folders", "favorites", "recent", "search",
        "albums", "shared_by_me", "people", "places", "tags", "smart", "media_types",
        "settings", "logout"
    };
    public static final String PREF_MENU_ORDER = "menu_order";
    
    // 双击返回键退出相关
    private boolean isBackPressedOnce = false;
    private final Handler backHandler = new Handler(Looper.getMainLooper());
    private static final int BACK_PRESS_INTERVAL = 2000; // 2秒内双击
    
    // 菜单项数据类
    public static class MenuItem {
        private String title;
        private String action;

        public MenuItem(String title, String action) {
            this.title = title;
            this.action = action;
        }

        public String getTitle() { return title; }
        public String getAction() { return action; }
    }
    
    // RecyclerView Adapter for menu
    public class MenuAdapter extends RecyclerView.Adapter<MenuAdapter.ViewHolder> {
        private List<MenuItem> items = new ArrayList<>();
        private int selectedPosition = -1;
        
        public void add(MenuItem item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }
        
        public MenuItem get(int position) {
            return items.get(position);
        }
        
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_menu, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            MenuItem item = items.get(position);
            holder.textView.setText(item.title);
            
            // 设置焦点监听
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    selectedPosition = position;
                    v.setBackgroundResource(R.drawable.menu_item_focused);
                } else {
                    v.setBackgroundResource(0);
                }
            });
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            
            ViewHolder(View itemView) {
                super(itemView);
                textView = itemView.findViewById(R.id.menu_text);
                
                itemView.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        handleMenuSelection(items.get(pos));
                    }
                });
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置全屏模式，隐藏状态栏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("nas_url", "");
        token = prefs.getString("api_token", "");
        secret = prefs.getString("secret", "");

        // 初始化侧滑菜单
        setupSideMenu();

        // 添加 MainFragment
        setupMainFragment();

        // 初始化 API 并获取相册应用版本
        initApiAndGetVersion();
    }

    private void setupSideMenu() {
        drawerLayout = findViewById(R.id.drawer_layout);
        menuGrid = findViewById(R.id.menu_grid);

        // 设置菜单适配器
        menuAdapter = new MenuAdapter();

        // 从 SharedPreferences 加载菜单顺序
        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String[] menuOrder = loadMenuOrder(prefs);

        for (String action : menuOrder) {
            String title = getMenuTitleByAction(action);
            if (title != null) {
                menuAdapter.add(new MenuItem(title, action));
            }
        }

        menuGrid.setAdapter(menuAdapter);
        menuGrid.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_HIGH_EDGE);
        menuGrid.setWindowAlignmentOffset(48);
        menuGrid.setWindowAlignmentOffsetPercent(VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);

        // 禁用滑动打开菜单，只允许通过按键打开
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END);
    }

    private String[] loadMenuOrder(SharedPreferences prefs) {
        String json = prefs.getString(PREF_MENU_ORDER, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                String[] order = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    order[i] = arr.getString(i);
                }
                return order;
            } catch (JSONException e) {
                Log.w(TAG, "菜单顺序解析失败，使用默认顺序", e);
            }
        }
        return DEFAULT_MENU_ORDER;
    }

    private String getMenuTitleByAction(String action) {
        switch (action) {
            case "shared_to_me": return "共享给我";
            case "gallery": return "图库";
            case "folders": return "文件夹";
            case "favorites": return "收藏";
            case "recent": return "最近添加";
            case "search": return "🔍 搜索";
            case "albums": return "相册";
            case "shared_by_me": return "我共享的";
            case "people": return "人物";
            case "places": return "地点";
            case "tags": return "标签";
            case "smart": return "智能分类";
            case "media_types": return "媒体类型";
            case "settings": return "设置";
            case "logout": return "退出登录";
            default: return null;
        }
    }

    private void setupMainFragment() {
        MainFragment fragment = new MainFragment();
        Bundle args = new Bundle();
        args.putString("nas_url", baseUrl);
        args.putString("api_token", token);
        args.putString("secret", secret);
        fragment.setArguments(args);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_content_container, fragment)
                .commit();
    }

    private void handleMenuSelection(MenuItem item) {
        Log.d(TAG, "Menu selected: " + item.getAction());
        
        switch (item.getAction()) {
            case "gallery":
                loadTimelinePhotos();
                break;
            case "folders":
                loadFolders();
                break;
            case "albums":
                loadAlbums();
                break;
            case "shared":
                loadSharedAlbums();
                break;
            case "shared_to_me":
                loadSharedAlbums();
                break;
            case "shared_by_me":
                loadSharedByMeAlbums();
                break;
            case "favorites":
                loadFavorites();
                break;
            case "recent":
                loadRecent();
                break;
            case "search":
                openSearch();
                return;
            case "settings":
                openSettings();
                return;
            case "logout":
                logout();
                return;
            case "map":
            case "places":
            case "people":
            case "tags":
            case "smart":
            case "media_types":
                Toast.makeText(this, "功能开发中: " + item.getTitle(), Toast.LENGTH_SHORT).show();
                drawerLayout.closeDrawer(GravityCompat.END);
                return;
        }
        
        drawerLayout.closeDrawer(GravityCompat.END);
    }
    
    private void openSettings() {
        drawerLayout.closeDrawer(GravityCompat.END);
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void showAlbumMenuDialog(MainFragment fragment) {
        final String[] options = {"📅 月份跳转", "▶ 幻灯片播放"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("功能菜单")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        fragment.showMonthPickerDialog();
                    } else {
                        showSlideshowIntervalDialog(fragment);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSlideshowIntervalDialog(MainFragment fragment) {
        final String[] options = {"2秒", "3秒", "5秒", "10秒", "30秒"};
        final int[] intervals = {2, 3, 5, 10, 30};

        new android.app.AlertDialog.Builder(this)
                .setTitle("幻灯片播放间隔")
                .setItems(options, (dialog, which) -> {
                    fragment.startSlideshow(intervals[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void openSearch() {
        drawerLayout.closeDrawer(GravityCompat.END);
        Intent intent = new Intent(this, SearchActivity.class);
        startActivity(intent);
    }
    
    private void logout() {
        // 清除所有登录信息
        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        prefs.edit()
            .remove("nas_url")
            .remove("api_token")
            .remove("secret")
            .remove("backId")
            .remove("has_credentials")
            .remove("saved_url")
            .remove("saved_user")
            .remove("saved_pass")
            .apply();
        
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
        
        // 跳转到登录界面
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // 在相册时间线视图下，MENU键显示功能菜单
            MainFragment fragment = (MainFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.main_content_container);
            if (fragment != null && fragment.isInAlbumTimelineView()) {
                showAlbumMenuDialog(fragment);
                return true;
            }
            // 菜单键切换侧滑菜单
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END);
            } else {
                drawerLayout.openDrawer(GravityCompat.END);
                menuGrid.requestFocus();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END);
                return true;
            }
            
            // 检查 MainFragment 是否需要处理返回键（返回到时间线）
            MainFragment fragment = (MainFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.main_content_container);
            if (fragment != null && fragment.onBackPressed()) {
                return true;  // MainFragment 处理了返回键
            }
            
            // 处理双击返回键退出
            if (isBackPressedOnce) {
                // 第二次点击，退出应用
                finish();
                return true;
            }
            
            // 第一次点击
            isBackPressedOnce = true;
            Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
            
            // 2秒后重置状态
            backHandler.postDelayed(() -> isBackPressedOnce = false, BACK_PRESS_INTERVAL);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void initApiAndGetVersion() {
        if (baseUrl.isEmpty() || token.isEmpty() || secret.isEmpty()) {
            Log.e(TAG, "Missing credentials - cannot fetch version");
            return;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new com.fnphoto.tv.api.AuthInterceptor(this))
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        api = retrofit.create(FnHttpApi.class);

        // 生成认证头
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/app/version", "GET", null);
        
        Log.d(TAG, "Fetching album app version from: " + baseUrl);
        
        api.getAppVersion(token, authx).enqueue(new Callback<FnHttpApi.AppVersionResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.AppVersionResponse> call, 
                                  Response<FnHttpApi.AppVersionResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.AppVersionResponse result = response.body();
                    if (result.errno == 0 && result.data != null) {
                        Log.i(TAG, "========================================");
                        Log.i(TAG, "相册应用版本信息:");
                        Log.i(TAG, "  版本号: " + result.data.version);
                        Log.i(TAG, "========================================");
                        // 获取版本成功后，获取相册统计信息和加载默认页面
                        getPhotoStats();
                        loadDefaultPage();
                    } else {
                        Log.w(TAG, "获取版本失败: errno=" + result.errno + ", result=" + result.result);
                    }
                } else {
                    Log.e(TAG, "HTTP错误: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.AppVersionResponse> call, Throwable t) {
                Log.e(TAG, "网络请求失败: " + t.getMessage(), t);
            }
        });
    }

    private void getPhotoStats() {
        if (api == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化或token为空");
            return;
        }

        // 生成认证头
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/user_photo/stat", "GET", null);

        Log.d(TAG, "Fetching photo stats...");

        api.getPhotoStats(token, authx).enqueue(new Callback<FnHttpApi.PhotoStatsResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.PhotoStatsResponse> call,
                                   Response<FnHttpApi.PhotoStatsResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.PhotoStatsResponse result = response.body();
                    if (result.code == 0 && result.data != null) {
                        Log.i(TAG, "========================================");
                        Log.i(TAG, "相册统计信息:");
                        Log.i(TAG, "  照片数量: " + result.data.photoCount);
                        Log.i(TAG, "  视频数量: " + result.data.videoCount);
                        Log.i(TAG, "  是否管理员: " + result.data.isAdmin);
                        Log.i(TAG, "========================================");
                    } else {
                        Log.w(TAG, "获取统计失败: code=" + result.code + ", msg=" + result.msg);
                    }
                } else {
                    Log.e(TAG, "HTTP错误: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.PhotoStatsResponse> call, Throwable t) {
                Log.e(TAG, "获取统计信息失败: " + t.getMessage(), t);
            }
        });
    }

    private void loadTimelinePhotos() {
        // 通过 MainFragment 加载时间线照片
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) {
            fragment.loadTimeline();
        }
    }

    private void loadFolders() {
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) {
            fragment.loadFolders();
        }
    }

    private void loadAlbums() {
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) {
            fragment.loadAlbums();
        }
    }

    private void loadSharedAlbums() {
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) {
            fragment.loadSharedAlbums();
        }
    }

    private void loadSharedByMeAlbums() {
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) {
            fragment.loadSharedByMeAlbums();
        }
    }

    private void loadDefaultPage() {
        SharedPreferences prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);
        String[] menuOrder = loadMenuOrder(prefs);
        if (menuOrder.length > 0) {
            String firstAction = menuOrder[0];
            Log.d(TAG, "加载默认页面: " + firstAction);
            handleMenuAction(firstAction);
        }
    }

    private void handleMenuAction(String action) {
        switch (action) {
            case "gallery": loadTimelinePhotos(); break;
            case "folders": loadFolders(); break;
            case "albums": loadAlbums(); break;
            case "favorites": loadFavorites(); break;
            case "recent": loadRecent(); break;
            case "shared_to_me": loadSharedAlbums(); break;
            case "shared_by_me": loadSharedByMeAlbums(); break;
            default: loadSharedAlbums(); break;
        }
    }

    private void loadFavorites() {
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) {
            fragment.loadFavorites();
        }
    }

    private void loadRecent() {
        MainFragment fragment = (MainFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_content_container);
        if (fragment != null) {
            fragment.loadRecent();
        }
    }
}
