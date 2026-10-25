package cn.ciptc.brush;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {

    static final String BASE = "http://suining.ciptc.org.cn";
    static final String SSO = "https://sso.ciptc.org.cn";
    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    TextView log;
    EditText etUser, etPwd, etCode, etCookie;
    ImageView ivCode;
    Button btnGo, btnCookie, btnRefresh;
    LinearLayout pwdBox, cookieBox;
    String pagehash = "", hiddenRef = "";
    boolean cookieMode = false;
    byte[] curCap = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        trustAllSsl();
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cm);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("ciptc 平台一键刷课工具");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView privacy = new TextView(this);
        privacy.setText("全部本地运行, 仅连接平台官方服务器(ciptc.org.cn), 账号密码不上传任何第三方。");
        privacy.setTextSize(11);
        privacy.setTextColor(0xFF666666);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, dp(2), 0, dp(8));
        root.addView(privacy);

        // ---- 账号密码区 ----
        pwdBox = new LinearLayout(this);
        pwdBox.setOrientation(LinearLayout.VERTICAL);
        etUser = mkEdit("账号（手机号）");
        etPwd = mkEdit("密码");
        etPwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout capRow = new LinearLayout(this);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        ivCode = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(130), dp(40));
        ivCode.setLayoutParams(lp);
        ivCode.setBackgroundColor(0xFFDDDDDD);
        ivCode.setScaleType(ImageView.ScaleType.FIT_XY);
        ivCode.setOnClickListener(v -> loadCaptcha());
        capRow.addView(ivCode);
        etCode = mkEdit("验证码（点图刷新）");
        capRow.addView(etCode);
        btnRefresh = new Button(this);
        btnRefresh.setText("换图");
        btnRefresh.setOnClickListener(v -> loadCaptcha());
        capRow.addView(btnRefresh);

        btnGo = new Button(this);
        btnGo.setText("登录并一键刷课");
        btnGo.setOnClickListener(v -> startBrush());

        pwdBox.addView(etUser);
        pwdBox.addView(etPwd);
        pwdBox.addView(capRow);
        pwdBox.addView(btnGo);
        root.addView(pwdBox);

        // ---- Cookie区 ----
        cookieBox = new LinearLayout(this);
        cookieBox.setOrientation(LinearLayout.VERTICAL);
        etCookie = mkEdit("粘贴 Cookie 内容");
        etCookie.setSingleLine(false);
        etCookie.setMinLines(3);
        btnCookie = new Button(this);
        btnCookie.setText("用 Cookie 一键刷课");
        btnCookie.setOnClickListener(v -> startBrush());
        cookieBox.addView(etCookie);
        cookieBox.addView(btnCookie);
        cookieBox.setVisibility(View.GONE);
        root.addView(cookieBox);

        // 模式切换
        Button btnMode = new Button(this);
        btnMode.setText("切换: 使用 Cookie 登录");
        btnMode.setOnClickListener(v -> {
            cookieMode = !cookieMode;
            cookieBox.setVisibility(cookieMode ? View.VISIBLE : View.GONE);
            pwdBox.setVisibility(cookieMode ? View.GONE : View.VISIBLE);
            btnMode.setText(cookieMode ? "切换: 使用账号密码登录" : "切换: 使用 Cookie 登录");
        });
        root.addView(btnMode);

        // ---- 日志 ----
        log = new TextView(this);
        log.setTextSize(12);
        log.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sv = new ScrollView(this);
        sv.addView(log);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(sv);

        setContentView(root);
        loadCaptcha();
        addLog("就绪。输入账号密码, 点图刷新验证码, 点\"登录并一键刷课\"。");
    }

    EditText mkEdit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(14);
        return e;
    }

    int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    void addLog(String s) {
        runOnUiThread(() -> log.append(s + "\n"));
    }

    // ---------------- 网络 ----------------
    static void trustAllSsl() {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String h, SSLSession s) { return true; }
            });
        } catch (Exception e) { e.printStackTrace(); }
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    static String urlencode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    HttpResponse http(String method, String url, String body, String ctype, String referer) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", ctype == null ? "application/x-www-form-urlencoded" : ctype);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
            }
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                c.disconnect();
                String abs = loc.startsWith("http") ? loc : new URL(new URL(url), loc).toString();
                return http(method.equals("POST") ? "GET" : method, abs, null, null, url);
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = readAll(in == null ? c.getInputStream() : in);
            c.disconnect();
            return new HttpResponse(code, data);
        } catch (Exception e) {
            if (c != null) c.disconnect();
            return new HttpResponse(-1, ("ERR:" + e).getBytes());
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static class HttpResponse {
        int code;
        byte[] data;
        HttpResponse(int c, byte[] d) { code = c; data = d; }
        String text() { try { return new String(data, "UTF-8"); } catch (Exception e) { return new String(data); } }
    }

    // ---------------- 验证码 ----------------
    void loadCaptcha() {
        new Thread(() -> {
            try {
                if (pagehash.isEmpty()) {
                    String html = http("GET", SSO + "/account/login.shtml?t=" + System.currentTimeMillis() / 1000
                            + "&referer=" + urlencode(BASE + "/login"), null, null, null).text();
                    Matcher m = Pattern.compile("name=\"pagehash\"[^>]*value=\"([^\"]*)\"").matcher(html);
                    if (m.find()) pagehash = m.group(1);
                    Matcher m2 = Pattern.compile("name=\"referer\"[^>]*value=\"([^\"]*)\"").matcher(html);
                    hiddenRef = m2.find() ? m2.group(1).replace("&amp;", "&") : BASE + "/login";
                }
                String capUrl = SSO + "/csi_verifycode.php?pagehash=" + pagehash + "&rand=" + (10000 + (int) (Math.random() * 89999));
                HttpResponse r = http("GET", capUrl, null, null, SSO + "/account/login.shtml");
                curCap = r.data;
                final Bitmap bmp = BitmapFactory.decodeByteArray(r.data, 0, r.data.length);
                if (bmp != null) runOnUiThread(() -> ivCode.setImageBitmap(bmp));
            } catch (Exception e) {
                addLog("[!] 验证码加载失败: " + e);
            }
        }).start();
    }

    // ---------------- 主流程 ----------------
    void startBrush() {
        final String user = etUser.getText().toString().trim();
        final String pwd = etPwd.getText().toString();
        final String code = etCode.getText().toString().trim();
        final String ck = etCookie.getText().toString().trim();
        new Thread(() -> {
            try {
                if (cookieMode) {
                    injectCookie(ck);
                    addLog("[OK] Cookie 已注入");
                } else {
                    if (user.isEmpty() || pwd.isEmpty()) { addLog("[!] 账号密码不能为空"); return; }
                    if (code.isEmpty()) { addLog("[!] 请先输入验证码(点图可刷新)"); return; }
                    addLog("[*] 正在登录...");
                    if (!login(user, pwd, code)) { addLog("[!] 登录失败, 检查账号/密码/验证码(验证码点图换新)"); return; }
                    addLog("[OK] 登录成功");
                }
                brushAll();
                addLog("全部完成 ✅");
            } catch (Exception e) {
                addLog("[!] 出错: " + e);
            }
        }).start();
    }

    void injectCookie(String ck) {
        try {
            for (String part : ck.split(";")) {
                int i = part.indexOf("=");
                if (i <= 0) continue;
                HttpCookie hc = new HttpCookie(part.substring(0, i).trim(), part.substring(i + 1).trim());
                hc.setDomain(".ciptc.org.cn");
                hc.setPath("/");
                CookieManager cm = (CookieManager) java.net.CookieHandler.getDefault();
                cm.getCookieStore().add(new URI("http://suining.ciptc.org.cn"), hc);
                cm.getCookieStore().add(new URI("https://sso.ciptc.org.cn"), hc);
            }
        } catch (Exception e) {
            addLog("[!] Cookie 解析失败: " + e);
        }
    }

    static class CookieHandlerHolder {
        static java.net.CookieHandler get() { return java.net.CookieHandler.getDefault(); }
    }

    boolean login(String user, String pwd, String code) {
        long ts = System.currentTimeMillis() / 1000;
        String loginUrl = SSO + "/account/login.shtml?t=" + ts + "&referer=" + urlencode(BASE + "/login");
        if (pagehash.isEmpty()) {
            String html = http("GET", loginUrl, null, null, null).text();
            Matcher m = Pattern.compile("name=\"pagehash\"[^>]*value=\"([^\"]*)\"").matcher(html);
            if (m.find()) pagehash = m.group(1);
            Matcher m2 = Pattern.compile("name=\"referer\"[^>]*value=\"([^\"]*)\"").matcher(html);
            hiddenRef = m2.find() ? m2.group(1).replace("&amp;", "&") : BASE + "/login";
        }
        String body = "username=" + urlencode(user) + "&userpass=" + urlencode(pwd)
                + "&verifycode=" + urlencode(code) + "&referer=" + urlencode(hiddenRef)
                + "&pagehash=" + urlencode(pagehash) + "&subencp=" + md5(pwd) + "&cookietime=2592000";
        HttpResponse r = http("POST", SSO + "/account/login.shtml?ct=" + ts, body, null, loginUrl);
        String resp = r.text();
        Matcher tm = Pattern.compile("token(?:%3D|=)([0-9a-f]+)").matcher(resp);
        boolean auth = false;
        try {
            CookieManager cm = (CookieManager) CookieHandlerHolder.get();
            for (HttpCookie hc : cm.getCookieStore().get(new URI("https://sso.ciptc.org.cn")))
                if (hc.getName().equals("ssoa_55a3_auth")) auth = true;
        } catch (Exception ignored) {}
        if (tm.find()) {
            http("GET", BASE + "/login?token=" + tm.group(1), null, null, null);
        } else if (auth) {
            http("GET", BASE + "/home/student/index", null, null, null);
        }
        String chk = http("GET", BASE + "/home/student/nowLearn?_=" + System.currentTimeMillis(), null, null, null).text();
        return chk.contains("selecthours") && chk.contains("_learn_first_courseware");
    }

    // ---------------- 刷课 ----------------
    static class Blk {
        String name, cid;
        List<long[]> courses = new ArrayList<>(); // {ep}
        Map<Long, String> epNames = new HashMap<>();
        String epName(long ep) { return epNames.get(ep); }
        void putName(long ep, String n) { epNames.put(ep, n); }
    }
    static class Ch { String id; int time; }

    void brushAll() {
        addLog("[1/3] 获取课程列表...");
        String idx = http("GET", BASE + "/home/student/nowLearn?_=" + System.currentTimeMillis(), null, null, null).text();
        List<Blk> blocks = parseNowLearn(idx);
        if (blocks.isEmpty()) { addLog("[!] 未解析到课程, 登录态可能失效"); return; }
        for (Blk blk : blocks) {
            addLog("== " + blk.name + " (" + blk.cid + ") " + blk.courses.size() + "门 ==");
            for (long[] cs : blk.courses) {
                long ep = cs[0];
                String cname = blk.epName(ep);
                addLog("  ● " + cname + " (eplanid=" + ep + ")");
                String learn = http("GET", BASE + "/home/student/course/learn/" + ep + "?_=" + System.currentTimeMillis(), null, null, null).text();
                Set<Long> cws = new HashSet<>();
                Matcher m = Pattern.compile("courseware/enter/\\d+/(\\d+)").matcher(learn);
                while (m.find()) cws.add(Long.parseLong(m.group(1)));
                for (long cw : cws) brushCourse(ep, cw);
            }
            String sm = http("GET", BASE + "/home/student/userhourcert/selecthours/" + blk.cid, null, null, null).text();
            Matcher m = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9（）()、·\\- ]+?)\\s+(\\d+\\.\\d)\\s*分\\s+(\\d+)\\s*学时").matcher(sm);
            while (m.find()) addLog("    " + m.group(1).trim() + " | " + m.group(2) + "分 | " + m.group(3) + "学时");
            if (sm.contains("都已经获得学时")) addLog("    [全部课程已达标]");
        }
    }

    List<Blk> parseNowLearn(String html) {
        List<Blk> out = new ArrayList<>();
        Matcher sh = Pattern.compile("selecthours/(\\d+)").matcher(html);
        List<Integer> pos = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (sh.find()) { pos.add(sh.start()); ids.add(sh.group(1)); }
        Pattern cr = Pattern.compile("<dl[^>]*>(.*?)</dl>\\s*<div class=\"course-actions\">[^>]*?onclick=\"_learn_first_courseware\\((\\d+)\\)\"", Pattern.DOTALL);
        for (int i = 0; i < pos.size(); i++) {
            int end = i + 1 < pos.size() ? pos.get(i + 1) : html.length();
            String seg = html.substring(pos.get(i), end);
            String head = html.substring(Math.max(0, pos.get(i) - 800), pos.get(i));
            Matcher nm = Pattern.compile("<h3[^>]*title=\"([^\"]+)\"").matcher(head);
            String bname = nm.find() ? nm.group(1).trim() : ("班级" + ids.get(i));
            Blk blk = new Blk();
            blk.name = bname;
            blk.cid = ids.get(i);
            Matcher cm = cr.matcher(seg);
            while (cm.find()) {
                String cname = cm.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", "");
                cname = cname.split("学习时间|我的得分|已学")[0].trim();
                long ep = Long.parseLong(cm.group(2));
                if (blk.epName(ep) == null) blk.courses.add(new long[]{ep});
                blk.putName(ep, cname);
            }
            out.add(blk);
        }
        return out;
    }

    void brushCourse(long ep, long cw) {
        String enter = http("GET", BASE + "/home/student/course/courseware/enter/" + ep + "/" + cw, null, null, null).text();
        Matcher fm = Pattern.compile("(?:src=|url\\()['\"]?(http://resource\\.ciptc\\.org\\.cn/pri/courses/[^'\"?#\\s]+)").matcher(enter);
        if (!fm.find()) { addLog("    [!] 未找到课件路径"); return; }
        String p = fm.group(1);
        p = p.substring(0, p.lastIndexOf("/") + 1);
        String cfgUrl = "http://" + p.substring(p.indexOf("resource")) + "video_course_cfg_g.xml";
        cfgUrl = cfgUrl.replaceAll("(?<!(\\w|:))//", "/");
        String cfg = http("GET", cfgUrl, null, null, p + "index.html").text();
        List<Ch> chs = new ArrayList<>();
        Map<String, Long> knps = new HashMap<>();
        Matcher im = Pattern.compile("<item\\s+([^>]*)>").matcher(cfg);
        while (im.find()) {
            String a = im.group(1);
            String id = attr(a, "id");
            String ve = attr(a, "video-entry");
            String tt = attr(a, "time");
            if (id == null || ve == null || tt == null) continue;
            Ch ch = new Ch();
            ch.id = id;
            ch.time = (int) Float.parseFloat(tt);
            chs.add(ch);
        }
        Matcher tm = Pattern.compile("<tag\\s+([^>]*)/>").matcher(cfg);
        while (tm.find()) {
            String a = tm.group(1);
            if ("know".equals(attr(a, "action"))) {
                try { knps.put(attr(a, "id"), Long.parseLong(attr(a, "time"))); } catch (Exception ignored) {}
            }
        }
        if (chs.isEmpty()) { addLog("    [!] 非视频课件"); return; }
        int total = 0;
        for (Ch c : chs) total += c.time;
        int target = (int) (total * 0.8);

        String stat = http("GET", BASE + "/home/scorm/rte?cmd=stat&uid=null&cwid=" + cw + "&eplanid=" + ep, null, null, null).text();
        Map<String, String> items = new HashMap<>();
        Matcher sm = Pattern.compile("<item\\s+([^>]*)/>").matcher(stat);
        while (sm.find()) { String a = sm.group(1); items.put(attr(a, "id"), a); }
        long haveTime = 0;
        Set<String> haveKnps = new HashSet<>();
        for (Map.Entry<String, String> e : items.entrySet()) {
            if (e.getKey().endsWith("_know")) {
                if ("1".equals(attr(e.getValue(), "complete"))) haveKnps.add(e.getKey());
            } else {
                try { haveTime += Long.parseLong(attr(e.getValue(), "time")); } catch (Exception ignored) {}
            }
        }
        addLog("    章节x" + chs.size() + " 总时长" + (total / 60) + "min 目标" + (target / 60) + "min 已有" + (haveTime / 60) + "min 知识点" + haveKnps.size() + "/" + knps.size());
        long needTime = Math.max(0, target - haveTime);
        int needK = 0;
        for (String k : knps.keySet()) if (!haveKnps.contains(k)) needK++;
        if (needTime <= 0 && needK == 0) { addLog("    [OK] 已达标"); return; }

        long remaining = needTime;
        int token = 1;
        for (int i = 0; i < chs.size(); i++) {
            Ch c = chs.get(i);
            long haveCh = 0;
            String a = items.get(c.id);
            if (a != null) { try { haveCh = Long.parseLong(attr(a, "time")); } catch (Exception ignored) {} }
            long share = Math.min(c.time - haveCh, (long) ((double) c.time / total * needTime));
            if (i == chs.size() - 1) share = remaining;
            share = Math.max(0, Math.min(share, remaining));
            if (share > 0) {
                remaining -= share;
                String xml = "<request lastAccess=\"" + c.id + "\" sessionTime=\"" + share + "\" timepoint=\"" + ((long) c.time * 1000000L) + "\"></request>";
                String r = http("POST", BASE + "/home/scorm/rte?cmd=submit&token=" + token + "&uid=null&cwid=" + cw + "&eplanid=" + ep + "&subtype=ntime&postchid=" + c.id, xml, "text/xml", null).text();
                addLog("    ntime " + c.id + " +" + share + "s -> " + (r.contains("success=\"true\"") ? "OK" : "FAIL"));
                token++;
            }
        }
        for (String k : knps.keySet()) {
            if (haveKnps.contains(k)) continue;
            String chid = "ch_01";
            for (Ch c : chs) if (k.startsWith(c.id)) { chid = c.id; break; }
            String xml = "<request lastAccess=\"" + chid + "\" sessionTime=\"1\" timepoint=\"" + (knps.get(k) * 1000) + "\"><item id=\"" + k + "\" score=\"100\" time=\"0\" /></request>";
            String r = http("POST", BASE + "/home/scorm/rte?cmd=submit&token=" + token + "&uid=null&cwid=" + cw + "&eplanid=" + ep + "&subtype=nknow&postchid=" + chid, xml, "text/xml", null).text();
            addLog("    nknow " + k + " -> " + (r.contains("success=\"true\"") ? "OK" : "FAIL"));
            token++;
        }
        String st2 = http("GET", BASE + "/home/scorm/rte?cmd=stat&uid=null&cwid=" + cw + "&eplanid=" + ep, null, null, null).text();
        long have2 = 0;
        int k2 = 0;
        Matcher s2 = Pattern.compile("<item\\s+([^>]*)/>").matcher(st2);
        while (s2.find()) {
            String a = s2.group(1);
            String id = attr(a, "id");
            if (id != null && id.endsWith("_know")) { if ("1".equals(attr(a, "complete"))) k2++; }
            else { try { have2 += Long.parseLong(attr(a, "time")); } catch (Exception ignored) {} }
        }
        addLog("    -> 提交后: 时间" + (have2 / 60) + "min 知识点" + k2 + "/" + knps.size());
    }

    static String attr(String a, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(a);
        return m.find() ? m.group(1) : null;
    }
}
