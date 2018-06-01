package com.toolkit.zhihufav;


import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.NestedScrollView;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.SearchView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.toolkit.zhihufav.util.ConnectivityState;
import com.toolkit.zhihufav.util.SQLiteHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.util.List;


public class ContentActivity extends AppCompatActivity {

    private static final String TAG = "ContentActivity";

    private Intent mResult;
    private MenuItem mSearchItem;
    private ViewPager mViewPager;
    private ViewPagerAdapter mAdapter;
    private SharedPreferences mPreferences;
    private ConnectivityState mNetStateReceiver;
    private static WeakReference<Context> sReference;

    private Toolbar mToolBar;
    private AppBarLayout mAppBarLayout;
    private CollapsingToolbarLayout mToolbarLayout;
    private int mToolbarState;  // state有限状态 status偏向连续
    private boolean mToolbarDoingExpand, mToolbarDoingCollapse;

    static final int TOOLBAR_COLLAPSED = -1;
    static final int TOOLBAR_INTERMEDIATE = 0;
    static final int TOOLBAR_EXPANDED = 1;

    private class onPageChangeListener extends ViewPager.SimpleOnPageChangeListener {
        @Override
        public void onPageSelected(int position) {
            // 在setCurrentItemInternal/scrollToItem里，先调用smoothScrollTo/populate/setPrimaryItem
            // 再dispatchOnPageSelected/onPageSelected，但先调用的等动画完成才执行setPrimary，一般会慢
            // 然把页面滑动到位才松开，会立即执行setPrimary！与WebView有关的应放去setUserVisibleHint
            //setToolBarVisible(true);                   // 考虑意外从复制模式换页…但会有好多postDelay
            mResult.putExtra("position", position);      // result直接用的传入Intent
            mAdapter.setQuery(null, "", true);           // 重置查找状态
            setTitle(mAdapter.getPageTitle(position));   // position是滑动终点的索引(CurrentView是起点)
            if (position >= mAdapter.getCount() - mViewPager.getOffscreenPageLimit() - 1) {
                mAdapter.addSomeAsync(5);  // 要预载入的页得在开始预载的前一页就准备好
            }  // 提前页数不能比main的多，不然点到ListView不预载的最后一项时，刚开窗口就addSome，来不及notify抛异常
        }
    }

    public static ContentActivity getReference() {
        return sReference == null ? null : (ContentActivity) sReference.get();
    }


    private WebView getCurrentWebView() {
        // 不能用mViewPager.getChildAt(0)，其索引与实际顺序无关！
        Fragment fragment = mAdapter.getCurrentPage();
        View view = (fragment != null) ? fragment.getView() : null;
        return (view != null) ? (WebView) view.findViewById(R.id.webView_section) : null;
    }

    private int getCurrentWebViewScrollPos() {
        return PageFragment.getScrollPos(getCurrentWebView());
    }

    private int getCurrentWebViewMode() {
        return PageFragment.getContentMode(getCurrentWebView());
    }

    private void reloadCurrentWebView(int target_mode) {
        mAdapter.reloadToMode(getCurrentWebView(), target_mode);
    }

    private boolean goBackCurrentWebView() {
        WebView webView = getCurrentWebView();
        if (webView != null) {  // 用goBack不行，因为除了视频页都不是载入网址而是数据
            if (getCurrentWebViewMode() < PageFragment.MODE_START) {
                PageFragment.setContentMode(getCurrentWebView(), PageFragment.MODE_START);
                setToolBarVisible(true);  // 复制控件用的返回键这里拦不到，只是为了保证能退出复制模式
                return true;
            } else if (getCurrentWebViewMode() > PageFragment.MODE_START) {
                reloadCurrentWebView(PageFragment.MODE_START);
                return true;
            } // else == MODE_START
        }
        return false;  // false表示没有处理，给外层
    }

    private void hideInputMethod() {
        // android.R.id.content gives the root view of current activity
        InputMethodManager manager = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(findViewById(android.R.id.content).getWindowToken(), 0);
    }

    private void onDisplayOptionsMenu(Menu menu) {
        MenuItem menuItem;  // 此函数每次点开都调用，若add则加入的项不会清除
        menuItem = menu.findItem(R.id.content_menu_entry); // 伪装的入口当然不要显示
        menuItem.setVisible(false);
        menuItem = menu.findItem(R.id.content_refresh);    // 刷新是什么时候都能显示
        menuItem.setVisible(true);
        menuItem = menu.findItem(R.id.content_copy_link);  // 链接也什么时候都能复制
        menuItem.setVisible(true);
        menuItem = menu.findItem(R.id.content_search);     // 图片视频模式不给找文本
        menuItem.setVisible(getCurrentWebViewMode() == PageFragment.MODE_START);
        menuItem = menu.findItem(R.id.content_text_size);  // 图片视频模式不给调字体
        menuItem.setVisible(getCurrentWebViewMode() == PageFragment.MODE_START);
        menuItem = menu.findItem(R.id.content_open_with);  // 图片视频模式不给乱打开
        menuItem.setVisible(getCurrentWebViewMode() == PageFragment.MODE_START);
        menuItem = menu.findItem(R.id.content_save);       // 首页视频模式不给乱保存
        menuItem.setVisible(getCurrentWebViewMode() == PageFragment.MODE_IMAGE);
        menuItem = menu.findItem(R.id.content_night_mode); // 夜间模式文本按情况改变
        menuItem.setTitle(PageFragment.isNightTheme() ? R.string.stop_night_mode : R.string.night_mode);
        menuItem.setVisible(true);                         // 而且也是什么时候都显示
    }

