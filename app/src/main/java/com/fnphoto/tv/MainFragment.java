package com.fnphoto.tv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.widget.*;

import com.fnphoto.tv.api.FnAuthUtils;
import com.fnphoto.tv.api.FnHttpApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainFragment extends BrowseSupportFragment {
    private static final String TAG = "MainFragment";
    private static final int PREVIEW_LOAD_DELAY = 300; // 延迟加载时间
    private static final int VISIBLE_RANGE_BUFFER = 40; // 可视范围前后的缓冲数量

    private FnHttpApi api;
    private String token;
    private String baseUrl;
    private ArrayObjectAdapter mRowsAdapter;
    private CardPresenter mCardPresenter;
    private List<FnHttpApi.TimelineItem> timelineItems;
    private boolean isPhotoListView = false;
    private List<MediaItem> currentMediaList;
    
    // 懒加载相关
    private List<MediaItem> allDateItems = new ArrayList<>();
    private List<FnHttpApi.TimelineItem> allTimelineItems = new ArrayList<>();
    private Set<Integer> loadedIndexes = new HashSet<>();
    private Handler lazyLoadHandler = new Handler(Looper.getMainLooper());
    private Handler positionHandler = new Handler(Looper.getMainLooper()); // 专门用于位置恢复
    private int lastVisibleIndex = -1;
    
    // 预览缩略图缓存（按日期字符串缓存，避免返回时重新加载）
    private Map<String, List<String>> previewThumbnailCache = new HashMap<>();

    // 保存滚动位置
    private int savedTimelinePosition = -1;  // 保存时间线的选中位置
    private int savedPhotoListPosition = -1; // 保存照片列表的选中位置

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getArguments() != null) {
            baseUrl = getArguments().getString("nas_url", "");
            token = getArguments().getString("api_token", "");
        }

        setupUI();
        
        if (baseUrl != null && !baseUrl.isEmpty()) {
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl + "/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
            api = retrofit.create(FnHttpApi.class);
        }

        setupEventListeners();
    }

    private void setupUI() {
        setTitle("飞牛相册");
        setHeadersState(BrowseSupportFragment.HEADERS_DISABLED);
        setBrandColor(getResources().getColor(android.R.color.black));
        setSearchAffordanceColor(getResources().getColor(android.R.color.white));
        
        mCardPresenter = new CardPresenter(baseUrl);
        mRowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(mRowsAdapter);
    }

    private void setupEventListeners() {
        setOnItemViewClickedListener(new OnItemViewClickedListener() {
            @Override
            public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                      RowPresenter.ViewHolder rowViewHolder, Row row) {
                if (item instanceof MediaItem) {
                    MediaItem mediaItem = (MediaItem) item;
                    
                    if ("date".equals(mediaItem.getType())) {
                        loadPhotosByDate(mediaItem.getDateStr(), mediaItem.getPhotoCount());
                    } else if ("folder".equals(mediaItem.getType())) {
                        openFolderBrowse(mediaItem);
                    } else if ("album".equals(mediaItem.getType())) {
                        loadPhotosByAlbum(mediaItem.getId(), mediaItem.getTitle());
                    } else if ("video".equals(mediaItem.getType()) || "photo".equals(mediaItem.getType())) {
                        openMediaDetail(mediaItem);
                    }
                }
            }
        });

        // 监听选中项变化，实现懒加载
        setOnItemViewSelectedListener(new OnItemViewSelectedListener() {
            @Override
            public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                       RowPresenter.ViewHolder rowViewHolder, Row row) {
                if (item instanceof MediaItem && ((MediaItem) item).getType().equals("date")) {
                    // 找到选中项的索引
                    int selectedIndex = allDateItems.indexOf(item);
                    if (selectedIndex >= 0) {
                        scheduleLazyLoad(selectedIndex);
                    }
                }
            }
        });
    }
    
    private void scheduleLazyLoad(int centerIndex) {
        lastVisibleIndex = centerIndex;
        
        // 取消之前的延迟任务
        lazyLoadHandler.removeCallbacksAndMessages(null);
        
        // 延迟加载，避免快速滚动时频繁加载
        lazyLoadHandler.postDelayed(() -> {
            if (centerIndex == lastVisibleIndex) {
                // 用户停止滚动，加载可视范围内的预览
                loadVisiblePreviews(centerIndex);
            }
        }, PREVIEW_LOAD_DELAY);
    }
    
    private void loadVisiblePreviews(int centerIndex) {
        if (allDateItems.isEmpty() || allTimelineItems.isEmpty()) return;
        
        int start = Math.max(0, centerIndex - VISIBLE_RANGE_BUFFER);
        int end = Math.min(allDateItems.size(), centerIndex + VISIBLE_RANGE_BUFFER + 1);
        
        Log.d(TAG, "Loading previews for visible range: " + start + " to " + end);
        
        for (int i = start; i < end; i++) {
            if (!loadedIndexes.contains(i)) {
                loadedIndexes.add(i);
                final int index = i;
                final MediaItem mediaItem = allDateItems.get(i);
                final FnHttpApi.TimelineItem timelineItem = allTimelineItems.get(i);
                
                if (timelineItem.itemCount > 0) {
                    // 如果已有缓存的预览缩略图，直接通知更新，无需重新请求
                    if (mediaItem.getPreviewThumbUrls() != null && !mediaItem.getPreviewThumbUrls().isEmpty()) {
                        Log.d(TAG, "Using cached preview for " + mediaItem.getDateStr());
                        notifyItemChanged(mediaItem);
                    } else {
                        loadDatePreviewThumbnails(mediaItem, timelineItem, () -> {
                            notifyItemChanged(mediaItem);
                        });
                    }
                }
            }
        }
    }
    
    private void notifyItemChanged(MediaItem item) {
        // 遍历所有行，找到并更新对应的项
        for (int i = 0; i < mRowsAdapter.size(); i++) {
            Object row = mRowsAdapter.get(i);
            if (row instanceof ListRow) {
                ArrayObjectAdapter rowAdapter = (ArrayObjectAdapter) ((ListRow) row).getAdapter();
                int itemIndex = rowAdapter.indexOf(item);
                if (itemIndex >= 0) {
                    rowAdapter.notifyItemRangeChanged(itemIndex, 1);
                    break;
                }
            }
        }
    }

    public boolean onBackPressed() {
        if (isPhotoListView) {
            if (timelineItems != null) {
                Log.d(TAG, "Returning to timeline");
                savePhotoListPosition();
                displayTimeline(timelineItems);
            } else if (savedAlbumList != null) {
                Log.d(TAG, "Returning to album list");
                displayAlbums(savedAlbumList);
            } else {
                return false;
            }
            return true;
        }
        return false;
    }
    
    private void saveTimelinePosition() {
        try {
            // 找到当前选中的日期项在allDateItems中的索引
            int selectedRow = getSelectedPosition();
            if (selectedRow >= 0 && selectedRow < mRowsAdapter.size()) {
                Object row = mRowsAdapter.get(selectedRow);
                if (row instanceof ListRow) {
                    // 获取当前行的选中位置
                    // 注意：Leanback不直接提供行内选中位置，我们使用lastVisibleIndex
                    savedTimelinePosition = lastVisibleIndex;
                    Log.d(TAG, "Saved timeline position: " + savedTimelinePosition);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving timeline position", e);
        }
    }
    
    private void savePhotoListPosition() {
        try {
            savedPhotoListPosition = getSelectedPosition();
            Log.d(TAG, "Saved photo list position: " + savedPhotoListPosition);
        } catch (Exception e) {
            Log.e(TAG, "Error saving photo list position", e);
        }
    }
    
    private void restoreTimelinePosition() {
        Log.d(TAG, "restoreTimelinePosition called, saved position: " + savedTimelinePosition + ", total items: " + allDateItems.size());
        if (savedTimelinePosition >= 0 && savedTimelinePosition < allDateItems.size()) {
            // 使用独立的 Handler，避免被 lazyLoadHandler 清空
            positionHandler.postDelayed(() -> {
                try {
                    // 找到该日期项所在的行
                    MediaItem targetItem = allDateItems.get(savedTimelinePosition);
                    Log.d(TAG, "Looking for item: " + targetItem.getId() + " at position " + savedTimelinePosition);
                    
                    for (int rowIdx = 0; rowIdx < mRowsAdapter.size(); rowIdx++) {
                        Object row = mRowsAdapter.get(rowIdx);
                        if (row instanceof ListRow) {
                            ArrayObjectAdapter rowAdapter = (ArrayObjectAdapter) ((ListRow) row).getAdapter();
                            int itemIdx = rowAdapter.indexOf(targetItem);
                            Log.d(TAG, "Row " + rowIdx + ": item index = " + itemIdx);
                            
                            if (itemIdx >= 0) {
                                // 找到行，选中它
                                setSelectedPosition(rowIdx);
                                Log.d(TAG, "Restored timeline position - row: " + rowIdx + ", item index: " + savedTimelinePosition);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error restoring timeline position", e);
                }
            }, 800); // 延迟800ms等待视图准备好
        } else {
            Log.w(TAG, "Invalid saved timeline position: " + savedTimelinePosition);
        }
    }

    public void loadTimeline() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        String authx = FnAuthUtils.generateAuthX("/p/api/v1/gallery/timeline", "GET", null);

        api.getTimeline(token, authx, null).enqueue(new Callback<FnHttpApi.TimelineResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.TimelineResponse> call, 
                                   Response<FnHttpApi.TimelineResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.TimelineResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayTimeline(result.data.list);
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.TimelineResponse> call, Throwable t) {
                Log.e(TAG, "加载时间线失败", t);
            }
        });
    }

    private void displayTimeline(List<FnHttpApi.TimelineItem> items) {
        timelineItems = items;
        isPhotoListView = false;
        allDateItems.clear();
        allTimelineItems.clear();
        loadedIndexes.clear();
        
        mRowsAdapter.clear();
        
        String currentYearMonth = "";
        ArrayObjectAdapter currentRowAdapter = null;
        
        for (FnHttpApi.TimelineItem item : items) {
            String yearMonth = item.year + "年" + item.month + "月";
            String dateStr = item.year + "-" + String.format("%02d", item.month) + "-" + String.format("%02d", item.day);
            
            if (!yearMonth.equals(currentYearMonth)) {
                currentYearMonth = yearMonth;
                HeaderItem header = new HeaderItem(yearMonth);
                currentRowAdapter = new ArrayObjectAdapter(mCardPresenter);
                mRowsAdapter.add(new ListRow(header, currentRowAdapter));
            }
            
            MediaItem mediaItem = new MediaItem(
                dateStr,
                item.day + "日 (" + item.itemCount + "张)",
                item.itemCount
            );
            // 如果缓存中有预览缩略图，直接复用
            if (previewThumbnailCache.containsKey(dateStr)) {
                mediaItem.setPreviewThumbUrls(previewThumbnailCache.get(dateStr));
            }
            currentRowAdapter.add(mediaItem);
            allDateItems.add(mediaItem);
            allTimelineItems.add(item);
        }
        
        // 初始加载前几个可见项的预览
        if (!allDateItems.isEmpty()) {
            lazyLoadHandler.postDelayed(() -> loadVisiblePreviews(0), 500);
        }
        
        // 恢复之前保存的位置
        restoreTimelinePosition();
    }

    public void loadFolders() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        Boolean desc = false;
        Integer orderBy = 2;
        StringBuilder paramsBuilder = new StringBuilder();
        paramsBuilder.append("desc=").append(desc);
        paramsBuilder.append("&orderBy=").append(orderBy);

        String params = paramsBuilder.toString();

        String authx = FnAuthUtils.generateAuthX("/p/api/v1/photo/folder/list", "GET", params);

        api.getManagedFolders(token, authx, desc, orderBy).enqueue(new Callback<FnHttpApi.FolderListResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.FolderListResponse> call,
                                   Response<FnHttpApi.FolderListResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.FolderListResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayFolders(result.data.list);
                    } else {
                        Log.e(TAG, "加载文件夹失败: code=" + result.code + ", msg=" + result.msg);
                    }
                } else {
                    Log.e(TAG, "加载文件夹失败: HTTP " + response.code());
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.FolderListResponse> call, Throwable t) {
                Log.e(TAG, "加载文件夹失败", t);
            }
        });
    }

    private void displayFolders(List<FnHttpApi.FolderItem> folders) {
        isPhotoListView = false;
        timelineItems = null;
        mRowsAdapter.clear();

        int itemsPerRow = 6;
        int totalRows = (int) Math.ceil((double) folders.size() / itemsPerRow);

        for (int row = 0; row < totalRows; row++) {
            int start = row * itemsPerRow;
            int end = Math.min(start + itemsPerRow, folders.size());

            HeaderItem header = row == 0 ? new HeaderItem("文件夹 (" + folders.size() + ")") : null;
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);

            for (int i = start; i < end; i++) {
                FnHttpApi.FolderItem folder = folders.get(i);
                String folderName = folder.getFolderName();
                int totalCount = folder.getTotalCount();

                MediaItem item = new MediaItem(
                    String.valueOf(folder.folderId),
                    folderName,
                    "folder",
                    null,
                    folder.folderPath
                );

                if (totalCount > 0) {
                    StringBuilder countInfo = new StringBuilder();
                    if (folder.photoCount > 0) {
                        countInfo.append(folder.photoCount).append("张照片");
                    }
                    if (folder.videoCount > 0) {
                        if (countInfo.length() > 0) {
                            countInfo.append(" · ");
                        }
                        countInfo.append(folder.videoCount).append("个视频");
                    }
                    item.setDateStr(countInfo.toString());
                }

                rowAdapter.add(item);
            }

            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }
    }

    public void loadAlbums() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        String params = "sort_direction=desc&sort_by=date_time&offset=0&limit=1000";
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/album/list", "GET", params);

        api.getAlbums(token, authx, "desc", "date_time", 0, 1000).enqueue(new Callback<FnHttpApi.AlbumListResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.AlbumListResponse> call,
                                   Response<FnHttpApi.AlbumListResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.AlbumListResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayAlbums(result.data.list);
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.AlbumListResponse> call, Throwable t) {
                Log.e(TAG, "加载相册失败", t);
            }
        });
    }

    private void displayAlbums(List<FnHttpApi.NewAlbum> albums) {
        savedAlbumList = new ArrayList<>(albums);
        isPhotoListView = false;
        timelineItems = null;
        mRowsAdapter.clear();
        
        int itemsPerRow = 6;
        int totalRows = (int) Math.ceil((double) albums.size() / itemsPerRow);

        for (int row = 0; row < totalRows; row++) {
            int start = row * itemsPerRow;
            int end = Math.min(start + itemsPerRow, albums.size());

            HeaderItem header = row == 0 ? new HeaderItem("相册 (" + albums.size() + ")") : null;
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);

            for (int i = start; i < end; i++) {
                FnHttpApi.NewAlbum album = albums.get(i);
                String posterUrl = album.posterUrl != null ? baseUrl + album.posterUrl : null;
                MediaItem item = new MediaItem(
                    String.valueOf(album.albumId),
                    album.albumName,
                    "album",
                    posterUrl,
                    posterUrl
                );
                int totalCount = album.photoCount + album.videoCount;
                if (totalCount > 0) {
                    StringBuilder countInfo = new StringBuilder();
                    if (album.photoCount > 0) {
                        countInfo.append(album.photoCount).append("张照片");
                    }
                    if (album.videoCount > 0) {
                        if (countInfo.length() > 0) {
                            countInfo.append(" · ");
                        }
                        countInfo.append(album.videoCount).append("个视频");
                    }
                    item.setDateStr(countInfo.toString());
                }
                rowAdapter.add(item);
            }

            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }
    }

    private void loadDatePreviewThumbnails(final MediaItem mediaItem, 
                                           final FnHttpApi.TimelineItem timelineItem,
                                           final Runnable onComplete) {
        if (api == null || token == null || token.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        
        String dateStr = mediaItem.getDateStr();
        String dateTime = dateStr.replace("-", ":");
        String startTime = dateTime + " 00:00:00";
        String endTime = dateTime + " 23:59:59";
        int limit = Math.min(timelineItem.itemCount,4);
        int offset = 0;
        String mode = "index";
        
        StringBuilder paramsBuilder = new StringBuilder();
        paramsBuilder.append("end_time=").append(endTime);
        paramsBuilder.append("&limit=").append(limit);
        paramsBuilder.append("&mode=").append(mode);
        paramsBuilder.append("&offset=").append(offset);
        paramsBuilder.append("&start_time=").append(startTime);
        
        String params = paramsBuilder.toString();
        String path = "/p/api/v1/gallery/getList";
        
        String authx = FnAuthUtils.generateAuthX(path, "GET", params);
        
        api.getPhotosByTimeRange(token, authx, startTime, endTime, limit, offset, mode)
            .enqueue(new Callback<FnHttpApi.GalleryListResponse>() {
                @Override
                public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                                       Response<FnHttpApi.GalleryListResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        FnHttpApi.GalleryListResponse result = response.body();
                        if (result.code == 0 && result.data != null && result.data.list != null) {
                            List<String> thumbUrls = new ArrayList<>();
                            for (FnHttpApi.GalleryPhoto photo : result.data.list) {
                                if (photo.additional != null && photo.additional.thumbnail != null) {
                                    String thumbUrl = photo.additional.thumbnail.mUrl;
                                    if (thumbUrl == null) {
                                        thumbUrl = photo.additional.thumbnail.sUrl;
                                    }
                                    if (thumbUrl != null) {
                                        if (!thumbUrl.startsWith("http") && baseUrl != null) {
                                            thumbUrl = baseUrl + thumbUrl;
                                        }
                                        thumbUrls.add(thumbUrl);
                                    }
                                }
                            }
                            mediaItem.setPreviewThumbUrls(thumbUrls);
                            previewThumbnailCache.put(dateStr, thumbUrls);
                        }
                    }
                    if (onComplete != null) onComplete.run();
                }

                @Override
                public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable t) {
                    Log.e(TAG, "加载预览缩略图失败: " + dateStr, t);
                    if (onComplete != null) onComplete.run();
                }
            });
    }

    public void loadPhotosByDate(String dateStr, int itemCount) {
        // 保存时间线的滚动位置
        saveTimelinePosition();
        
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        Log.d(TAG, "Loading photos for date: " + dateStr);

        String dateTime = dateStr.replace("-", ":");
        String startTime = dateTime + " 00:00:00";
        String endTime = dateTime + " 23:59:59";
        int limit = itemCount;
        int offset = 0;
        String mode = "index";

        StringBuilder paramsBuilder = new StringBuilder();
        paramsBuilder.append("end_time=").append(endTime);
        paramsBuilder.append("&limit=").append(limit);
        paramsBuilder.append("&mode=").append(mode);
        paramsBuilder.append("&offset=").append(offset);
        paramsBuilder.append("&start_time=").append(startTime);
        
        String params = paramsBuilder.toString();
        String path = "/p/api/v1/gallery/getList";
        
        Log.d(TAG, "Path: " + path);
        Log.d(TAG, "Params: " + params);

        String authx = FnAuthUtils.generateAuthX(path, "GET", params);

        api.getPhotosByTimeRange(token, authx, startTime, endTime, limit, offset, mode)
            .enqueue(new Callback<FnHttpApi.GalleryListResponse>() {
                @Override
                public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                                       Response<FnHttpApi.GalleryListResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        FnHttpApi.GalleryListResponse result = response.body();
                        if (result.code == 0 && result.data != null && result.data.list != null) {
                            displayPhotosByDate(dateStr, result.data.list);
                        }
                    }
                }

                @Override
                public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable t) {
                    Log.e(TAG, "加载照片列表失败", t);
                }
            });
    }

    private void displayPhotosByDate(String dateStr, List<FnHttpApi.GalleryPhoto> photos) {
        isPhotoListView = true;
        mRowsAdapter.clear();

        HeaderItem header = new HeaderItem(dateStr + " (" + photos.size() + "张)");
        ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(mCardPresenter);

        currentMediaList = new ArrayList<>();

        for (FnHttpApi.GalleryPhoto photo : photos) {
            String thumbUrl = null;
            String originalUrl = null;

            if (photo.additional != null && photo.additional.thumbnail != null) {
                FnHttpApi.GalleryThumbnail thumbnail = photo.additional.thumbnail;
                
                thumbUrl = thumbnail.mUrl != null ? baseUrl + thumbnail.mUrl : (thumbnail.sUrl != null ? baseUrl + thumbnail.sUrl : null);
                
                originalUrl = thumbnail.mUrl != null ? baseUrl + thumbnail.mUrl : null;
            }

            MediaItem item = new MediaItem(
                String.valueOf(photo.id),
                photo.fileName,
                photo.category,
                thumbUrl,
                originalUrl
            );
            currentMediaList.add(item);
            listRowAdapter.add(item);
        }

        mRowsAdapter.add(new ListRow(header, listRowAdapter));
        
        // 恢复照片列表的位置
        if (savedPhotoListPosition >= 0) {
            lazyLoadHandler.postDelayed(() -> {
                try {
                    setSelectedPosition(savedPhotoListPosition);
                    Log.d(TAG, "Restored photo list position: " + savedPhotoListPosition);
                } catch (Exception e) {
                    Log.e(TAG, "Error restoring photo list position", e);
                }
            }, 300);
        }
    }

    public void loadFavorites() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        String params = "is_collect=1";
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/gallery/timeline", "GET", params);

        api.getTimeline(token, authx, 1).enqueue(new Callback<FnHttpApi.TimelineResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.TimelineResponse> call,
                                   Response<FnHttpApi.TimelineResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.TimelineResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayTimeline(result.data.list);
                    } else {
                        showEmptyState("暂无收藏");
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.TimelineResponse> call, Throwable t) {
                Log.e(TAG, "加载收藏失败", t);
                showEmptyState("加载失败");
            }
        });
    }

    public void loadRecent() {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        String authx = FnAuthUtils.generateAuthX("/p/api/v1/explore/recent_timeline", "GET", null);

        api.getRecentTimeline(token, authx).enqueue(new Callback<FnHttpApi.TimelineResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.TimelineResponse> call,
                                   Response<FnHttpApi.TimelineResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.TimelineResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        displayTimeline(result.data.list);
                    } else {
                        showEmptyState("暂无照片");
                    }
                }
            }

            @Override
            public void onFailure(Call<FnHttpApi.TimelineResponse> call, Throwable t) {
                Log.e(TAG, "加载最近照片失败", t);
                showEmptyState("加载失败");
            }
        });
    }

    private void displayPhotoList(String title, List<FnHttpApi.GalleryPhoto> photos) {
        isPhotoListView = true;
        timelineItems = null;
        mRowsAdapter.clear();

        HeaderItem header = new HeaderItem(title + " (" + photos.size() + ")");
        ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(mCardPresenter);

        currentMediaList = new ArrayList<>();

        for (FnHttpApi.GalleryPhoto photo : photos) {
            String thumbUrl = null;
            String originalUrl = null;

                if (photo.additional != null && photo.additional.thumbnail != null) {
                FnHttpApi.GalleryThumbnail thumbnail = photo.additional.thumbnail;
                thumbUrl = thumbnail.mUrl != null ? baseUrl + thumbnail.mUrl : (thumbnail.sUrl != null ? baseUrl + thumbnail.sUrl : null);
                originalUrl = thumbnail.mUrl != null ? baseUrl + thumbnail.mUrl : null;
            }

            MediaItem item = new MediaItem(
                String.valueOf(photo.id),
                photo.fileName,
                photo.category,
                thumbUrl,
                originalUrl
            );
            currentMediaList.add(item);
            listRowAdapter.add(item);
        }

        mRowsAdapter.add(new ListRow(header, listRowAdapter));
    }

    private void showEmptyState(String message) {
        isPhotoListView = true;
        timelineItems = null;
        mRowsAdapter.clear();

        HeaderItem header = new HeaderItem(message);
        ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(mCardPresenter);
        mRowsAdapter.add(new ListRow(header, listRowAdapter));
    }

    private List<FnHttpApi.NewAlbum> savedAlbumList;

    private void loadAlbumPhotosPage(final String albumName, final int albumId,
                                     final int offset, final List<FnHttpApi.GalleryPhoto> allPhotos) {
        String params = "album_id=" + albumId + "&sort_by=date_time&sort_direction=desc&offset=" + offset + "&limit=35";
        String authx = FnAuthUtils.generateAuthX("/p/api/v1/album/photos", "GET", params);

        api.getAlbumPhotos(token, authx, albumId, "date_time", "desc", offset, 35)
            .enqueue(new Callback<FnHttpApi.GalleryListResponse>() {
            @Override
            public void onResponse(Call<FnHttpApi.GalleryListResponse> call,
                                   Response<FnHttpApi.GalleryListResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    FnHttpApi.GalleryListResponse result = response.body();
                    if (result.code == 0 && result.data != null && result.data.list != null) {
                        allPhotos.addAll(result.data.list);
                        if (result.data.list.size() >= 35) {
                            loadAlbumPhotosPage(albumName, albumId, offset + 35, allPhotos);
                            return;
                        }
                    }
                }
                displayAlbumPhotos(albumName, allPhotos);
            }

            @Override
            public void onFailure(Call<FnHttpApi.GalleryListResponse> call, Throwable t) {
                if (!allPhotos.isEmpty()) {
                    displayAlbumPhotos(albumName, allPhotos);
                } else {
                    Log.e(TAG, "加载相册照片失败", t);
                }
            }
        });
    }

    public void loadPhotosByAlbum(String albumId, String albumName) {
        if (api == null || token == null || token.isEmpty()) {
            Log.e(TAG, "API未初始化");
            return;
        }

        saveTimelinePosition();
        loadAlbumPhotosPage(albumName, Integer.parseInt(albumId), 0, new ArrayList<FnHttpApi.GalleryPhoto>());
    }

    private void displayAlbumPhotos(String albumName, List<FnHttpApi.GalleryPhoto> photos) {
        isPhotoListView = true;
        timelineItems = null;
        mRowsAdapter.clear();

        currentMediaList = new ArrayList<>();

        int itemsPerRow = 6;
        int totalRows = (int) Math.ceil((double) photos.size() / itemsPerRow);

        for (int row = 0; row < totalRows; row++) {
            int start = row * itemsPerRow;
            int end = Math.min(start + itemsPerRow, photos.size());

            HeaderItem header = row == 0 ? new HeaderItem("相册: " + albumName + " (" + photos.size() + "张)") : null;
            ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(mCardPresenter);

            for (int i = start; i < end; i++) {
                FnHttpApi.GalleryPhoto photo = photos.get(i);

                String thumbUrl = null;
                String mediaUrl = null;

                if (photo.additional != null && photo.additional.thumbnail != null) {
                    FnHttpApi.GalleryThumbnail thumbnail = photo.additional.thumbnail;
                    thumbUrl = thumbnail.mUrl != null ? baseUrl + thumbnail.mUrl : (thumbnail.sUrl != null ? baseUrl + thumbnail.sUrl : null);
                    mediaUrl = thumbnail.originalUrl != null ? baseUrl + thumbnail.originalUrl : null;
                }

                MediaItem item = new MediaItem(
                    String.valueOf(photo.id),
                    photo.fileName,
                    photo.category,
                    thumbUrl,
                    mediaUrl
                );
                currentMediaList.add(item);
                rowAdapter.add(item);
            }

            mRowsAdapter.add(new ListRow(header, rowAdapter));
        }
    }

    private void openMediaDetail(MediaItem mediaItem) {
        if (currentMediaList == null || currentMediaList.isEmpty()) {
            currentMediaList = new ArrayList<>();
            currentMediaList.add(mediaItem);
        }

        int index = 0;
        for (int i = 0; i < currentMediaList.size(); i++) {
            if (currentMediaList.get(i).getId().equals(mediaItem.getId())) {
                index = i;
                break;
            }
        }

        Intent intent = new Intent(getActivity(), MediaDetailActivity.class);
        intent.putExtra("MEDIA_LIST", new ArrayList<>(currentMediaList));
        intent.putExtra("CURRENT_INDEX", index);
        startActivity(intent);
    }

    private void openFolderBrowse(MediaItem folderItem) {
        // 获取文件夹路径
        String folderPath = folderItem.getMediaUrl(); // 我们之前将路径保存在 mediaUrl 中
        if (folderPath == null || folderPath.isEmpty()) {
            Log.e(TAG, "文件夹路径为空");
            return;
        }

        Intent intent = new Intent(getActivity(), FolderBrowseActivity.class);
        intent.putExtra("FOLDER_PATH", folderPath);
        intent.putExtra("FOLDER_NAME", folderItem.getTitle());
        startActivity(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lazyLoadHandler.removeCallbacksAndMessages(null);
        positionHandler.removeCallbacksAndMessages(null);
    }
}
