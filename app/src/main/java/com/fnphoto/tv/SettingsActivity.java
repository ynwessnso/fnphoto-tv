package com.fnphoto.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.fnphoto.tv.cache.CachedImageLoader;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SettingsActivity extends FragmentActivity {

    private LinearLayout contentLayout;
    private LinearLayout menuOrderLayout;
    private SharedPreferences prefs;
    private List<String> currentMenuOrder;
    private int pendingFocusIndex = -1; // 移动后需要聚焦的按钮索引
    private boolean pendingFocusIsDown = false; // 聚焦上移还是下移按钮

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        prefs = getSharedPreferences("fn_photo_prefs", Context.MODE_PRIVATE);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setBackgroundColor(Color.parseColor("#0a0a0c"));

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText("设置");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(28);
        tvTitle.setPadding(48, 32, 48, 16);
        rootLayout.addView(tvTitle);

        // ScrollView for content
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(48, 8, 48, 48);

        addSectionHeader("连接信息");
        addInfoItem("NAS地址", prefs.getString("nas_url", "未设置"));
        addInfoItem("登录状态", prefs.getString("api_token", "").isEmpty() ? "未登录" : "已登录");

        addSectionHeader("缓存管理");
        addCacheInfoItem();
        addActionItem("清空图片缓存", v -> {
            CachedImageLoader.clearAllCache(this);
            Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show();
            refreshCacheSize();
        });

        addSectionHeader("关于");
        addInfoItem("应用名称", "fnPhoto TV");
        addInfoItem("版本", "1.0.0");
        addInfoItem("开发", "基于飞牛NAS相册系统");

        addSectionHeader("菜单排序（第一项为默认首页）");
        addMenuOrderSection();

        scrollView.addView(contentLayout);
        rootLayout.addView(scrollView);

        setContentView(rootLayout);
    }

    private void addSectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(Color.parseColor("#3b82f6"));
        header.setTextSize(20);
        header.setPadding(0, 32, 0, 12);
        contentLayout.addView(header);
    }

    private void addInfoItem(String label, String value) {
        LinearLayout itemLayout = new LinearLayout(this);
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(0, 12, 0, 12);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor("#9ca3af"));
        tvLabel.setTextSize(18);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(300, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(Color.WHITE);
        tvValue.setTextSize(18);

        itemLayout.addView(tvLabel);
        itemLayout.addView(tvValue);
        contentLayout.addView(itemLayout);
    }

    private TextView tvCacheSize;

    private void addCacheInfoItem() {
        LinearLayout itemLayout = new LinearLayout(this);
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(0, 12, 0, 12);

        TextView tvLabel = new TextView(this);
        tvLabel.setText("缓存大小");
        tvLabel.setTextColor(Color.parseColor("#9ca3af"));
        tvLabel.setTextSize(18);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(300, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvCacheSize = new TextView(this);
        tvCacheSize.setTextColor(Color.WHITE);
        tvCacheSize.setTextSize(18);

        itemLayout.addView(tvLabel);
        itemLayout.addView(tvCacheSize);
        contentLayout.addView(itemLayout);

        refreshCacheSize();
    }

    private void refreshCacheSize() {
        if (tvCacheSize != null) {
            float sizeMB = CachedImageLoader.getCacheSizeMB(this);
            if (sizeMB > 0.1f) {
                tvCacheSize.setText(String.format("%.1f MB", sizeMB));
            } else {
                tvCacheSize.setText("无缓存");
            }
        }
    }

    private void addActionItem(String title, View.OnClickListener listener) {
        TextView action = new TextView(this);
        action.setText(title);
        action.setTextColor(Color.parseColor("#ef4444"));
        action.setTextSize(18);
        action.setPadding(300, 16, 0, 16);
        action.setFocusable(true);
        action.setFocusableInTouchMode(true);
        action.setOnClickListener(listener);
        contentLayout.addView(action);
    }

    private void addMenuOrderSection() {
        // 加载当前菜单顺序
        String json = prefs.getString(MainActivity.PREF_MENU_ORDER, null);
        currentMenuOrder = new ArrayList<>();
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    currentMenuOrder.add(arr.getString(i));
                }
            } catch (JSONException e) {
                currentMenuOrder.addAll(Arrays.asList(MainActivity.DEFAULT_MENU_ORDER));
            }
        } else {
            currentMenuOrder.addAll(Arrays.asList(MainActivity.DEFAULT_MENU_ORDER));
        }

        menuOrderLayout = new LinearLayout(this);
        menuOrderLayout.setOrientation(LinearLayout.VERTICAL);
        menuOrderLayout.setPadding(0, 8, 0, 8);
        refreshMenuOrderList();

        contentLayout.addView(menuOrderLayout);

        // 保存按钮
        TextView saveBtn = new TextView(this);
        saveBtn.setText("💾 保存菜单排序");
        saveBtn.setTextColor(Color.parseColor("#22c55e"));
        saveBtn.setTextSize(18);
        saveBtn.setPadding(0, 24, 0, 16);
        saveBtn.setFocusable(true);
        saveBtn.setFocusableInTouchMode(true);
        saveBtn.setOnClickListener(v -> saveMenuOrder());
        contentLayout.addView(saveBtn);
    }

    private void refreshMenuOrderList() {
        menuOrderLayout.removeAllViews();
        for (int i = 0; i < currentMenuOrder.size(); i++) {
            String action = currentMenuOrder.get(i);
            String title = getMenuTitleByAction(action);
            boolean isFirst = i == 0;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(12, 6, 12, 6);
            row.setGravity(Gravity.CENTER_VERTICAL);

            // 序号 + 标题
            TextView tvIndex = new TextView(this);
            tvIndex.setText((i + 1) + ".");
            tvIndex.setTextColor(isFirst ? Color.parseColor("#22c55e") : Color.parseColor("#9ca3af"));
            tvIndex.setTextSize(16);
            tvIndex.setWidth(60);
            row.addView(tvIndex);

            TextView tvTitle = new TextView(this);
            tvTitle.setText(title + (isFirst ? " (首页)" : ""));
            tvTitle.setTextColor(isFirst ? Color.parseColor("#22c55e") : Color.WHITE);
            tvTitle.setTextSize(16);
            tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvTitle);

            // 上移按钮
            if (i > 0) {
                TextView btnUp = new TextView(this);
                btnUp.setText("▲");
                btnUp.setTextColor(Color.parseColor("#3b82f6"));
                btnUp.setTextSize(18);
                btnUp.setPadding(24, 8, 24, 8);
                btnUp.setFocusable(true);
                btnUp.setFocusableInTouchMode(true);
                btnUp.setBackgroundColor(Color.TRANSPARENT);
                final int idx = i;
                btnUp.setOnFocusChangeListener((v, hasFocus) -> {
                    row.setBackgroundColor(hasFocus ? Color.parseColor("#1a3b82f6") : Color.TRANSPARENT);
                });
                btnUp.setOnClickListener(v -> moveMenuItem(idx, idx - 1, false));
                row.addView(btnUp);
            } else {
                TextView spacer = new TextView(this);
                spacer.setText("   ");
                spacer.setPadding(24, 8, 24, 8);
                row.addView(spacer);
            }

            // 下移按钮
            if (i < currentMenuOrder.size() - 1) {
                TextView btnDown = new TextView(this);
                btnDown.setText("▼");
                btnDown.setTextColor(Color.parseColor("#3b82f6"));
                btnDown.setTextSize(18);
                btnDown.setPadding(24, 8, 24, 8);
                btnDown.setFocusable(true);
                btnDown.setFocusableInTouchMode(true);
                btnDown.setBackgroundColor(Color.TRANSPARENT);
                final int idx = i;
                btnDown.setOnFocusChangeListener((v, hasFocus) -> {
                    row.setBackgroundColor(hasFocus ? Color.parseColor("#1a3b82f6") : Color.TRANSPARENT);
                });
                btnDown.setOnClickListener(v -> moveMenuItem(idx, idx + 1, true));
                row.addView(btnDown);
            }

            menuOrderLayout.addView(row);
        }

        // 恢复焦点
        if (pendingFocusIndex >= 0) {
            restoreFocus(pendingFocusIndex, pendingFocusIsDown);
            pendingFocusIndex = -1;
        }
    }

    private void moveMenuItem(int from, int to, boolean isDown) {
        String item = currentMenuOrder.remove(from);
        currentMenuOrder.add(to, item);
        // 移动后，焦点应落在同一位置（新索引 = to）
        pendingFocusIndex = to;
        pendingFocusIsDown = isDown;
        refreshMenuOrderList();
        Toast.makeText(this, "已移动，记得保存", Toast.LENGTH_SHORT).show();
    }

    private void restoreFocus(int itemIndex, boolean isDown) {
        int childCount = menuOrderLayout.getChildCount();
        if (itemIndex >= 0 && itemIndex < childCount) {
            LinearLayout row = (LinearLayout) menuOrderLayout.getChildAt(itemIndex);
            // 按钮在 row 中的位置：序号(0) + 标题(1) + [上移] + [下移]
            // 如果有上移按钮，它在 index 2；下移在 index 3（或 2 如果没有上移）
            int buttonIndex = isDown ? row.getChildCount() - 1 : Math.min(2, row.getChildCount() - 1);
            View target = row.getChildAt(buttonIndex);
            if (target != null) {
                target.requestFocus();
            }
        }
    }

    private void saveMenuOrder() {
        JSONArray arr = new JSONArray();
        for (String action : currentMenuOrder) {
            arr.put(action);
        }
        prefs.edit().putString(MainActivity.PREF_MENU_ORDER, arr.toString()).apply();
        Toast.makeText(this, "菜单排序已保存，重启后生效", Toast.LENGTH_SHORT).show();
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
            default: return action;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
