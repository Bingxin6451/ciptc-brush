#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ciptc_brush.py — 中国知识产权远程教育平台(ciptc.org.cn) 一键刷课脚本

两种登录方式:
  1) 账号密码: python3 ciptc_brush.py --username 手机号 [--password 密码]
     脚本自动弹出验证码图片给用户看, 输入验证码后自动登录并刷完全部课程。
  2) cookie:   python3 ciptc_brush.py --cookie 'JSESSIONID=...; ...'

其他参数:
  --skip-class 电技    跳过班级名包含该子串的班
  --only-class 物联    只刷班级名包含该子串的班
  --target-pct 0.8     目标时长=课程总时长x80%(默认)
  --target-min 80      或直接指定分钟(优先)
  --dry-run            只统计不提交
  --verifycode XXXX    自动填验证码(测试用, 平时省略则交互输入)
"""

import argparse
import getpass
import hashlib
import html as ihtml
import http.cookiejar
import os
import platform
import random
import re
import ssl
import subprocess
import sys
import time
import urllib.parse
import urllib.request

DEFAULT_BASE = "http://suining.ciptc.org.cn"
SSO = "https://sso.ciptc.org.cn"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
IMG_DIR = os.path.expanduser("~/.ciptc_brush")
CONFIG_FILE = os.path.join(IMG_DIR, "config.ini")

USAGE = """
══════════════════════ 使用说明 (Windows / 安卓通用) ══════════════════════

【是什么】
自动刷课工具：输一次账号密码和验证码，自动登录并刷完你所有班级的所有
课程，学习时长与知识点覆盖率自动补满，课程得分直接 100 分。

【使用】(约 1 分钟)
1. 打开工具
2. 输入账号(手机号)和密码(密码是明文显示的)
3. 看弹出的验证码图片，输入 4 位验证码（看不清点图片换一张）
4. 点「登录并一键刷课」(安卓) / 选 "2" 回车 (Windows)
5. 等它跑完，出现"全部完成"即刷完
6. 各课程 100 分即通过；成绩可在平台「我的课程 → 查看学习情况」核对

【登录方式】
- 账号密码登录：普通人用这个（推荐）
- Cookie 登录：会复制 Cookie 的人用，整串粘贴即可
- 只查看学习情况：只看分数学时，不做任何修改

【隐私说明】
- 工具完全本地运行，只连接学习平台官方服务器(suining/sso/resource.ciptc.org.cn)，
  不经过任何第三方，账号密码不上传、不记录到别处。
- 账号密码一次性使用，用完即弃，不保存到本地。
- 代码开源，可自行审查或抓包验证。