    private void showOverflowMenu() {
        //mToolBar.showOverflowMenu();

        // 第一个参数Context要用this而不能是getApplicationContext()，否则theme不对
        final PopupMenu popupMenu = new PopupMenu(this, findViewById(R.id.content_menu_entry));
        popupMenu.inflate(R.menu.menu_content);
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                popupMenu.dismiss();
                if (item.getItemId() == R.id.content_search) {
                    item = mSearchItem;  // PopupMenu的item不带SearchView
                    MenuItemCompat.expandActionView(mSearchItem);
                }
                return onOptionsItemSelected(item);
            }
        });
        onDisplayOptionsMenu(popupMenu.getMenu());
//        popupMenu.show();  // 此菜单右边距不准确，且点击菜单项时没有动画效果(虽然系统的也没有)

        // 为了解决菜单样式问题用自由度更大的PopupWindow再包裹一层…
        final View popupView = getLayoutInflater().inflate(R.layout.popup_overflow, null);
        final PopupWindow popupWindow = new PopupWindow(popupView, 0, 0, true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable());
        popupWindow.setAnimationStyle(R.style.popup_anim_alpha);
        // 动态添加上面可见的菜单项，并绑定点击事件
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                popupWindow.dismiss();
                popupMenu.getMenu().performIdentifierAction(v.getId(), 0);  // 调用上面的菜单项方法
            }
        };
        int count = popupMenu.getMenu().size();
        ViewGroup container = (ViewGroup) ((ViewGroup) popupView).getChildAt(0);
        for (int i = 0; i < count; i++) {
            MenuItem item = popupMenu.getMenu().getItem(i);
            if (item.isVisible()) {
                Button button = (Button) getLayoutInflater().inflate(R.layout.overflow_item, null);
                button.setId(item.getItemId());  // 最后一个参数不是null会自动给加一层LinearLayout
                button.setText(item.getTitle());
                button.setOnClickListener(listener);
                container.addView(button);
            }
        }
        popupWindow.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        popupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);  // find不到时抛异常
        popupWindow.showAtLocation(findViewById(R.id.content_menu_entry), Gravity.TOP | Gravity.END, 0, 0);
    }


    public void closeSearchView() {
        if (mSearchItem != null) {
            MenuItemCompat.collapseActionView(mSearchItem);
        }
    }

    public void copyLink(String url) {
        if (url != null && !url.isEmpty()) {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = ClipData.newPlainText("Link", url);
            clip.setPrimaryClip(data);
            if (url.startsWith("http")) url = url.substring(url.indexOf("://") + 3);
            if (url.startsWith("link.zhihu.com")) url = url.substring(url.indexOf("%3A//") + 5);
            Toast.makeText(this, getString(R.string.link_copied, url), Toast.LENGTH_SHORT).show();
        }
    }

    public void toggleNightTheme() {
        boolean night = PageFragment.isNightTheme();

        if (night) setTheme(R.style.AppTheme_Night);
        else setTheme(R.style.AppTheme_NoActionBar);
        int toolbar_text_color_id = night ? android.R.attr.textColorPrimary :
                                            android.R.attr.textColorPrimaryInverse;
        try {
            int color;
            Resources.Theme theme = getTheme();
            Resources resources = getResources();
            TypedValue typedValue = new TypedValue();
            theme.resolveAttribute(R.attr.colorPrimary, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            mToolbarLayout.setContentScrimColor(color);
            mToolbarLayout.setBackgroundColor(color);
            theme.resolveAttribute(toolbar_text_color_id, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            ((TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout)).setTextColor(color);
            //mToolbarLayout.setCollapsedTitleTextColor(color);  // 夜间重开又回到纯白了
            theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true);  // 只改这个状态栏不变
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            mToolbarLayout.setStatusBarScrimColor(color);


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {  // 4.4只能设为透明
                theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true);
                getWindow().setStatusBarColor(resources.getColor(typedValue.resourceId));
//                ObjectAnimator status_anim = ObjectAnimator.ofArgb(getWindow(), "statusBarColor",
//                        getWindow().getStatusBarColor(), resources.getColor(typedValue.resourceId));
//                status_anim.start();
            }

            theme.resolveAttribute(android.R.attr.itemBackground, typedValue, true);
            color = ResourcesCompat.getColor(resources, typedValue.resourceId, null);
            PageFragment.setBackColor(color);  // 此在新建网页时使用
            for (int i = 0, n = mViewPager.getChildCount(); i < n; i++) {
                WebView webView = (WebView) mViewPager.getChildAt(i).findViewById(R.id.webView_section);
                if (webView != null) {
                    webView.setBackgroundColor(color);
                    webView.setTag(R.id.web_tag_in_html, mAdapter.getPageContent(PageFragment.getPageIndex(webView)));
                    if (PageFragment.getContentMode(webView) <= PageFragment.MODE_START) {
                        //mAdapter.reloadToMode(webView, ViewPagerAdapter.getContentMode(webView));
                        String body_color = night ? "'#aaa'" : "'#111'";  // 视频和图片没文字不用变
                        webView.loadUrl("javascript:body_color(" + body_color + ")");  // 注意单引号
                    }
                }
            }
//            // 相当于屏幕截图，但是这样动图/视频就卡了
//            view.setDrawingCacheEnabled(true);
//            view.buildDrawingCache(true);
//            final Bitmap localBitmap = Bitmap.createBitmap(view.getDrawingCache());
//            view.setDrawingCacheEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "toggleNightTheme: " + e.toString());
        }
    }

    public int getToolbarState() {
        return mToolbarState;
    }

    public void setToolbarDoingExpand(boolean expand) {
        mToolbarDoingExpand = expand;
        mToolbarDoingCollapse = !expand;
    }

    public void setToolBarExpanded(boolean expand) {
        setToolbarDoingExpand(expand);  // 强制给动画留出时间
        mAppBarLayout.setExpanded(expand);
    }

    public void setToolBarVisible(boolean visible) {
        final AppBarLayout.LayoutParams params = (AppBarLayout.LayoutParams) mToolbarLayout.getLayoutParams();
        final int toggleFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED;  // scroll取消是一直展开
        if (visible) {
            mToolbarLayout.postDelayed(new Runnable() {
                @Override
                public void run() {
                    params.setScrollFlags(params.getScrollFlags() | toggleFlags); // 触发AppBar的OffsetChanged
                }
            }, 300);  // 等自带控件消失后填补空位
            mToolbarLayout.postDelayed(new Runnable() {
                @Override
                public void run() {
                    setTitle(getTitle());
                    mToolbarLayout.animate().alpha(1).start();
                }
            }, 500);  // 等动画完成改标题才能居中
        } else {
            params.setScrollFlags(params.getScrollFlags() & ~toggleFlags);  // 取消属性隐藏后剩个空的AppBar
            mToolbarLayout.animate().alpha(0).start();  // 改Toolbar还是没用
        }
    }


    private void setToolBarListeners() {
        // 只应在onCreate时，已初始化完毕这些变量后调用！
        mAppBarLayout.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
            @Override
            public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
                //Log.w("offsetChanged", "offset = " + verticalOffset);
                int collapseOffset = mAppBarLayout.getTotalScrollRange();
                if (verticalOffset >= 0)
                    mToolbarState = TOOLBAR_EXPANDED;   // 完全展开时offset为0
                else if (verticalOffset <= -collapseOffset)
                    mToolbarState = TOOLBAR_COLLAPSED;  // 完全折叠为-1*高度(隐藏时更负)
                else
                    mToolbarState = TOOLBAR_INTERMEDIATE;

                // 不允许展开时要阻止手指在标题栏下拉(展开一点再阻止不然会死循环)
                boolean forceAnimate = mToolbarDoingExpand || mToolbarDoingCollapse;
                if (mToolbarState != TOOLBAR_COLLAPSED && !forceAnimate) {
                    if (getCurrentWebViewMode() != PageFragment.MODE_START)
                        mAppBarLayout.setExpanded(false, false);  // 有动画会死循环，下面也是
                    if (getCurrentWebViewScrollPos() > 10)  // >0易在滚动微小不同步时突然折叠
                        mAppBarLayout.setExpanded(false, false);  // 查找时也不行(高亮在屏幕底看不到)
                }
                if (mToolbarState == TOOLBAR_EXPANDED)
                    mToolbarDoingExpand = false;  // 展开动画完成再重置
                if (mToolbarState == TOOLBAR_COLLAPSED)
                    mToolbarDoingCollapse = false;  // 不要一起重置：开始时一般恰满足对面条件，重置就没了

                // 自带渐变有延时，自己加一层文本透明度渐变，在折叠一半多一点就全透明
                int alpha = (int) (255 * (1 + 1.5f * verticalOffset / collapseOffset));  // verticalOffset是负数
                if (alpha < 0) alpha = 0;
                TextView textView = (TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout);
                textView.setTextColor((alpha << 24) + (textView.getCurrentTextColor() & 0xFFFFFF));
            }
        });

        // 标题栏折叠时的点击，查找文本时点查找下一个按钮偏下时会误触发
        mToolBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getCurrentWebViewMode() == PageFragment.MODE_START &&
                        !MenuItemCompat.isActionViewExpanded(mSearchItem)) {
                    if (getCurrentWebViewScrollPos() > 10) {
                        PageFragment.setScrollPos(getCurrentWebView(), 0, true);
                        // 等上滚动画快结束再展开；注意getScrollY不会立即变0，直接去展开会被阻止
                        mToolBar.postDelayed(new Runnable() {
                            @Override
                            public void run() {setToolBarExpanded(true);}
                        }, 200);
                    } else
                        setToolBarExpanded(true);  // 不用上滚动画
                }
            }
        });

        // 标题栏展开时的点击查找同问题下的回答
        mToolbarLayout.setOnClickListener(new View.OnClickListener() {
            private Toast toast = null;
            private long lastClickTime = 0;
            private int fastClickCount = 0;
            private CheckForClicks pendingCheckForClicks = new CheckForClicks();
            class CheckForClicks implements Runnable {
                @Override
                public void run() {
                    performQuery(fastClickCount);
                    fastClickCount = 0;
                }
            }

            private void performQuery(int clicks) {
                int pos = mViewPager.getCurrentItem();
                String key, field, message;
                switch (clicks) {
                    case 1:
                        key = mAdapter.getPageTitle(pos).toString();
                        field = "title";
                        message = getString(R.string.no_same_title);
                        break;
                    case 2:
                        key = mAdapter.getPageUser(pos);
                        field = "name";
                        message = getString(R.string.no_same_user);
                        break;
                    case 3:
                        key = mAdapter.getPageDate(pos);
                        field = "revision";
                        message = getString(R.string.no_same_date);
                        break;
                    default:
                        return;  // 连续点击太多次就不管咯
                }

                // 在manifest里设置main的launchMode="singleTask"，则startActivity时可重用最初的
                // 但main设置singleTask会把其上的窗口都杀掉，因此即使本窗口设为singleTop也不能复用
                // 另main启动本窗口用的startActivityForResult使singleTop无效
                if (mAdapter.tryBaseQuery(key, field, "") > 1) {
                    mAdapter.dbDetach();  // 免得后面addSomeAsync后说没notify

                    // 不必担心Result造成滚动，main会清空，滚动不了…
                    // 除非之前就在搜这个标题不会清空，但这样正好需要滚…
                    // 另改动画必须在startActivity或finish后调用
                    mResult.putExtra("key", key);
                    mResult.putExtra("field", field);
                    finish();  // startActivity和NavUtils.navigateUpFromSameTask会忽略Result
                    overridePendingTransition(R.anim.popup_show_bottom, R.anim.popup_hide_bottom);
                } else {
                    if (toast != null) toast.cancel();
                    (toast = Toast.makeText(ContentActivity.this, message, Toast.LENGTH_SHORT)).show();
                }  // UI的Context别用getApplicationContext()，生命周期太长，Activity都Destroy了还在
            }

            @Override
            public void onClick(View v) {
                long delta_time = System.currentTimeMillis() - lastClickTime;
                if (delta_time < ViewConfiguration.getDoubleTapTimeout()) {
                    v.removeCallbacks(pendingCheckForClicks);  // 要移除之前的，不然ClickCount会清0
                }
                v.postDelayed(pendingCheckForClicks, ViewConfiguration.getDoubleTapTimeout());
                lastClickTime = System.currentTimeMillis();
                fastClickCount++;
            }
        });

