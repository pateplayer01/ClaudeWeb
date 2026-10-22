package com.example.claudeweb;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String HOME = BuildConfig.HOME;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int FILE_REQ = 1;
    private static final String HOOK = "(function(){if(window.__dlHook)return;window.__dlHook=1;window.__blobs={};var oc=URL.createObjectURL;URL.createObjectURL=function(b){var u=oc.call(URL,b);try{window.__blobs[u]=b;}catch(e){}return u;};var ac=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(this.download)window.__lastName=this.download;return ac.apply(this,arguments);};document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;if(a)window.__lastName=a.getAttribute('download');},true);})();";

    private FrameLayout root;
    private WebView web;
    private WebView popup;
    private volatile boolean expectingBlob = false;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setup(web);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            Uri d = getIntent().getData();
            web.loadUrl(d != null && isAllowed(d) ? d.toString() : HOME);
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
        Uri d = intent.getData();
        if (d != null && isAllowed(d)) {
            closePopup();
            web.loadUrl(d.toString());
        }
    }

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
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                v.evaluateJavascript(HOOK, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(HOOK, null);
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
                    + "var b=window.__blobs&&window.__blobs[u];if(b){send(b);return;}"
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

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
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
