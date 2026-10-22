package com.example.claudeweb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String HOME = BuildConfig.HOME;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int FILE_REQ = 1;

    // Giu toi da 5 blob gan nhat de doc khi tai ve, tranh ton RAM.
    private static final String HOOK =
            "(function(){if(window.__dlHook)return;window.__dlHook=1;window.__blobs={};window.__blobOrder=[];"
            + "var oc=URL.createObjectURL;URL.createObjectURL=function(b){var u=oc.call(URL,b);"
            + "try{window.__blobs[u]=b;window.__blobOrder.push(u);"
            + "while(window.__blobOrder.length>5){delete window.__blobs[window.__blobOrder.shift()];}}catch(e){}return u;};"
            + "var ac=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){"
            + "if(this.download)window.__lastName=this.download;return ac.apply(this,arguments);};"
            + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;"
            + "if(a)window.__lastName=a.getAttribute('download');},true);})();";

    private FrameLayout root;
    private WebView web;
    private WebView popup;
    private ValueCallback<Uri[]> filePathCallback;
    private volatile boolean expectingBlob = false;

    private String errorUrl = null;
    private boolean mainLoaded = false;
    private boolean restoredFromKill = false;
    private String pendingText = null;
    private ArrayList<Uri> pendingUris = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        web = new WebView(this);
        web.setBackgroundColor(getResources().getColor(R.color.app_bg, getTheme()));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setup(web);
        addMenuButton();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
            restoredFromKill = true;
        } else {
            Uri d = getIntent().getData();
            web.loadUrl(d != null && isAllowed(d) ? d.toString() : HOME);
            handleShare(getIntent());
        }
    }

    private static boolean isAllowed(Uri u) {
        if (!"https".equals(u.getScheme())) return false;
        String host = u.getHost();
        if (host == null) return false;
        for (String h : BuildConfig.HOSTS.split(",")) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri d = intent.getData();
        if (d != null && isAllowed(d)) {
            closePopup();
            web.loadUrl(d.toString());
        }
        handleShare(intent);
    }

    // ---------- Nhan noi dung chia se ----------

    @SuppressWarnings("deprecation")
    private void handleShare(Intent it) {
        if (it == null) return;
        String a = it.getAction();
        boolean single = Intent.ACTION_SEND.equals(a);
        if (!single && !Intent.ACTION_SEND_MULTIPLE.equals(a)) return;

        ArrayList<Uri> uris = new ArrayList<>();
        if (single) {
            Uri s = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (s != null) uris.add(s);
        } else {
            ArrayList<Uri> l = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }

        String text = it.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && !text.isEmpty()) {
            pendingText = text;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("shared", text));
        }
        if (!uris.isEmpty()) {
            pendingUris = uris;
            root.postDelayed(() -> pendingUris = null, 120000);
            toast("Bấm nút đính kèm để thêm tệp vừa chia sẻ");
        }
        if (mainLoaded) {
            if (pendingText != null) web.postDelayed(() -> deliverText(0), 500);
            if (pendingUris != null) web.postDelayed(() -> openFileInput(), 800);
        }
    }

    private void deliverText(final int attempt) {
        if (pendingText == null) return;
        String js = "(function(t){var el=document.querySelector('div[contenteditable=\"true\"]')||document.querySelector('textarea');"
                + "if(!el)return false;el.focus();"
                + "if(el.tagName==='TEXTAREA'){var set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;"
                + "set.call(el,(el.value?el.value+'\\n':'')+t);el.dispatchEvent(new Event('input',{bubbles:true}));}"
                + "else{document.execCommand('insertText',false,t);}return true;})(" + JSONObject.quote(pendingText) + ")";
        web.evaluateJavascript(js, value -> {
            if ("true".equals(value)) {
                pendingText = null;
            } else if (attempt < 6) {
                web.postDelayed(() -> deliverText(attempt + 1), 1000);
            } else {
                pendingText = null;
                toast("Nội dung đã được sao chép, hãy dán vào ô chat");
            }
        });
    }

    // Sau khi he thong dong ung dung va trang duoc khoi phuc, o nhap co the con chu cu
    // da gui roi. Chi xoa khi chu trong o trung voi noi dung dang co trong cuoc tro chuyen.
    private void cleanStaleDraft(final int attempt) {
        String js = "(function(){function norm(t){return (t||'').split('\\n').map(function(l){return l.replace(/^\\s*(\\d+[.)]|[-*\\u2022])\\s+/,'');}).join(' ').replace(/\\s+/g,' ').trim().toLowerCase();}function count(h,n){var c=0,i=0;while((i=h.indexOf(n,i))!==-1){c++;i+=n.length;}return c;}var el=document.querySelector('div[contenteditable=\"true\"]')||document.querySelector('textarea');if(!el)return 'noinput';var isTa=el.tagName==='TEXTAREA';var d=norm(isTa?el.value:el.innerText);if(d.length<8)return 'none';var body=norm(document.body.innerText);var inEl=isTa?0:count(norm(el.innerText),d);if(count(body,d)-inEl<1)return 'none';el.focus();if(isTa){var set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;set.call(el,'');el.dispatchEvent(new Event('input',{bubbles:true}));}else{document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);}return 'cleared';})()";
        web.evaluateJavascript(js, value -> {
            if (value != null && value.contains("cleared")) return;
            if (attempt < 6) web.postDelayed(() -> cleanStaleDraft(attempt + 1), 1500);
        });
    }

    private void openFileInput() {
        if (pendingUris == null) return;
        web.evaluateJavascript("(function(){var i=document.querySelector('input[type=file]');"
                + "if(i){i.click();return true;}return false;})()", null);
    }

    // ---------- Nut menu: Tai lai, Kiem tra cap nhat ----------

    private void addMenuButton() {
        final float d = getResources().getDisplayMetrics().density;
        int size = (int) (40 * d);
        final TextView b = new TextView(this);
        b.setText("\u22EE");
        b.setTextColor(Color.WHITE);
        b.setTextSize(22);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription("Menu");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xB3000000);
        b.setBackground(bg);
        b.setAlpha(0.55f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.TOP);
        lp.topMargin = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        lp.rightMargin = (int) (4 * d);
        root.addView(b, lp);

        b.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            float startTop;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        startTop = p.topMargin;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dy) > 10 * d) moved = true;
                        if (moved) {
                            int max = Math.max(0, root.getHeight() - v.getHeight());
                            p.topMargin = (int) Math.max(0, Math.min(max, startTop + dy));
                            v.setLayoutParams(p);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) showMenu(v);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Tải lại trang");
        m.getMenu().add(0, 2, 1, "Kiểm tra cập nhật");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) reloadPage();
            else checkUpdate();
            return true;
        });
        m.show();
    }

    private void reloadPage() {
        if (errorUrl != null) {
            String u = errorUrl;
            errorUrl = null;
            web.loadUrl(u);
        } else {
            web.reload();
        }
    }

    // ---------- Trang loi mat mang ----------

    private void showError(String url) {
        try {
            String enc = URLEncoder.encode(url, "UTF-8").replace("+", "%20");
            String html = "<html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<style>body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;"
                    + "justify-content:center;height:100vh;margin:0;background:#fff;color:#222}"
                    + "@media(prefers-color-scheme:dark){body{background:#1a1a1a;color:#eee}}"
                    + "button{margin-top:24px;padding:12px 28px;font-size:16px;border:0;border-radius:24px;"
                    + "background:#d97757;color:#fff}</style></head><body>"
                    + "<div style='font-size:18px'>Không thể kết nối</div>"
                    + "<div style='opacity:.6;margin-top:8px'>Kiểm tra mạng rồi thử lại</div>"
                    + "<button onclick=\"location.replace(decodeURIComponent('" + enc + "'))\">Thử lại</button>"
                    + "</body></html>";
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            errorUrl = url;
        } catch (Exception ignored) {
        }
    }

    // ---------- Kiem tra cap nhat (thu cong) ----------

    private void checkUpdate() {
        toast("Đang kiểm tra cập nhật...");
        new Thread(() -> {
            try {
                URL u = new URL("https://api.github.com/repos/" + BuildConfig.REPO + "/releases/latest");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "ClaudeWebApp");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) {
                    toast("Không kiểm tra được cập nhật");
                    return;
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();

                JSONObject j = new JSONObject(sb.toString());
                final String tag = j.getString("tag_name");
                int latest = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
                String apkUrl = null;
                JSONArray assets = j.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (BuildConfig.APK_NAME.equals(a.getString("name"))) {
                        apkUrl = a.getString("browser_download_url");
                    }
                }
                if (latest > BuildConfig.BUILD_NUMBER && apkUrl != null) {
                    final String fu = apkUrl;
                    runOnUiThread(() -> showUpdateDialog(tag, fu));
                } else {
                    toast("Bạn đang dùng bản mới nhất (build " + BuildConfig.BUILD_NUMBER + ")");
                }
            } catch (Exception e) {
                toast("Không kiểm tra được cập nhật");
            }
        }).start();
    }

    private void showUpdateDialog(String tag, final String url) {
        new AlertDialog.Builder(this)
                .setTitle("Có bản mới")
                .setMessage("Bản " + tag + " đã sẵn sàng. Tải về?")
                .setPositiveButton("Tải", (dlg, which) -> downloadApk(url))
                .setNegativeButton("Để sau", null)
                .show();
    }

    private void downloadApk(String url) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType("application/vnd.android.package-archive");
            req.setTitle(BuildConfig.APK_NAME);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, BuildConfig.APK_NAME);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            toast("Đang tải bản mới, bấm vào thông báo để cài");
        } catch (Exception e) {
            toast("Tải xuống thất bại");
        }
    }

    // ---------- WebView ----------

    private void setup(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(UA);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new DlBridge(), "AndroidDl");
        w.setDownloadListener((url, ua, cd, mime, len) -> handleDownload(w, url, ua, cd, mime));

        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                if (url != null && url.startsWith("http")) errorUrl = null;
                v.evaluateJavascript(HOOK, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(HOOK, null);
                if (v == web && url != null && url.startsWith("http") && restoredFromKill) {
                    restoredFromKill = false;
                    web.postDelayed(() -> cleanStaleDraft(0), 1500);
                }
                if (v == web && url != null && url.startsWith("http") && !mainLoaded) {
                    mainLoaded = true;
                    if (pendingText != null) web.postDelayed(() -> deliverText(0), 1200);
                    if (pendingUris != null) web.postDelayed(() -> openFileInput(), 1800);
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError err) {
                if (v == web && r.isForMainFrame()) showError(r.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme();
                if (scheme == null || scheme.equals("about") || scheme.equals("blob")
                        || scheme.equals("data") || scheme.equals("javascript")) {
                    return false;
                }
                if (scheme.equals("intent")) {
                    try {
                        Intent it = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                        Uri target = it.getData();
                        String fb = it.getStringExtra("browser_fallback_url");
                        if (target != null && ("https".equals(target.getScheme()) || "http".equals(target.getScheme()))) {
                            v.loadUrl(target.toString());
                        } else if (fb != null) {
                            v.loadUrl(fb);
                        }
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    toast("Mở ngoài: " + scheme);
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                String host = u.getHost();
                if (host != null) {
                    for (String h : BuildConfig.HOSTS.split(",")) {
                        if (host.equals(h) || host.endsWith("." + h)) return false;
                    }
                }
                // Chuyen huong tu dong (nhu buoc dang nhap Google) o lai trong ung dung.
                if (!r.hasGesture() || r.isRedirect()) return false;
                toast("Mở ngoài: " + host);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pendingUris != null && !pendingUris.isEmpty()) {
                    Uri[] arr = pendingUris.toArray(new Uri[0]);
                    pendingUris = null;
                    cb.onReceiveValue(arr);
                    return true;
                }
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(p.createIntent(), FILE_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                closePopup();
                popup = new WebView(MainActivity.this);
                setup(popup);
                root.addView(popup, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView v) {
                if (v == popup) closePopup();
            }
        });
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private void handleDownload(WebView w, String url, String ua, String cd, String mime) {
        String name = URLUtil.guessFileName(url, cd, mime);
        toast("Bắt đầu tải...");
        if (url.startsWith("blob:")) {
            String js = "(function(){var u='" + esc(url) + "';var name=window.__lastName||'" + esc(name) + "';window.__lastName=null;"
                    + "function send(b){var r=new FileReader();r.onloadend=function(){AndroidDl.save(r.result,'" + esc(mime) + "',name);};"
                    + "r.onerror=function(){AndroidDl.fail();};r.readAsDataURL(b);}"
                    + "var b=window.__blobs&&window.__blobs[u];if(b){delete window.__blobs[u];send(b);return;}"
                    + "var x=new XMLHttpRequest();x.open('GET',u,true);x.responseType='blob';"
                    + "x.onload=function(){send(x.response);};x.onerror=function(){AndroidDl.fail();};x.send();})();";
            expectingBlob = true;
            w.evaluateJavascript(js, null);
            return;
        }
        if (url.startsWith("data:")) {
            new DlBridge().saveInternal(url, mime, name);
            return;
        }
        if (!url.startsWith("http")) return;
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (ua != null) req.addRequestHeader("User-Agent", ua);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            toast("Đang tải: " + name);
            if (w == popup) root.post(this::closePopup);
        } catch (Exception e) {
            toast("Tải xuống thất bại");
        }
    }

    private class DlBridge {
        @JavascriptInterface
        public void fail() {
            expectingBlob = false;
            toast("Không đọc được tệp");
        }

        @JavascriptInterface
        public void save(String dataUrl, String mime, String name) {
            if (!expectingBlob) return;
            expectingBlob = false;
            saveInternal(dataUrl, mime, name);
        }

        void saveInternal(String dataUrl, String mime, String name) {
            try {
                int comma = dataUrl.indexOf(',');
                String header = dataUrl.substring(0, comma);
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                String type = mime;
                if (header.startsWith("data:") && header.contains(";")) {
                    String t = header.substring(5, header.indexOf(';'));
                    if (!t.isEmpty()) type = t;
                }
                if (type == null || type.isEmpty()) type = "application/octet-stream";

                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, type);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(bytes);
                    os.close();
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    FileOutputStream os = new FileOutputStream(new File(dir, name));
                    os.write(bytes);
                    os.close();
                }
                toast("Đã lưu vào Downloads: " + name);
            } catch (Exception e) {
                toast("Lưu tệp thất bại");
            }
        }
    }

    private void closePopup() {
        if (popup != null) {
            root.removeView(popup);
            popup.destroy();
            popup = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQ && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            filePathCallback = null;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    // Bo con tro nhap chu: khoi phuc lai trang thai o nhap khi ra/vao ung dung,
    // de ban phim khong tu dong dien lai chu cu.
    private void releaseInputFocus() {
        web.evaluateJavascript("(function(){var a=document.activeElement;"
                + "if(a&&a!==document.body&&a.blur)a.blur();})()", null);
        web.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            imm.restartInput(web);
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        releaseInputFocus();
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        releaseInputFocus();
    }

    @Override
    public void onBackPressed() {
        if (popup != null) {
            closePopup();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
