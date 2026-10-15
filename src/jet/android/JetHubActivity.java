package jet.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 平台代理 API 管理页（从《AI 模型与接口中心》→《管理平台代理 API》进入）。
 *
 * 整屏就是网关自带的那个管理页 —— **不再套原生外壳**。
 * 原先顶部那条「内嵌网关运行中 127.0.0.1:8391」加两个按钮属于重复：
 * 运行状态、一键写入、重试在网页里都已经有了，两套并存只会让用户
 * 分不清哪个是真的、还占掉本来就紧张的手机屏幕高度。
 *
 * 两条交互上的硬要求：
 * <ul>
 *   <li><b>供应商授权链接走系统浏览器</b>：{@code shouldOverrideUrlLoading} 把非本机
 *       地址交给外部浏览器。登录页在 WebView 里打开会顶掉管理页、且没有后退入口，
 *       用户授权完回不到原页面（曾因此以为「登录没保存」）。</li>
 *   <li><b>兜底后退按钮</b>：WebView 一旦真的产生了历史（例如本机回调页），
 *       右下角浮出「返回」，不指望用户知道系统返回键等于 WebView 后退。</li>
 * </ul>
 */
public class JetHubActivity extends Activity {

    private WebView webView;
    private TextView backBtn;
    private LinearLayout errorBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        jet.android.Boot.ensureStarted(this);

        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new Bridge(), "JetHubNative");
        // 网页 JS 报错时能在 logcat 里看到（真机排查只能靠这个）。
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                android.util.Log.i("JetHub", "[web] " + msg.message()
                        + " @" + msg.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternally(request.getUrl() == null ? null : request.getUrl().toString());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 只认主文档失败：子资源 404 不该把整页判死。
                if (request == null || request.isForMainFrame()) showError();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 管理页里「一键写入 AI 模型中心」需要调用原生能力（读墨听模型中心
                // 的备份 JSON 并合并写回），网页侧做不到，故在这里把按钮接到桥接对象上。
                view.evaluateJavascript(BIND_REGISTER_JS, null);
                view.evaluateJavascript(BIND_EXTERNAL_JS, null);
                syncBackButton();
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        backBtn = new TextView(this);
        backBtn.setText("返回");
        backBtn.setTextSize(14f);
        backBtn.setTextColor(Color.WHITE);
        backBtn.setGravity(Gravity.CENTER);
        int pad = dp(14);
        backBtn.setPadding(pad, dp(8), pad, dp(8));
        backBtn.setBackgroundColor(0xCC20242c);
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView != null && webView.canGoBack()) webView.goBack();
                else backBtn.setVisibility(View.GONE);
            }
        });
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM | Gravity.END;
        bp.setMargins(0, 0, dp(16), dp(20));
        root.addView(backBtn, bp);

        errorBox = buildErrorBox();
        errorBox.setVisibility(View.GONE);
        root.addView(errorBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        webView.loadUrl(homeUrl());
    }

    /** 网关未启动时的兜底页 —— 整屏 WebView 连不上时只剩白屏，用户无从下手。 */
    private LinearLayout buildErrorBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF14161a);
        int p = dp(24);
        box.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("内嵌网关未启动");
        title.setTextColor(0xFFece6dc);
        title.setTextSize(17f);
        box.addView(title);

        final TextView detail = new TextView(this);
        detail.setTextColor(0xFFb9b2a6);
        detail.setTextSize(13f);
        detail.setPadding(0, dp(12), 0, dp(16));
        box.addView(detail);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jet.android.Boot.ensureStarted(JetHubActivity.this);
                if (jet.android.Boot.isStarted()) {
                    errorBox.setVisibility(View.GONE);
                    webView.loadUrl(homeUrl());
                } else {
                    detail.setText(errorDetail());
                }
            }
        });
        box.addView(retry);

        // 详情文案随「重试」刷新，故每次显示时现算。
        detail.setTag("detail");
        box.setTag(detail);
        return box;
    }

    private void showError() {
        if (errorBox == null) return;
        TextView detail = (TextView) errorBox.getTag();
        if (detail != null) detail.setText(errorDetail());
        errorBox.setVisibility(View.VISIBLE);
    }

    private String errorDetail() {
        if (jet.android.Boot.isStarted()) {
            return "网关已在监听，但页面加载失败。\n可下拉重试，或稍后重新进入。";
        }
        String err = jet.android.Boot.lastError();
        return "原因：" + (err == null ? "未知" : err)
                + "\n日志：/data/data/" + getPackageName() + "/files/jethub/boot.log";
    }

    /**
     * 非本机地址一律交给系统浏览器。
     *
     * 供应商授权页（MiniMax / Trae / CodeBuddy / WorkBuddy）都在公网，
     * 在 WebView 里打开会把管理页顶掉；用户授权完按返回只回到登录页，
     * 看起来就像「登录没保存」。走外部浏览器后管理页原地不动，
     * 它自己的轮询会在凭据落库后自动翻成「登录成功」。
     */
    private boolean openExternally(String url) {
        if (url == null) return false;
        if (isLocal(url)) return false; // 本机页面（含 Trae 的 loopback 回调）留在 WebView
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("JetHub", "无法打开外部浏览器：" + url, t);
            return false; // 没有浏览器就退回 WebView 内打开，总比什么都不发生强
        }
        return true;
    }

    private static boolean isLocal(String url) {
        return url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
                || url.startsWith("https://127.0.0.1:") || url.startsWith("https://localhost:");
    }

    private void syncBackButton() {
        if (backBtn == null || webView == null) return;
        backBtn.setVisibility(webView.canGoBack() ? View.VISIBLE : View.GONE);
    }

    private static String homeUrl() {
        return "http://127.0.0.1:" + jet.core.GatewayServer.port() + "/";
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /**
     * 从系统浏览器授权完切回 App 时，让管理页立刻同步一次。
     *
     * 页面自己只在 visibilitychange 时刷新，而 Android WebView 不保证该事件在
     * Activity 恢复时触发；少了这个钩子，用户授权完回来会看到「还在等待授权」，
     * 以为登录没成功（实际凭据已经落库）。这里手动派发一次该事件即可复用
     * 页面既有的刷新逻辑，无需页面暴露额外接口。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){try{document.dispatchEvent(new Event('visibilitychange'));}"
                    + "catch(e){}})()", null);
            syncBackButton();
        }
    }

    /** 网页里的「一键写入 AI 模型中心」桥接对象。 */
    private final class Bridge {
        /**
         * 在系统浏览器里打开外链。
         *
         * 页面里 {@code target="_blank"} 的授权链接在 WebView 里会被当成同窗导航
         * （默认 setSupportMultipleWindows(false)），管理页被顶掉且回不来 ——
         * 这正是「点进供应商链接后没有返回按钮」的成因。交给外部浏览器后
         * 管理页原地不动，后台轮询继续跑，授权完成即自动出现账号。
         */
        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!openExternally(url)) {
                        // 外部浏览器不可用时退回 WebView 内打开；此时至少还有
                        // 右下角的「返回」兜底。
                        if (webView != null && url != null) webView.loadUrl(url);
                    }
                }
            });
        }

        @JavascriptInterface
        public String register() {
            String err;
            try {
                err = jet.core.ModelCenterRegistrar.register(JetHubActivity.this);
            } catch (Throwable t) {
                err = String.valueOf(t);
            }
            // 结果用原生 Toast 呈现，不依赖网页 alert()：Android WebView 未设
            // WebChromeClient 时会**静默丢弃** alert，按钮看起来像没反应。
            final String text = err == null
                    ? "已写入 AI 模型中心，可在 AI 模型中心查看"
                    : "写入失败：" + err;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(JetHubActivity.this, text, Toast.LENGTH_LONG).show();
                }
            });
            return err == null ? "ok" : err;
        }
    }

    /**
     * 在管理页里把注册按钮接到原生桥接对象。
     *
     * 管理页是自包含的静态 HTML（无外部依赖），故不改 HTML 而是注入这段脚本；
     * 原生对象缺失时（例如用电脑浏览器打开）退化为提示，按钮仍可点。
     */
    private static final String BIND_REGISTER_JS =
            "(function(){var b=document.getElementById('btn-register');if(!b)return;"
            + "if(b.getAttribute('data-native')==='1')return;b.setAttribute('data-native','1');"
            + "b.addEventListener('click',function(ev){ev.preventDefault();"
            + "var old=b.innerHTML;"
            + "if(!window.JetHubNative){b.innerHTML='<span>请在墨听 App 内使用此按钮</span>';"
            + "setTimeout(function(){b.innerHTML=old;},2600);return;}"
            + "b.disabled=true;b.innerHTML='<span>正在写入…</span>';"
            + "var r=window.JetHubNative.register();b.disabled=false;b.innerHTML=old;"
            + "if(r!=='ok'){var d=document.createElement('div');"
            + "d.style.cssText='margin-top:8px;font-size:12px;color:#c0453a;word-break:break-all';"
            + "d.textContent='写入失败：'+r;b.parentNode.appendChild(d);"
            + "setTimeout(function(){if(d.parentNode)d.parentNode.removeChild(d);},6000);}"
            + "},false);})();";

    /**
     * 把管理页里的站外链接改道到原生桥接对象。
     *
     * 页面渲染授权链接用的是 {@code <a target="_blank">}，而 WebView 默认
     * {@code setSupportMultipleWindows(false)} 会把它当同窗导航 —— 管理页被顶掉，
     * 用户授权完回不到原页面（这就是「没有返回按钮」的现场）。捕获阶段拦下点击
     * 交给 {@link Bridge#openUrl(String)}，页面自身的结构完全不用改。
     */
    private static final String BIND_EXTERNAL_JS =
            "(function(){if(window.__jhExt)return;window.__jhExt=1;"
            + "function local(u){return /^https?:\\/\\/(127\\.0\\.0\\.1|localhost)(:|\\/|$)/.test(u);}"
            + "document.addEventListener('click',function(ev){"
            + "var n=ev.target,g=0;"
            + "while(n&&n!==document&&g<8){g++;"
            + "if(n.tagName==='A'&&n.getAttribute){"
            + "var u=n.getAttribute('href')||'';"
            + "if(u&&!local(u)){ev.preventDefault();ev.stopPropagation();"
            + "if(window.JetHubNative&&window.JetHubNative.openUrl){window.JetHubNative.openUrl(u);}"
            + "else{window.open(u,'_blank');}"
            + "return;}}"
            + "n=n.parentNode;}},true);})();";

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            syncBackButton();
        } else {
            super.onBackPressed();
        }
    }
}