//        NestedScrollView scrollView = (NestedScrollView) findViewById(R.id.scroll_container);
//        scrollView.setOnTouchListener(new View.OnTouchListener() {
//            // 网页仅在起始页可能放弃阻止父级拦截(但也是MOVE后)，复制/图片/视频等全程由网页处理
//            // 本View要滑动一段才开始拦截，遂收不到DOWN，只能从MOVE开始(毕竟滚动只需相对距离)
//            // 拦截后就不会经过onInterceptTouch了，而是直接走onTouch(Event)
//            // 但最大的问题就是DOWN拦不到显得滑动迟钝，尤其是快速滑动可能不响应
//            private VelocityTracker tracker;
//            private int dragDirection;  // 0不算拖动 1主要X轴(横向) 2主要Y轴(纵向)
//            private float touchStartX, touchStartY;
//            private boolean firstMove = true, firstInterceptForChild;
//
//            @Override
//            public boolean onTouch(View v, MotionEvent event) {
//                WebView webView = getCurrentWebView();
//                if (webView == null) return false;
//
//                int action = event.getActionMasked();
//                float deltaX = event.getRawX() - touchStartX;
//                float deltaY = event.getRawY() - touchStartY;  // ViewConfiguration.get(..).getScaledTouchSlop()
//                float thresh = 8 * getResources().getDisplayMetrics().density;  // 8dp->px 换页是16dp
//                float velocityY = 0f;
//                boolean consumed = false;
//
//                if (tracker == null)  // 收到事件不一定从DOWN开始
//                    tracker = VelocityTracker.obtain();
//
//                if (firstMove && MotionEvent.ACTION_UP != action) {
//                    tracker.clear();
//                    tracker.addMovement(event);  // 后面的DOWN就不能有clear
//                    action = MotionEvent.ACTION_DOWN;  // 不要影响实际的event
//                    firstMove = false;
//                }
//
//                String TAG = "nested_onTouch";
//                String[] s = {"DOWN", "UP", "MOVE", "CANCEL", "OUTSIDE"};
////                if (action != MotionEvent.ACTION_MOVE)
//                    Log.w(TAG, "action = " + (action < 5 ? s[action] :
//                            "POINTER_" + s[event.getActionMasked() - 5] + " #" + event.getActionIndex()) +
//                            " @(" + (int) event.getRawX() + ", " + (int) event.getRawY() + ")");
//
//                switch (action) {
//                    case MotionEvent.ACTION_DOWN:
//                        touchStartX = event.getRawX();
//                        touchStartY = event.getRawY();
//                        dragDirection = 0;
//                        firstInterceptForChild = true;
//                        break;
//
//                    case MotionEvent.ACTION_MOVE:
//                        tracker.addMovement(event);
//                        tracker.computeCurrentVelocity(1000);
//                        velocityY = tracker.getYVelocity();
//                        if (dragDirection == 0) {   // 滑远又滑回来时不再白白消费thresh距离
//                            if (Math.abs(deltaX) > Math.max(2 * thresh, Math.abs(deltaY)))
//                                dragDirection = 1;  // ViewPager能立刻响应
//                            else if (Math.abs(deltaY) > thresh)
//                                dragDirection = 2;
//                        }
//                        break;
//
//                    case MotionEvent.ACTION_UP:
//                    case MotionEvent.ACTION_CANCEL:
//                        if (tracker != null) {
//                            tracker.recycle();
//                            tracker = null;
//                        }
//                        firstMove = true;
//                        break;
//                }
//
//                MotionEvent childEvent = MotionEvent.obtain(event);  // 防止影响自己的onTouchEvent
//                if (dragDirection == 1) {  // 这里不写else if，上面刚改完就可以进来
//                    consumed = true;       // 不要横竖一起动
//                    if (firstInterceptForChild && MotionEvent.ACTION_UP != action) {
//                        childEvent.setAction(MotionEvent.ACTION_DOWN);
//                    }
//                    mViewPager.onTouchEvent(childEvent);  // 给onIntercept要等x>2y才拦截
//                    firstInterceptForChild = false;       // 给ViewPager后不会再要回来
//                } else if (dragDirection == 2) {
//                    // 在标题栏折叠完时(只看网页在顶)，要把手指上滑(dy<0)给WebView处理，但下滑不给(出标题栏)
//                    // 在标题栏展开完时(网页必已在顶)，手指下滑给WebView(边缘滑动发光)，但上滑不给(收标题栏)
//                    // 滑一段才能判断，此前别传给网页，否则顶部上滑在thresh内造成网页滑动，会使标题栏突然折叠
//                    // 之前在网页处理拦截，若放弃阻止后再调用阻止父级拦截，事件还是只到父级，只能硬塞给网页处
//                    if (mAppbarCollapsed && (deltaY < 0 && velocityY < 0 || webView.getScrollY() > 0) ||
//                            mAppbarExpanded && deltaY > 0 && velocityY > 0) {  // 定了direction就不用thresh了
//                        if (firstInterceptForChild && MotionEvent.ACTION_UP != action) {
//                            childEvent.setAction(MotionEvent.ACTION_DOWN);  // 给网页以DOWN开始，不然不动
//                        }
//                        webView.onTouchEvent(childEvent);  // dispatch流程多比较卡
//                        firstInterceptForChild = false;
////                        if (mAppbarCollapsed && webView.getScrollY() > 0) {
//                            consumed = true;  // 此时嵌套滑会被OffsetChange阻止(不让标题栏展开)
////                        }                     // 下滑时距离将积累，直到滑到顶不被阻止时突然展开
//                    } else {
//                        if (!firstInterceptForChild) {
//                            childEvent.setAction(MotionEvent.ACTION_CANCEL);  // 若CANCEL自己以后就不动了
//                            webView.onTouchEvent(childEvent);  // 开始拦截时给网页CANCEL，如停止检测长按
//                        }  // 用onTouchEvent绕过onTouch也防止网页接收CANCEL后又传递过来
//                        firstInterceptForChild = true;
//                    }
//                }
//                childEvent.recycle();
//
//                Log.w(TAG, "consumed=" + consumed + ". " +
//                        "d=(" + (int) deltaX + ", " + (int) deltaY + ")" + ". vy=" + (int) velocityY);
//                return consumed;  // 得让系统处理与appbar的嵌套滑动(嵌套滑不动的网页不会放弃给这里)
//            }
//        });
    }

    @Override
    public void setTitle(CharSequence title) {
        // 若ToolBarLayout用setTitleEnabled(true)则ToolBar的Title不管用
        super.setTitle(title);
        mToolBar.setTitle(title);
        mToolbarLayout.setTitle(title);
        ((TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout)).setText(title);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.w(TAG, "onCreate: position = " + getIntent().getIntExtra("position", 0));
        mPreferences = getSharedPreferences("Settings", MODE_PRIVATE);
        PageFragment.setTextZoom(null, mPreferences.getInt("TextZoom", 85));
        PageFragment.setNightTheme(mPreferences.getBoolean("NightTheme", false));
        setTheme(PageFragment.isNightTheme() ? R.style.AppTheme_Night : R.style.AppTheme_NoActionBar);

        // super.onCreate里也是先改主题再调super
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content);
        sReference = new WeakReference<Context>(this);

        mNetStateReceiver = new ConnectivityState(this);
        mNetStateReceiver.setListener(new ConnectivityState.OnStateChangeListener() {
            @Override
            public void onChanged() {
                PageFragment.changeCacheMode(getCurrentWebView());  // 隔壁页换到时会调整下载策略
            }
        });
        registerReceiver(mNetStateReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));

        NestedScrollView scrollView = (NestedScrollView) findViewById(R.id.scroll_container);
        scrollView.setFillViewport(true);  // 这个设置是必须的，否则里面的ViewPager不可见

        // 要求manifest里设置主题为AppTheme.NoActionBar
        mToolBar = (Toolbar) findViewById(R.id.toolbar_content);
        mAppBarLayout = (AppBarLayout) findViewById(R.id.app_bar);
        mToolbarLayout = (CollapsingToolbarLayout) findViewById(R.id.toolbar_layout);
        mToolbarLayout.setExpandedTitleColor(0x00FFFFFF);  // 透明的白色(默认的透明是黑色的)
        setToolBarListeners();

        setSupportActionBar(mToolBar);
        ActionBar actionBar = getSupportActionBar();  // 继承Activity时用getActionBar
        if (actionBar != null) {  // 返回只需在Manifest加android:parentActivityName=".MainActivity"
            actionBar.setDisplayShowHomeEnabled(false);  // 去掉标题栏图标
            actionBar.setDisplayHomeAsUpEnabled(true);   // 启用返回按钮事件(onOptionsItemSelected)
        }

        mResult = getIntent();  // 直接利用；下面setCurrentItem()用到mResult
        setResult(RESULT_OK, mResult);  // 不换页就返回也得有个结果

        setTitle("加载中...");
        String[] query = (savedInstanceState != null) ? savedInstanceState.getStringArray("query") : null;
        final int position = (savedInstanceState != null) ? savedInstanceState.getInt("position") :
                                                            mResult.getIntExtra("position", 0);

        // Create the mAdapter that will return a fragment for each sections of the activity.
        mAdapter = new ViewPagerAdapter(this, getFragmentManager());  // 这里可能遇到删库
        mAdapter.setBaseQuery(query);     // 转屏时设置后和原来一样(这个set不clear)
        mAdapter.notifyDataSetChanged();  // 更新一次Count才知道要不要addSome
        mAdapter.setEntryPage(position);  // 除此外的其他页等滑到才加载图片

        // Set up the ViewPager with the sections mAdapter.
        mViewPager = (ViewPager) findViewById(R.id.container);
        mViewPager.addOnPageChangeListener(new onPageChangeListener());
        mViewPager.setAdapter(mAdapter);  // 有数据下面才能换页

        // 清内存重入需要add，而从main点进来时不用
        if (position + 1 > mAdapter.getCount()) {  // position+1才是数量，正数才会执行(被删库时得久一点)
            mAdapter.addSomeAsync(position + 1 - mAdapter.getCount());
            mAdapter.addListener(new SQLiteHelper.AsyncTaskListener() {  // 加在adapter的notify的监听后
                @Override
                public void onStart(AsyncTask task) {}

                @Override
                public void onAsyncFinish(AsyncTask task) {
                    if (task.isCancelled()) {
                        mAdapter.removeListener(this);
                    }
                }

                @Override
                public void onFinish(AsyncTask task) {
                    mAdapter.removeListener(this);
                    mViewPager.setCurrentItem(position);
                    setTitle(mAdapter.getPageTitle(position));
                    for (int i = 0, n = mViewPager.getChildCount(); i < n; i++) {
                        WebView webView = (WebView) mViewPager.getChildAt(i).findViewById(R.id.webView_section);
                        if (webView != null) {  // 异步期间可能按url和tag为null加载过，即about:blank
                            if (PageFragment.getContentMode(webView) == PageFragment.MODE_BLANK) {
                                String html = mAdapter.getPageContent(PageFragment.getPageIndex(webView));
                                webView.setTag(R.id.web_tag_in_html, html);
                                mAdapter.reloadToMode(webView, PageFragment.MODE_START);
                            }  // 不用重建数据库时，查数据库比生成窗口快，窗口建好时已能正常加载，不必再刷新
                        }  // 清内存前Tag也会保存在Fragment的savedInstanceState里…其实整个for只用于意外情况
                    }
                }
            });
        } else {
            mViewPager.setCurrentItem(position);  // 非0时顺便触发换页监听
            setTitle(mAdapter.getPageTitle(position));  // 0页不触发换页监听
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.w(TAG, "onConfigurationChanged");
        super.onConfigurationChanged(newConfig);  // 先让他更新资源再继续，主要是R.style.xxx对应的值

        // 重新layout一个ContentView然后把布局参数复制过来
        // Activity的setContentView()方法的实现在PhoneWindow里
        View newContentView = getLayoutInflater().inflate(R.layout.activity_content,
                (ViewGroup) getWindow().getDecorView(), false);
        Toolbar newToolBar = (Toolbar) newContentView.findViewById(R.id.toolbar_content);
        AppBarLayout newAppBarLayout = (AppBarLayout) newContentView.findViewById(R.id.app_bar);
        CollapsingToolbarLayout newToolbarLayout = (CollapsingToolbarLayout) newContentView.findViewById(R.id.toolbar_layout);
        TextView oldTitle = (TextView) mToolbarLayout.findViewById(R.id.textView_toolbarLayout);
        TextView newTitle = (TextView) newToolbarLayout.findViewById(R.id.textView_toolbarLayout);

        // 主要是多处关联的app_bar_height、actionBarSize和字体大小会改变
        mAppBarLayout.setLayoutParams(newAppBarLayout.getLayoutParams());
        mToolbarLayout.setLayoutParams(newToolbarLayout.getLayoutParams());
        mToolbarLayout.setCollapsedTitleTextAppearance(R.style.TextAppearance_Toolbar_Title);
        mToolbarLayout.setExpandedTitleTextAppearance(R.style.TextAppearance_Toolbar_Title);
        mToolbarLayout.setExpandedTitleColor(0x00FFFFFF);  // 同onCreate
        mToolBar.setLayoutParams(newToolBar.getLayoutParams());  // 外有layout时toolbar的样式无效
        //mToolBar.setTitleTextAppearance(this, R.style.TextAppearance_Toolbar_Title);
        oldTitle.setLayoutParams(newTitle.getLayoutParams());
        oldTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, newTitle.getTextSize());
