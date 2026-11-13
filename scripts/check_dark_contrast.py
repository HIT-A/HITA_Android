"""暗色模式对比度审计：values-night 资源 + Compose ColorScheme。

按 WCAG AA 判定：正文文本 4.5:1，图形元素/大字 3:1。
退出码 0 表示全部达标，1 表示存在未达标项（并逐条列出）。

用法：
    python3 scripts/check_dark_contrast.py
"""

import os, re, sys, xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
RES = os.path.join(ROOT, 'app/src/main/res')
THEME = os.path.join(ROOT, 'app/src/main/java/cn/limpu/hita/ui/design/HitaComposeTheme.kt')

# ---------- color math ----------
def parse(v):
    v = v.strip().lstrip('#')
    if len(v) == 8:
        return (int(v[2:4],16), int(v[4:6],16), int(v[6:8],16), int(v[0:2],16)/255.0)
    if len(v) == 6:
        return (int(v[0:2],16), int(v[2:4],16), int(v[4:6],16), 1.0)
    return None

def over(fg, bg):
    a = fg[3]
    return (fg[0]*a + bg[0]*(1-a), fg[1]*a + bg[1]*(1-a), fg[2]*a + bg[2]*(1-a), 1.0)

def lum(c):
    def ch(v):
        v /= 255.0
        return v/12.92 if v <= 0.04045 else ((v+0.055)/1.055)**2.4
    return 0.2126*ch(c[0]) + 0.7152*ch(c[1]) + 0.0722*ch(c[2])

def ratio(fg, bg):
    a, b = lum(fg), lum(bg)
    hi, lo = max(a,b), min(a,b)
    return (hi+0.05)/(lo+0.05)

# ---------- XML resolution (values-night wins in dark mode) ----------
def load_colors(path):
    out = {}
    if not os.path.exists(path): return out
    for c in ET.parse(path).getroot().findall('color'):
        out[c.get('name')] = (c.text or '').strip()
    return out

def load_theme_items(path):
    out = {}
    if not os.path.exists(path): return out
    for st in ET.parse(path).getroot().findall('style'):
        if st.get('name') != 'Theme.HITA': continue
        for it in st.findall('item'):
            out[it.get('name')] = (it.text or '').strip()
    return out

def build_table():
    day = load_colors(os.path.join(RES,'values/colors.xml'))
    ds  = load_colors(os.path.join(RES,'values/design_system.xml'))
    night = load_colors(os.path.join(RES,'values-night/colors.xml'))
    # dark mode: night overrides day, design_system aliases resolve through the same table
    table = dict(day); table.update(ds); table.update(night)
    # design_system.xml itself has no night variant -> aliases must be re-resolved
    return table

def resolve(name, table, depth=0):
    if depth > 12: return None
    v = table.get(name)
    if v is None: return None
    if v.startswith('@color/'):
        return resolve(v[len('@color/'):], table, depth+1)
    return v

# ---------- XML pairs ----------
# (foreground, background, min_ratio, label, category)
XML_PAIRS = [
    ('on_surface','surface',4.5,'正文 / 背景','text'),
    ('on_surface','surface_variant',4.5,'正文 / 卡片','text'),
    ('on_surface_variant','surface',4.5,'次要文字 / 背景','text'),
    ('on_surface_variant','surface_variant',4.5,'次要文字 / 卡片','text'),
    ('on_surface_hint','surface',4.5,'提示文字 / 背景','text'),
    ('surface_disabled','surface',4.5,'禁用文字 / 背景','text'),
    ('grayA5','surface',4.5,'灰字 / 背景','text'),
    ('on_primary','primary',4.5,'主按钮文字','text'),
    ('on_primary_container','primary_container',4.5,'主容器文字','text'),
    ('on_secondary_container','secondary_container',4.5,'次容器文字','text'),
    ('on_tertiary_container','tertiary_container',4.5,'三容器文字','text'),
    ('error','surface',4.5,'错误文字 / 背景','text'),
    ('on_error','error',4.5,'错误按钮文字','text'),
    ('on_error_container','error_container',4.5,'错误容器文字','text'),
    ('inverse_on_surface','inverse_surface',4.5,'反色文字','text'),
    ('primary','surface',3.0,'主色 / 背景','graphic'),
    ('outline','surface',3.0,'描边 / 背景','graphic'),
    ('outline','surface_variant',3.0,'描边 / 卡片','graphic'),
    ('outline_variant','surface',3.0,'细描边 / 背景','graphic'),
    ('outline_variant','surface_variant',3.0,'细描边 / 卡片','graphic'),
    # 季节卡片：文字直接压在季节容器色上
    ('season_spring_text','season_spring',4.5,'春卡片文字','text'),
    ('season_summer_text','season_summer',4.5,'夏卡片文字','text'),
    ('season_autumn_text','season_autumn',4.5,'秋卡片文字','text'),
    ('season_winter_text','season_winter',4.5,'冬卡片文字','text'),
    # 小组件
    ('widget_text_primary','widget_background',4.5,'小组件主文字','text'),
    ('widget_text_secondary','widget_background',4.5,'小组件次文字','text'),
    ('widget_text_secondary_dark','widget_background_dark',4.5,'小组件次文字(暗)','text'),
]

def audit_xml():
    table = build_table()
    rows, fails = [], []
    for fg, bg, mn, label, cat in XML_PAIRS:
        f, b = resolve(fg, table), resolve(bg, table)
        if not f or not b:
            rows.append((label, fg, bg, None, mn, 'SKIP', cat)); continue
        fr, br = parse(f), parse(b)
        if not fr or not br:
            rows.append((label, f, b, None, mn, 'SKIP', cat)); continue
        r = ratio(over(fr, br), br)
        ok = r >= mn
        rows.append((label, f, b, r, mn, 'OK' if ok else 'FAIL', cat))
        if not ok: fails.append(rows[-1])
    return rows, fails