【常见问题】
- 登录失败：核对账号密码；验证码过期就点图换新再输。
- Windows 弹窗一闪而过：右键以管理员身份运行。
- 杀毒软件报毒：自签名脚本属误报，加入信任区即可。
- 默认刷全部班级全部课程，无需手动选。
════════════════════════════════════════════════════════════════════════
"""


def load_config():
    """读取本地配置(username/password), 免重复输入"""
    cfg = {}
    try:
        for line in open(CONFIG_FILE, encoding="utf-8"):
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    except FileNotFoundError:
        pass
    return cfg


def save_config(username, password):
    os.makedirs(IMG_DIR, exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        f.write(f"username={username}\npassword={password}\n")

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stdin.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ---------------- 全局 opener(cookiejar) ----------------
_CJ = http.cookiejar.CookieJar()
# sso.ciptc.org.cn 证书链不完整, urllib 严格校验会失败, 跳过证书验证
_HTTPS = urllib.request.HTTPSHandler(context=ssl._create_unverified_context())
_OPENER = urllib.request.build_opener(_HTTPS, urllib.request.HTTPCookieProcessor(_CJ))


def http_get(url, referer=None, raw=False):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept": "*/*",
        "Referer": referer or DEFAULT_BASE + "/",
    })
    with _OPENER.open(req, timeout=25) as r:
        data = r.read()
        return data if raw else data.decode("utf-8", "replace")


def http_post(url, data, content_type="application/x-www-form-urlencoded", referer=None, raw=False):
    req = urllib.request.Request(url, data=data if isinstance(data, bytes) else data.encode("utf-8"),
                                 headers={"User-Agent": UA, "Content-Type": content_type,
                                          "Referer": referer or url})
    with _OPENER.open(req, timeout=25) as r:
        out = r.read()
        return out if raw else out.decode("utf-8", "replace")


# ---------------- 登录: cookie 模式 ----------------
def load_cookie_string(cookie_str):
    """把用户粘贴的 Cookie 头注入 cookiejar (覆盖 suining/sso 两域)"""
    from http.cookies import SimpleCookie
    sc = SimpleCookie()
    sc.load(cookie_str.replace("; ", ";"))
    n = 0
    for name, morsel in sc.items():
        ck = http.cookiejar.Cookie(
            version=0, name=name, value=morsel.value,
            port=None, port_specified=False,
            domain=".ciptc.org.cn", domain_specified=True, domain_initial_dot=True,
            path="/", path_specified=True, secure=False, expires=None,
            discard=True, comment=None, comment_url=None, rest={}, rfc2109=False)
        _CJ.set_cookie(ck)
        n += 1
    return n


# ---------------- 登录: 账号密码模式 ----------------
def show_image(path):
    """弹验证码图片给用户看"""
    try:
        if sys.platform == "darwin":
            subprocess.Popen(["open", path])
        elif os.name == "nt":
            os.startfile(path)
        else:
            subprocess.Popen(["xdg-open", path])
    except Exception:
        print(f"验证码图片已保存: {path}, 请自行打开查看")


def login_password(base, username, password, auto_code=None):
    """账号密码登录, 成功后 cookiejar 内已有 suining/sso 会话"""
    ts = int(time.time())
    ref = urllib.parse.quote(f"{base}/login", safe="")
    login_url = f"{SSO}/account/login.shtml?t={ts}&referer={ref}"
    html = http_get(login_url)
    ph = re.search(r'name="pagehash"[^>]*value="([^"]*)"', html)
    if not ph:
        print("[FAIL] 登录页解析失败(未找到pagehash)")
        return False
    pagehash = ph.group(1)
    hf = re.search(r'name="referer"[^>]*value="([^"]*)"', html)
    hidden_ref = ihtml.unescape(hf.group(1)) if hf else f"{base}/login"
    show_vc = re.search(r'name="showverifycode"[^>]*value="([^"]*)"', html)
    # 该平台登录页始终显示验证码(showverifycode初始为空, 由JS动态置1)
    need_vc = True
    import random as _rnd

    os.makedirs(IMG_DIR, exist_ok=True)
    ocr = None
    try:
        import ddddocr
        ocr = ddddocr.DdddOcr(show_ad=False)
    except Exception:
        pass
    for attempt in range(1, 6):
        code = ""
        if need_vc:
            cap_url = f"{SSO}/csi_verifycode.php?pagehash={pagehash}&rand={_rnd.randint(10000, 99999)}"
            img_path = os.path.join(IMG_DIR, f"verify_{ts}_{attempt}.png")
            img = http_get(cap_url, referer=login_url, raw=True)
            with open(img_path, "wb") as f:
                f.write(img)
            show_image(img_path)
            # 1) 尝试自动OCR+服务端预校验
            if ocr:
                try:
                    auto = ocr.classification(img).strip()
                    vcd = int(time.time() * 1000)
                    pre = http_post(f"{SSO}/csi_verifycode.php?curtime={vcd}",
                                    urllib.parse.urlencode({"action": "check", "pagehash": pagehash,
                                                            "verifycode": auto}),
                                    referer=login_url)
                    if "succ" in pre:
                        code = auto
                        print(f"[*] 自动识别验证码: {code}")
                except Exception:
                    pass
            # 2) 自动失败则人工输入
            if not code:
                code = (auto_code or input(f"请输入验证码(第{attempt}次, 不区分大小写): ")).strip()
        body = urllib.parse.urlencode({
            "username": username,
            "userpass": password,
            "verifycode": code,
            "referer": hidden_ref,
            "pagehash": pagehash,
            "subencp": hashlib.md5(password.encode("utf-8")).hexdigest(),
            "cookietime": "on",
        })
        post_url = f"{SSO}/account/login.shtml?ct={ts}"
        resp = http_post(post_url, body, referer=login_url)
        # 登录成功的标志: 响应带 wx_bind 跳转token 或 cookiejar 已有 ssoa_55a3_auth
        tm = re.search(r'token(?:%3D|=)([0-9a-f]+)', resp)
        auth = any(c.name == "ssoa_55a3_auth" for c in _CJ)
        if tm:
            http_get(f"{base}/login?token={tm.group(1)}")
        elif auth:
            http_get(f"{base}/home/student/index")
        chk = http_get(f"{base}/home/student/nowLearn?_={int(time.time() * 1000)}")
        if "selecthours" in chk and "_learn_first_courseware" in chk:
            print("[OK] 登录成功")
            return True
        if auth:
            print("[FAIL] 已认证但课程列表加载失败, 请检查账号是否有在学课程")
            return False
        err = " | ".join(re.findall(r'class="[^"]*err_info[^"]*"[^>]*>\s*([^<]{2,60})', resp))
        err = err or "账号/密码/验证码有误"
        print(f"[FAIL] 第{attempt}次登录失败: {err}")
        if auto_code:
            print("[FAIL] 自动验证码错误, 停止重试")
            return False
    return False


# ---------------- 1. 课程列表(nowLearn接口) ----------------
def parse_nowlearn(html_src):
    blocks = []
    shs = [(m.start(), m.group(1)) for m in re.finditer(r"selecthours/(\d+)", html_src)]
    if not shs:
        return blocks
    course_re = re.compile(
        r"<dl[^>]*>(.*?)</dl>\s*<div class=\"course-actions\">"
        r"[^>]*?onclick=\"_learn_first_courseware\((\d+)\)\"", re.S)
    for i, (pos, classid) in enumerate(shs):
        end = shs[i + 1][0] if i + 1 < len(shs) else len(html_src)
        seg = html_src[pos:end]
        head = html_src[max(0, pos - 800):pos]
        nm = re.search(r'<h3[^>]*title="([^"]+)"', head)
        cname_blk = ihtml.unescape(nm.group(1)).strip() if nm else f"班级{classid}"
        courses = []
        for cm in course_re.finditer(seg):
            cname = ihtml.unescape(re.sub(r"<[^>]+>", "", cm.group(1)))
            cname = re.sub(r"\s+", "", cname)
            cname = re.split(r"学习时间|我的得分|已学", cname)[0].strip()
            courses.append((int(cm.group(2)), cname))
        seen, uniq = set(), []
        for c in courses:
            if c[0] not in seen:
                seen.add(c[0])
                uniq.append(c)
        blocks.append((cname_blk, classid, uniq))
    return blocks


# ---------------- 2. 课件 cwid ----------------
def get_cwids(base, eplanid):
    url = f"{base}/home/student/course/learn/{eplanid}?_={int(time.time() * 1000)}"
    html_src = http_get(url)
    return sorted({int(x) for x in re.findall(r"courseware/enter/\d+/(\d+)", html_src)})


# ---------------- 3. 课件目录路径 ----------------
def get_course_path(base, eplanid, cwid):
    html_src = http_get(f"{base}/home/student/course/courseware/enter/{eplanid}/{cwid}")
    m = re.search(r"(?:src=|url\()[\"']?(http://resource\.ciptc\.org\.cn/pri/courses/[^\"'?#\s]+)", html_src)
    if not m:
        return None
    p = m.group(1)
    p = p[:p.rfind("/") + 1] if "/index" in p else p
    if not p.endswith("/"):
        p = p[:p.rfind("/") + 1]
    scheme = p[:p.find("://") + 3]
    return scheme + re.sub(r"/+", "/", p[len(scheme):])


# ---------------- 4. 课程配置 ----------------
def load_cfg(course_path):
    try:
        xml = http_get(course_path + "video_course_cfg_g.xml",
                       referer=course_path + "index.html")
    except Exception as e:
        print(f"    [!] 配置获取失败: {e}")
        return None
    if "manifest" not in xml:
        return None
    chapters, knps = [], {}
    for m in re.finditer(r"<item\s+([^>]*)>", xml):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        ch = attrs.get("id")
        if not ch or not attrs.get("video-entry"):
            continue
        chapters.append({"id": ch, "time": int(float(attrs.get("time", "0")))})
    for m in re.finditer(r"<tag\s+([^>]*)/>", xml):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        if attrs.get("action") == "know":
            knps[attrs.get("id")] = int(float(attrs.get("time", "0")))
    return {"chapters": chapters, "knps": knps}


# ---------------- 5. SCORM RTE ----------------
def scorm_stat(base, eplanid, cwid):
    xml = http_get(f"{base}/home/scorm/rte?cmd=stat&uid=null&cwid={cwid}&eplanid={eplanid}")
    items = {}
    for m in re.finditer(r"<item\s+([^>]*)/>", xml):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        items[a["id"]] = a
    return items


def scorm_submit(base, eplanid, cwid, token, subtype, chid, session_time, timepoint, knpid=None):
    knp_xml = f'<item id="{knpid}" score="100" time="0" />' if knpid else ""
    body = (f'<request lastAccess="{chid}" sessionTime="{session_time}" '
            f'timepoint="{timepoint}">{knp_xml}</request>')
    url = (f"{base}/home/scorm/rte?cmd=submit&token={token}&uid=null&cwid={cwid}"
           f"&eplanid={eplanid}&subtype={subtype}&postchid={chid}")
    return http_post(url, body, content_type="text/xml")


# ---------------- 6. 单门课刷 ----------------
def brush_course(base, eplanid, cwid, target_pct, target_min, dry_run):
    path = get_course_path(base, eplanid, cwid)
    if not path:
        print(f"    [!] 未找到课件路径, 跳过 (ep={eplanid}, cw={cwid})")
        return None
    cfg = load_cfg(path)
    if not cfg or not cfg["chapters"]:
        print(f"    [!] 非视频课件/无配置, 跳过 ({path})")
        return None
    total = sum(c["time"] for c in cfg["chapters"])
    target = int(target_min * 60) if target_min else int(total * target_pct)
    st = scorm_stat(base, eplanid, cwid)
    have_time = sum(int(float(v.get("time", "0"))) for k, v in st.items() if not k.endswith("_know"))
    have_knps = {k for k, v in st.items() if k.endswith("_know") and v.get("complete") == "1"}
    need_time = max(0, target - have_time)
    need_knps = [k for k in cfg["knps"] if k not in have_knps]
    print(f"    章节x{len(cfg['chapters'])} 总时长{total//60}min 目标{target//60}min 已有{have_time//60}min "
          f"知识点{len(have_knps)}/{len(cfg['knps'])}")
    if need_time <= 0 and not need_knps:
        print("    [OK] 已达标, 无需提交")
        return {"target": target, "have": have_time, "knps": len(cfg["knps"])}
    if dry_run:
        print(f"    [DRY] 需补 {need_time}s 时间 + {len(need_knps)} 个知识点 (未提交)")
        return None
    remaining = need_time
    token = 1
    for i, c in enumerate(cfg["chapters"]):
        have_ch = int(float(st.get(c["id"], {}).get("time", "0")))
        share = min(c["time"] - have_ch, int(c["time"] / total * need_time)) if total else 0
        if i == len(cfg["chapters"]) - 1:
            share = remaining
        share = max(0, min(share, remaining))
        if share > 0:
            remaining -= share
            r = scorm_submit(base, eplanid, cwid, token, "ntime", c["id"], share, int(c["time"] * 1e6))
            ok = 'success="true"' in r
            print(f"    ntime {c['id']} +{share}s -> {'OK' if ok else 'FAIL: ' + r[:120]}")
            token += 1
    for k in need_knps:
        chid = next((c["id"] for c in cfg["chapters"] if k.startswith(c["id"])), "ch_01")
        r = scorm_submit(base, eplanid, cwid, token, "nknow", chid, 1, int(cfg["knps"][k] * 1e3), knpid=k)
        ok = 'success="true"' in r
        print(f"    nknow {k} -> {'OK' if ok else 'FAIL: ' + r[:120]}")
        token += 1
    st2 = scorm_stat(base, eplanid, cwid)
    have2 = sum(int(float(v.get("time", "0"))) for k, v in st2.items() if not k.endswith("_know"))
    knps2 = len({k for k, v in st2.items() if k.endswith("_know") and v.get("complete") == "1"})
    print(f"    -> 提交后: 时间{have2//60}min 知识点{knps2}/{len(cfg['knps'])}")
    return {"target": target, "have": have2, "knps": knps2}


# ---------------- 7. 汇总 ----------------
def show_summary(base, classid):
    try:
        html_src = http_get(f"{base}/home/student/userhourcert/selecthours/{classid}")
        txt = ihtml.unescape(re.sub(r"<[^>]+>", " ", html_src))
        txt = re.sub(r"[ \t]+", " ", txt)
        rows = re.findall(r"([\u4e00-\u9fa5A-Za-z0-9（）()、·\- ]+?)\s+(\d+\.\d)\s*分\s+(\d+)\s*学时", txt)
        for name, score, hour in rows:
            print(f"    {name.strip()} | {score}分 | {hour}学时")
        if "都已经获得学时" in txt:
            print("    [全部课程已达标]")
    except Exception as e:
        print(f"    [!] 汇总页获取失败: {e}")


def _pause():
    """Windows 双击运行时, 结尾停住窗口"""
    try:
        if sys.stdin and sys.stdin.isatty():
            input("\n按回车键退出...")
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser(description="ciptc 刷课")
    ap.add_argument("--cookie", default="")
    ap.add_argument("--cookie-file", default="")
    ap.add_argument("--username", default="")
    ap.add_argument("--password", default="")
    ap.add_argument("--verifycode", default="", help="测试用, 平时省略(交互输入)")
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--only-class", default="")
    ap.add_argument("--skip-class", default="")
    ap.add_argument("--target-pct", type=float, default=0.8)
    ap.add_argument("--target-min", type=float, default=0)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    # ---- 无命令行参数: 弹出交互菜单(Windows双击直接可用) ----
    has_cli = any([args.cookie, args.cookie_file, args.username, args.password,
                   args.verifycode, args.only_class, args.skip_class, args.target_min])
    if not has_cli:
        while True:
            print("=" * 52)
            print("    ciptc 平台一键刷课工具 (全自动刷完所有课程)")
            print("=" * 52)
            print("  [1] 使用 Cookie 登录")
            print("  [2] 使用账号密码登录 (自动弹出验证码图片)")
            print("  [3] 只查看学习情况, 不提交 (dry-run)")
            print("  [4] 查看使用说明")
            print("-" * 52)
            try:
                choice = input("请选择 [1/2/3/4]: ").strip() or "2"
            except (EOFError, KeyboardInterrupt):
                sys.exit(0)
            if choice == "4":
                print(USAGE)
                continue
            break
        if choice == "1":
            ck = input("请粘贴 Cookie 内容 (或输入 cookies.txt 文件路径): ").strip().strip('"')
            if not ck:
                print("Cookie 不能为空"); _pause(); sys.exit(1)
            if os.path.isfile(ck):
                ck = open(ck, encoding="utf-8", errors="replace").read().strip()
            args.cookie = ck
        elif choice == "3":
            args.dry_run = True
        elif choice not in ("1", "2", "3"):
            print("无效选择"); _pause(); sys.exit(1)

    # ---- 登录 ----
    if args.cookie:
        n = load_cookie_string(args.cookie.strip())
        print(f"[OK] 已注入 {n} 个 cookie")
    elif args.cookie_file:
        ck = open(args.cookie_file, encoding="utf-8").read().strip()
        n = load_cookie_string(ck)
        print(f"[OK] 已注入 {n} 个 cookie")
    else:
        # 一次性使用: 账号密码只在本次运行内存中, 不保存到本地
        user = args.username
        pwd = args.password
        if not user:
            try:
                user = input("账号(手机号): ").strip()
            except (EOFError, KeyboardInterrupt):
                sys.exit(0)
        if not pwd:
            pwd = input("密码(明文显示): ")
        if not user or not pwd:
            print("账号/密码不能为空"); _pause(); sys.exit(1)
        print("[*] 正在登录...")
        if not login_password(args.base, user, pwd, args.verifycode or None):
            _pause(); sys.exit(1)

    # ---- 拉课程列表, 验证登录 ----
    print("[1/3] 获取课程列表 (nowLearn) ...")
    idx = http_get(f"{args.base}/home/student/nowLearn?_={int(time.time() * 1000)}")
    blocks = parse_nowlearn(idx)
    if not blocks:
        print("未解析到课程, 检查登录状态/cookie是否有效")
        sys.exit(1)

    for bname, classid, courses in blocks:
        if args.only_class and args.only_class not in bname:
            continue
        if args.skip_class and args.skip_class in bname:
            print(f"== {bname} (classid={classid}) [跳过] ==")
            continue
        print(f"== {bname} (classid={classid}) {len(courses)}门 ==")
        for ep, cname in courses:
            print(f"  ● {cname} (eplanid={ep})")
            cwids = get_cwids(args.base, ep)
            if not cwids:
                print("    [!] 未找到课件")
                continue
            for cw in cwids:
                brush_course(args.base, ep, cw, args.target_pct, args.target_min, args.dry_run)
        show_summary(args.base, classid)
    print("全部完成。")
    if not has_cli:
        _pause()


if __name__ == "__main__":
    main()