//        ViewGroup.LayoutParams params = mAppBarLayout.getLayoutParams();
//        params.height = resources.getDimensionPixelSize(R.dimen.app_bar_height);
//        mAppBarLayout.setLayoutParams(params);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        // 如果转屏要重启，可以保存Adapter之类的复杂配置；退出之类的时候不会调用此函数
        Log.w(TAG, "onRetainCustomNonConfigurationInstance");
        return super.onRetainCustomNonConfigurationInstance();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {  // 别用有outPersistentState的重载，不调用的
        Log.w(TAG, "onSaveInstanceState: position = " + mResult.getIntExtra("position", 0));
        super.onSaveInstanceState(outState);
        outState.putInt("position", mResult.getIntExtra("position", 0));
        outState.putStringArray("query", mAdapter.getBaseQuery());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_content, menu);

        mSearchItem = menu.findItem(R.id.content_search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(mSearchItem);
        //SearchView searchView = (SearchView) menuItem.getActionView();  // ActionBar时用这
        searchView.setQueryHint(getString(R.string.content_search_hint));
        searchView.setSubmitButtonEnabled(true);  // 显示按钮方便查找下一个
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mAdapter.setQuery(getCurrentWebView(), query, true);
                setToolBarExpanded(false);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText.isEmpty()) {  // 清除/退出查找都会引发变空串；但改用Toolbar后就没了
                    mAdapter.setQuery(getCurrentWebView(), "", true);
                }
                return false;
            }
        });
        MenuItemCompat.setOnActionExpandListener(mSearchItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);
                searchView.setQuery("", true);  // 改用Toolbar后这些都要自己做
                hideInputMethod();
                return true;
            }
        });

        try {  // 长按查找的提交按钮时往前找
            //View goButton = searchView.findViewById(R.id.search_go_btn);  // 用这返回null
            Field fieldGoButton = searchView.getClass().getDeclaredField("mGoButton");
            fieldGoButton.setAccessible(true);  // 强行可访问；class是整个虚拟机共用(当然也包括里面的field名)
            View goButton = (View) fieldGoButton.get(searchView);  // 因此获取反射的field时要给定class的实例
            goButton.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    SearchView searchView = (SearchView) MenuItemCompat.getActionView(mSearchItem);
                    mAdapter.setQuery(getCurrentWebView(), searchView.getQuery().toString(), false);
                    setToolBarExpanded(false);
                    return true;  // 不引发Click
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "onCreateOptionsMenu: " + e.toString());
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 为了方便修改菜单主题，系统菜单已设为基本不显示(除了显示第一项以伪装溢出菜单，不然就没菜单了)
        // 点击伪装的溢出菜单按钮后弹出的是自定义菜单，当然事件还能直接用的系统菜单的事件
        //onDisplayOptionsMenu(menu);  // 有自定义菜单时不能调用此函数，系统菜单应只显示伪装按钮
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch (item.getItemId()) {
            case android.R.id.home:  // 标题栏返回按钮(ToolBar开搜索时的返回是搜索控件自己的)
                onBackPressed();
                return true;

            case R.id.content_menu_entry:  // 伪装的溢出菜单按钮
                showOverflowMenu();
                return true;

            case R.id.content_search:
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);
                searchView.onActionViewExpanded();  // 改用ToolBar后得默认展开
                return true;

            case R.id.content_refresh:  // 刷新，直接调WebView的reload是按照url(about:blank)弄的
                reloadCurrentWebView(getCurrentWebViewMode());
                return true;

            case R.id.content_copy_link:
                WebView webView = getCurrentWebView();
                if (webView != null) copyLink((String) webView.getTag(R.id.web_tag_url));
                return true;

            case R.id.content_night_mode:
                PageFragment.toggleNightTheme();
                mPreferences.edit().putBoolean("NightTheme", PageFragment.isNightTheme()).apply();
                return true;

            case R.id.content_save:
                File src = PageFragment.getContentCache(getCurrentWebView());  // 空WebView返回null
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File dest = new File(dir, "fav");  // 放在图片的子文件夹，fav恰好会被文件管理视为收藏...
                String path = null;
                if (src != null && (dest.exists() || dest.mkdirs())) {
                    try {  // mkdirs需要WRITE_EXTERNAL_STORAGE权限
                        String src_name = src.getName();  // 要去掉结尾的.0
                        dest = new File(dest, src_name.substring(0, src_name.length() - 2));
                        FileChannel in = new FileInputStream(src).getChannel();
                        FileChannel out = new FileOutputStream(dest).getChannel();
                        in.transferTo(0, in.size(), out);  // OutputStream会创建文件(文件夹存在)
                        in.close();  // 用FileChannel可比BufferedStream再快1倍
                        out.close();

                        path = dest.getAbsolutePath().replace(
                                Environment.getExternalStorageDirectory().getAbsolutePath(),
                                getString(R.string.external_storage));
                    } catch (Exception e) {
                        Log.e(TAG, "onOptionsItemSelected_save: " + e.toString());
                        if (dest.isFile() && dest.delete()) {
                            dest = dir;
                        }
                    }
                }
                String msg = dest.isFile() ? getString(R.string.content_saved, path) : getString(R.string.content_not_saved);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                return true;

            case R.id.content_text_size:
                hideInputMethod();
                int color_id = PageFragment.isNightTheme() ?
                        R.color.window_background_night : R.color.window_background;
                View view = getLayoutInflater().inflate(R.layout.popup_zoom, null);
                PopupWindow window = new PopupWindow(view, 0, 0, true);
                window.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
                window.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setOutsideTouchable(true);  // 允许点外边取消，有背景才能点外边(4.4只能用此函数)
                window.setBackgroundDrawable(new ColorDrawable(getResources().getColor(color_id)));
                window.setAnimationStyle(R.style.popup_anim_bottom);
                window.showAtLocation(findViewById(android.R.id.content), Gravity.BOTTOM, 0, 0);
                window.setOnDismissListener(new PopupWindow.OnDismissListener() {
                    @Override
                    public void onDismiss() {  // 左右已经载入的网页也要改缩放
                        for (int i = 0, n = mViewPager.getChildCount(); i < n; i++) {
                            WebView webView = (WebView) mViewPager.getChildAt(i).findViewById(R.id.webView_section);
                            PageFragment.setTextZoom(webView, PageFragment.getTextZoom());
                        }
                        mPreferences.edit().putInt("TextZoom", PageFragment.getTextZoom()).apply();
                    }
                });

                SeekBar mSeekBar = (SeekBar) view.findViewById(R.id.seekBar);  // 每格15，中间100
                mSeekBar.setProgress((PageFragment.getTextZoom() - 70) / 15);
                mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int text_zoom = 70 + 15 * progress;
                        PageFragment.setTextZoom(getCurrentWebView(), text_zoom);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
                //mSeekBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.popup_show_bottom));
                return true;

            case R.id.content_open_with:  // 用其他应用打开
                String currentUrl = mAdapter.getPageLink(mViewPager.getCurrentItem());
                if (currentUrl != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(currentUrl));
                    // uri用zhihu://只能接questions/answers/people/ 专栏不行；而用http://要选应用
                    List<ResolveInfo> pkgInfo = getPackageManager().queryIntentActivities(intent, 0);
                    for (ResolveInfo pkg : pkgInfo)  // 知乎日报(zhihu.daily)不掺和；没知乎用浏览器
                        if (pkg.activityInfo.packageName.contains("com.zhihu.android"))
                            intent.setPackage(pkg.activityInfo.packageName);
                    startActivity(intent);
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        Log.w(TAG, "onActionModeStarted");
        super.onActionModeStarted(mode);
        if (getCurrentWebViewMode() == PageFragment.MODE_START) {
            PageFragment.setContentMode(getCurrentWebView(), PageFragment.MODE_COPY);
            setToolBarExpanded(false);
        }
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        Log.w(TAG, "onActionModeFinished");
        super.onActionModeFinished(mode);
        if (getCurrentWebViewMode() < PageFragment.MODE_START) {
            PageFragment.setContentMode(getCurrentWebView(), PageFragment.MODE_START);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showOverflowMenu();  // 菜单显示后再按键都会取消菜单，且不触发此事件
            return true;         // KeyDown也拦不到
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (goBackCurrentWebView()) return;
        super.onBackPressed();  // 这里最后会调用this.finish();
    }  // 实体键盘的返回键

    @Override
    protected void onDestroy() {
        Log.w(TAG, "onDestroy");
        super.onDestroy();
        unregisterReceiver(mNetStateReceiver);
        mAdapter.dbDetach();
        sReference = null;
    }

}
