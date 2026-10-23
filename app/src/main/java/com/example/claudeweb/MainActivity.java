package com.example.claudeweb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            ensureDmReceiver();
            long id = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            dmJobs.put(id, new String[]{url, null, null, BuildConfig.APK_NAME, "application/vnd.android.package-archive"});
            toast("Đang tải bản mới...");
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

    // ---------- Tai xuong ----------
    private final HashMap<Long, String[]> dmJobs = new HashMap<>();
    private boolean dmReceiverOn = false;
    private boolean askedNotif = false;

    private static String fileNameFromCd(String cd) {
        if (cd == null) return null;
        try {
            Matcher m = Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) return Uri.decode(m.group(1).trim().replace("\"", ""));
            m = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) { }
        return null;
    }

    private static String fileNameFromUrl(String url) {
        if (url == null) return null;
        try {
            String p = Uri.parse(url).getLastPathSegment();
            if (p != null && !p.isEmpty()) return p;
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean isGenericMime(String m) {
        return m == null || m.isEmpty() || m.equals("application/octet-stream")
                || m.equals("binary/octet-stream") || m.equals("application/force-download");
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0 && i < name.length() - 1) ? name.substring(i + 1).toLowerCase() : "";
    }

    private static String fixName(String n, String mime) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "download";
        if (extOf(n).isEmpty() && !isGenericMime(mime)) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) n = n + "." + ext;
        }
        return n;
    }

    private static String resolveName(String url, String cd, String mime) {
        String n = fileNameFromCd(cd);
        if (n == null || n.isEmpty()) n = fileNameFromUrl(url);
        String u = fileNameFromUrl(url);
        if (n != null && n.toLowerCase().endsWith(".bin") && u != null
                && !extOf(u).isEmpty() && !u.toLowerCase().endsWith(".bin")) n = u;
        return fixName(n, mime);
    }

    // Loai MIME theo duoi tep. Tranh octet-stream: MediaStore se tu them ".bin".
    private static String resolveMime(String name, String mime) {
        if (!isGenericMime(mime)) return mime;
        String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name));
        if (t != null) return t;
        if (extOf(name).equals("apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    // ---------- Cai APK sau khi tai xong ----------
    private boolean resumed = false;
    private Uri pendingInstallUri = null;
    private String pendingInstallName = null;

    private void promptInstall(final Uri uri, final String name) {
        if (uri == null) return;
        runOnUiThread(() -> {
            pendingInstallUri = uri;
            pendingInstallName = name;
            if (resumed) showInstallDialog();
        });
    }

    private void showInstallDialog() {
        if (pendingInstallUri == null) return;
        final Uri uri = pendingInstallUri;
        final String name = pendingInstallName;
        pendingInstallUri = null;
        new AlertDialog.Builder(this)
                .setTitle("Đã tải xong")
                .setMessage(name)
                .setPositiveButton("Cài đặt", (d, w) -> installApk(uri, name))
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void installApk(Uri uri, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                // Can cap quyen "cai ung dung khong ro nguon"; quay lai se hien lai hop cai dat.
                pendingInstallUri = uri;
                pendingInstallName = name;
                toast("Bật \"Cho phép từ nguồn này\" rồi quay lại");
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Không mở được trình cài đặt. Mở tệp trong thư mục Downloads.");
        }
    }

    private class Sink {
        OutputStream os;
        Uri uri;
        File file;

        Sink(String name, String mime) throws Exception {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                os = getContentResolver().openOutputStream(uri);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                file = new File(dir, name);
                int i = 1;
                String base = name, ext = "";
                int dot = name.lastIndexOf('.');
                if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
                while (file.exists()) file = new File(dir, base + " (" + (i++) + ")" + ext);
                os = new FileOutputStream(file);
            }
        }

        void finish() throws Exception {
            os.close();
            if (uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
            }
        }

        void abort() {
            try { os.close(); } catch (Exception ignored) { }
            try {
                if (uri != null) getContentResolver().delete(uri, null, null);
                if (file != null) file.delete();
            } catch (Exception ignored) { }
        }
    }

    private void ensureDmReceiver() {
        if (dmReceiverOn) return;
        dmReceiverOn = true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                String[] job = dmJobs.remove(id);
                if (job == null) return;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id));
                int status = -1, reason = 0;
                if (cur != null) {
                    if (cur.moveToFirst()) {
                        status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    }
                    cur.close();
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    toast("Đã tải: " + job[3]);
                    if (job[3].toLowerCase().endsWith(".apk")) {
                        promptInstall(dm.getUriForDownloadedFile(id), job[3]);
                    }
                } else {
                    toast("Tải lỗi (mã " + reason + "), thử cách khác...");
                    streamDownload(job[0], job[1], job[2], job[3], job[4]);
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    // Tai truc tiep theo luong, ghi thang xuong dia (khong qua RAM). Dung khi DownloadManager loi.
    private void streamDownload(final String url, final String cookieUnused, final String ua,
                                final String name0, final String mime0) {
        new Thread(() -> {
            Sink sink = null;
            try {
                String cur = url;
                HttpURLConnection c = null;
                for (int hop = 0; hop < 8; hop++) {
                    c = (HttpURLConnection) new URL(cur).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    String ck = CookieManager.getInstance().getCookie(cur);
                    if (ck != null) c.setRequestProperty("Cookie", ck);
                    if (ua != null) c.setRequestProperty("User-Agent", ua);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                        c.disconnect();
                        continue;
                    }
                    break;
                }
                if (c.getResponseCode() != 200) {
                    toast("Tải thất bại (HTTP " + c.getResponseCode() + ")");
                    return;
                }
                String name = name0;
                String cdName = fileNameFromCd(c.getHeaderField("Content-Disposition"));
                if (cdName != null && !cdName.isEmpty()) name = fixName(cdName, c.getContentType());
                String mime = resolveMime(name, mime0 != null && !isGenericMime(mime0) ? mime0 : c.getContentType());
                sink = new Sink(name, mime);
                toast("Đang tải: " + name);
                InputStream in = c.getInputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) sink.os.write(buf, 0, n);
                in.close();
                sink.finish();
                toast("Đã lưu vào Downloads: " + name);
                if (name.toLowerCase().endsWith(".apk")) promptInstall(sink.uri, name);
            } catch (Exception e) {
                if (sink != null) sink.abort();
                toast("Tải thất bại");
            }
        }).start();
    }

    private void enqueueHttp(String url, String ua, String name, String mime, boolean fromPopup) {
        try {
            ensureDmReceiver();
            String cookie = CookieManager.getInstance().getCookie(url);
            String m = resolveMime(name, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(m);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (ua != null) req.addRequestHeader("User-Agent", ua);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            long id = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            dmJobs.put(id, new String[]{url, cookie, ua, name, m});
            toast("Đang tải: " + name);
        } catch (Exception e) {
            streamDownload(url, null, ua, name, mime);
        }
        if (fromPopup) root.post(this::closePopup);
    }

    private void handleDownload(WebView w, String url, String ua, String cd, String mime) {
        toast("Bắt đầu tải...");
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
        if (url.startsWith("blob:")) {
            String name = resolveName(null, cd, mime);
            // Doc tung khoi 1 MB va gui sang Java, ghi thang xuong dia: tep lon khong het RAM.
            String js = "(async function(){var u='" + esc(url) + "';var name=window.__lastName||'" + esc(name) + "';window.__lastName=null;"
                    + "try{var b=window.__blobs&&window.__blobs[u];if(b){delete window.__blobs[u];}"
                    + "else{var r=await fetch(u);b=await r.blob();}"
                    + "if(!AndroidDl.begin(name,b.type||'" + esc(mime == null ? "" : mime) + "'))return;"
                    + "var CH=1048576;for(var o=0;o<b.size;o+=CH){"
                    + "var bytes=new Uint8Array(await b.slice(o,o+CH).arrayBuffer());var s='';"
                    + "for(var i=0;i<bytes.length;i+=32768){s+=String.fromCharCode.apply(null,bytes.subarray(i,i+32768));}"
                    + "if(!AndroidDl.chunk(btoa(s)))return;}"
                    + "AndroidDl.end();}catch(e){AndroidDl.fail();}})();";
            expectingBlob = true;
            w.evaluateJavascript(js, null);
            return;
        }
        if (url.startsWith("data:")) {
            new DlBridge().saveInternal(url, mime, resolveName(null, cd, mime));
            return;
        }
        if (!url.startsWith("http")) return;
        lastUa = ua; lastMime = mime; lastCd = cd; lastFromPopup = (w == popup);
        // Tai ngay trong trang (cookie + tieu de nhu trinh duyet that) de tranh bi chan 403.
        String js = "(async function(){var u='" + esc(url) + "';var started=false;try{"
                + "var r=await fetch(u,{credentials:'include'});if(!r.ok){AndroidDl.fallback(u);return;}"
                + "var cd=r.headers.get('content-disposition')||'';var ty=(r.headers.get('content-type')||'').split(';')[0];"
                + "if(!AndroidDl.beginHttp(u,cd,ty))return;started=true;var rd=r.body.getReader();"
                + "for(;;){var x=await rd.read();if(x.done)break;var v=x.value;"
                + "for(var o=0;o<v.length;o+=786432){var p=v.subarray(o,o+786432);var s='';"
                + "for(var i=0;i<p.length;i+=32768){s+=String.fromCharCode.apply(null,p.subarray(i,i+32768));}"
                + "if(!AndroidDl.chunk(btoa(s)))return;}}"
                + "AndroidDl.end();}catch(e){if(started)AndroidDl.fail();else AndroidDl.fallback(u);}})();";
        expectingBlob = true;
        w.evaluateJavascript(js, null);
    }

    private String lastUa, lastMime, lastCd;
    private boolean lastFromPopup;

    // Du phong: DownloadManager voi ten/MIME da giai quyet.
    private void downloadHttpFallback(final String url, final String ua, String cd, final String mime, final boolean fromPopup) {
        final String name = resolveName(url, cd, mime);
        // Ten khong co duoi: doc tieu de that cua phan hoi de lay ten dung.
        if (extOf(name).isEmpty() || name.toLowerCase().endsWith(".bin")) {
            new Thread(() -> {
                String n = name, m = mime;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    String ck = CookieManager.getInstance().getCookie(url);
                    if (ck != null) c.setRequestProperty("Cookie", ck);
                    if (ua != null) c.setRequestProperty("User-Agent", ua);
                    if (c.getResponseCode() == 200) {
                        String cn = fileNameFromCd(c.getHeaderField("Content-Disposition"));
                        if (isGenericMime(m)) m = c.getContentType();
                        n = resolveName(c.getURL().toString(), c.getHeaderField("Content-Disposition"), m);
                        if (cn == null && extOf(n).isEmpty()) n = name;
                    }
                    c.disconnect();
                } catch (Exception ignored) { }
                enqueueHttp(url, ua, n, m, fromPopup);
            }).start();
            return;
        }
        enqueueHttp(url, ua, name, mime, fromPopup);
    }

    private class DlBridge {
        private Sink blobSink;
        private String blobName;

        @JavascriptInterface
        public void fail() {
            expectingBlob = false;
            if (blobSink != null) { blobSink.abort(); blobSink = null; }
            toast("Không đọc được tệp");
        }

        @JavascriptInterface
        public boolean begin(String name, String type) {
            if (!expectingBlob) return false;
            expectingBlob = false;
            try {
                blobName = fixName(name, type);
                blobSink = new Sink(blobName, resolveMime(blobName, type));
                toast("Đang lưu: " + blobName);
                return true;
            } catch (Exception e) {
                toast("Lưu tệp thất bại");
                return false;
            }
        }

        @JavascriptInterface
        public boolean beginHttp(String url, String cd, String type) {
            if (!expectingBlob) return false;
            expectingBlob = false;
            try {
                String t = isGenericMime(type) ? lastMime : type;
                blobName = resolveName(url, cd, t);
                blobSink = new Sink(blobName, resolveMime(blobName, t));
                toast("Đang tải: " + blobName);
                return true;
            } catch (Exception e) {
                toast("Lưu tệp thất bại");
                return false;
            }
        }

        @JavascriptInterface
        public void fallback(String url) {
            expectingBlob = false;
            downloadHttpFallback(url, lastUa, lastCd, lastMime, lastFromPopup);
        }

        @JavascriptInterface
        public boolean chunk(String b64) {
            if (blobSink == null) return false;
            try {
                blobSink.os.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                blobSink.abort();
                blobSink = null;
                toast("Lưu tệp thất bại");
                return false;
            }
        }

        @JavascriptInterface
        public void end() {
            if (blobSink == null) return;
            try {
                blobSink.finish();
                toast("Đã lưu vào Downloads: " + blobName);
                if (blobName.toLowerCase().endsWith(".apk")) promptInstall(blobSink.uri, blobName);
                if (popup != null) root.post(MainActivity.this::closePopup);
            } catch (Exception e) {
                blobSink.abort();
                toast("Lưu tệp thất bại");
            }
            blobSink = null;
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
                name = fixName(name, type);
                Sink sk = new Sink(name, resolveMime(name, type));
                sk.os.write(bytes);
                sk.finish();
                toast("Đã lưu vào Downloads: " + name);
                if (name.toLowerCase().endsWith(".apk")) promptInstall(sk.uri, name);
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
        resumed = false;
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
        resumed = true;
        showInstallDialog();
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