# ---------- Compose ColorScheme ----------
def compose_styles():
    """Extract each style's dark branch color assignments."""
    src = open(THEME, encoding='utf-8').read()
    # palette blocks live between 'return when (style) {' and the preset function
    body = src.split('return when (style) {',1)[1].split('private fun hitaBasicColorScheme',1)[0]
    out = {}

    def grab(chunk):
        d = {}
        # alpha 变体必须先于裸 Color.White 匹配，否则 `surface = Color.White.copy(...)`
        # 会被当成纯白表面，把半透明表面算成 1:1（假失败）。
        for m in re.finditer(r'(\w+)\s*=\s*(?:if \(darkTheme\)\s*)?Color\.White\.copy\(alpha\s*=\s*if \(darkTheme\) ([\d.]+)f else [\d.]+f\)', chunk):
            d.setdefault(m.group(1), 'W%s' % m.group(2))
        for m in re.finditer(r'(\w+)\s*=\s*(?:if \(darkTheme\)\s*)?Color\(0x([0-9A-Fa-f]{6,8})\)', chunk):
            d.setdefault(m.group(1), m.group(2))
        for m in re.finditer(r'(\w+)\s*=\s*(?:if \(darkTheme\)\s*)?Color\.(White|Black)\s*(?=[,\n)])', chunk):
            d.setdefault(m.group(1), 'FFFFFFFF' if m.group(2)=='White' else 'FF000000')
        return d

    # split per style keyword
    parts = re.split(r'\n        HitaThemeStyle\.', body)
    for p in parts[1:]:
        name = p.split(' ')[0].strip()
        out.setdefault(name, grab(p))
    return out

BASE_DARK = {'surface':'FF000000','surfaceVariant':'FF121212','background':'FF000000',
             'onSurface':'FFFFFFFF','onSurfaceVariant':'FF858585','primary':'FF6BB5F7',
             'onPrimary':'FF001A33','outline':'FF938F99','outlineVariant':'FF66636B',
             'primaryContainer':'FF003355','onPrimaryContainer':'FFD0E7FF'}

COMPOSE_PAIRS = [('onSurface','surface',4.5,'正文 / 卡片','text'),
                 ('onSurface','background',4.5,'正文 / 背景','text'),
                 ('onSurfaceVariant','surface',4.5,'次要文字 / 卡片','text'),
                 ('onSurfaceVariant','surfaceVariant',4.5,'次要文字 / 变体','text'),
                 ('onSurfaceVariant','background',4.5,'次要文字 / 背景','text'),
                 ('onPrimary','primary',4.5,'主按钮文字','text'),
                 ('onPrimaryContainer','primaryContainer',4.5,'主容器文字','text'),
                 ('primary','surface',3.0,'主色 / 卡片','graphic'),
                 ('primary','background',3.0,'主色 / 背景','graphic'),
                 ('outline','surface',3.0,'描边 / 卡片','graphic'),
                 ('outline','surfaceVariant',3.0,'描边 / 变体','graphic'),
                 ('outlineVariant','surface',3.0,'细描边 / 卡片','graphic'),
                 ('outlineVariant','surfaceVariant',3.0,'细描边 / 变体','graphic')]

def audit_compose():
    styles = compose_styles()
    rows, fails = [], []
    for name, vals in styles.items():
        m = dict(BASE_DARK); m.update({k:v for k,v in vals.items() if v})
        bg = parse('#'+m['background'])
        def effective(key):
            v = m.get(key)
            if not v: return None
            if v.startswith('W'):
                white = parse('#FFFFFF'); white = (255,255,255,float(v[1:]))
                return over(white, bg)
            return over(parse('#'+v), bg)
        for fg, bkey, mn, label, cat in COMPOSE_PAIRS:
            f, b = effective(fg), effective(bkey)
            if not f or not b:
                rows.append((name,label,fg,bkey,None,mn,'SKIP',cat)); continue
            r = ratio(f, b); ok = r >= mn
            rows.append((name,label,fg,bkey,r,mn,'OK' if ok else 'FAIL',cat))
            if not ok: fails.append(rows[-1])
    return rows, fails

def main():
    xrows, xfails = audit_xml()
    crows, cfails = audit_compose()
    print("=" * 96)
    print("XML 资源（values-night 生效）")
    print("=" * 96)
    print(f"{'项目':22} {'前景':12} {'背景':18} {'比值':>6}  {'阈值':>4} 判定")
    for label, f, b, r, mn, verdict, cat in xrows:
        rs = f"{r:6.2f}" if r is not None else "     -"
        print(f"{label:22} {f:12} {b:18} {rs}  {mn:4} {verdict}")
    print()
    print("=" * 96)
    print("Compose ColorScheme（暗色分支，含 alpha 合成后的实际表面）")
    print("=" * 96)
    print(f"{'主题':12} {'项目':20} {'比值':>6}  {'阈值':>4} 判定")
    for name, label, fg, bk, r, mn, verdict, cat in crows:
        rs = f"{r:6.2f}" if r is not None else "     -"
        print(f"{name:12} {label:20} {rs}  {mn:4} {verdict}")
    print()
    print(f"XML 检查 {len(xrows)} 项，失败 {len(xfails)}")
    print(f"Compose 检查 {len(crows)} 项，失败 {len(cfails)}")
    if xfails or cfails:
        print("\n未达标项：")
        for row in xfails + cfails: print("  ", row)
        return 1
    print("\n全部通过 WCAG AA（正文 4.5:1，图形/大字 3:1）")
    return 0

sys.exit(main())
