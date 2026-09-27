"""Generates the splash animation: app/src/main/res/drawable/splash_anvil*.xml.

The hammer strikes the anvil; eight sparks fly out of the strike, snake down both sides of the anvil and light up as
the letters of "ForgeGen" under it, which cool from the sparks' colours to the anvil's steel (2.3.0-3). The system
plays this animated vector before the app runs, so it cannot be random itself: VARIANTS copies with lightly different
spark routes are generated, and the app picks the one for the next start (MainActivity, setSplashScreenTheme).

Usage (from the repository root):
    pip install fonttools uharfbuzz brotli
    # Roboto Black, e.g. from the npm package @fontsource/roboto: files/roboto-latin-900-normal.woff
    python3 tools/splash/gen_splash.py --font roboto-latin-900-normal.woff [--preview-json splash_data.json]

The 288 x 288 viewport is the splash icon; the system shows only its middle circle of 192 dp (the icon mask), so
everything stays within r = 96 around (144, 144). The timings and curves match the preview the owner approved.
"""
import argparse
import io
import json
import math
import os
import sys

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

TEXT = "ForgeGen"
CX, CY, R = 144.0, 144.0, 96.0
VARIANTS = 6

# The strike (hammer, anvil, flash, rays) is scaled down and lifted to make room for the word.
SCENE_S = 0.80
SCENE_PIVOT = (144.0, 45.0)
SCENE_TY = 5.0
TEXT_W = 110.0     # ink width of the word
BASELINE = 213.0   # y of its baseline

LETTER_COLORS = ["#FFC857", "#4FE3FF", "#B06CFF", "#FFC857", "#FFC857", "#B06CFF", "#4FE3FF", "#FFC857"]
STEEL = [(0, "#FF8CC4F5"), (0.5, "#FF3F86CF"), (1, "#FF24508F")]

# Milliseconds (the strike lands at 380).
SPARK_START, SPARK_END, TRAIL_LAG, COOL = 385, 860, 90, 320
TOTAL_MS = SPARK_END + 60 + 420  # the word's glow is the last to fade


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def scene(p):
    x, y = p
    return (SCENE_PIVOT[0] + (x - SCENE_PIVOT[0]) * SCENE_S,
            SCENE_PIVOT[1] + (y - SCENE_PIVOT[1]) * SCENE_S + SCENE_TY)


def anvil(p):
    """The anvil group of the drawing: translate (139, 166.25), pivot (5, 50), scale 0.85."""
    x, y = p
    return ((x - 5) * 0.85 + 5 + 139, (y - 50) * 0.85 + 50 + 166.25)


# ---------------------------------------------------------------------------------------------------------------------
# The word


def load_font(path):
    font = TTFont(path)
    font.flavor = None  # HarfBuzz reads no WOFF: shape the same font as plain sfnt data
    raw = io.BytesIO()
    font.save(raw)
    return TTFont(io.BytesIO(raw.getvalue())), raw.getvalue()


def letters_for(path):
    font, raw = load_font(path)
    hbfont = hb.Font(hb.Face(hb.Blob(raw)))
    buf = hb.Buffer()
    buf.add_str(TEXT)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {"kern": True, "liga": False})
    gs = font.getGlyphSet()
    order = font.getGlyphOrder()
    glyphs, x = [], 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        glyphs.append((order[info.codepoint], x + pos.x_offset, pos.y_offset))
        x += pos.x_advance
    xs = []
    for name, gx, _ in glyphs:
        bp = BoundsPen(gs)
        gs[name].draw(bp)
        xs += [bp.bounds[0] + gx, bp.bounds[2] + gx]
    k = TEXT_W / (max(xs) - min(xs))
    left = CX - TEXT_W / 2 - min(xs) * k
    letters = []
    for i, (name, gx, gy) in enumerate(glyphs):
        pen = SVGPathPen(gs, ntos=fmt)
        gs[name].draw(TransformPen(pen, (k, 0, 0, -k, left + gx * k, BASELINE - gy * k)))  # y down
        bp = BoundsPen(gs)
        gs[name].draw(bp)
        x0, y0, x1, y1 = bp.bounds
        box = [left + (x0 + gx) * k, BASELINE - y1 * k, left + (x1 + gx) * k, BASELINE - y0 * k]
        letters.append({"ch": TEXT[i], "d": pen.getCommands(), "box": [round(v, 2) for v in box]})
    return letters


# ---------------------------------------------------------------------------------------------------------------------
# The sparks' routes


def catmull_rom(points, alpha=0.5):
    """Centripetal Catmull-Rom through the points, as one path of cubic Béziers."""
    pts = [points[0]] + points + [points[-1]]
    d = f"M{fmt(points[0][0])},{fmt(points[0][1])}"
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]

        def tj(ti, a, b):
            return ti + max(math.dist(a, b), 1e-6) ** alpha

        t0 = 0.0
        t1 = tj(t0, p0, p1)
        t2 = tj(t1, p1, p2)
        t3 = tj(t2, p2, p3)
        m1 = [((p1[j] - p0[j]) / (t1 - t0) - (p2[j] - p0[j]) / (t2 - t0) + (p2[j] - p1[j]) / (t2 - t1)) * (t2 - t1)
              for j in range(2)]
        m2 = [((p2[j] - p1[j]) / (t2 - t1) - (p3[j] - p1[j]) / (t3 - t1) + (p3[j] - p2[j]) / (t3 - t2)) * (t2 - t1)
              for j in range(2)]
        d += (f" C{fmt(p1[0] + m1[0] / 3)},{fmt(p1[1] + m1[1] / 3)} {fmt(p2[0] - m2[0] / 3)},{fmt(p2[1] - m2[1] / 3)}"
              f" {fmt(p2[0])},{fmt(p2[1])}")
    return d


def mulberry32(seed):
    """The preview's seeded random numbers (JavaScript's mulberry32), bit for bit."""
    state = [seed & 0xFFFFFFFF]

    def imul(a, b):
        return (a * b) & 0xFFFFFFFF

    def rnd():
        a = (state[0] + 0x6D2B79F5) & 0xFFFFFFFF
        state[0] = a
        t = imul(a ^ (a >> 15), 1 | a)
        t = ((t + imul(t ^ (t >> 7), 61 | t)) & 0xFFFFFFFF) ^ t
        return ((t ^ (t >> 14)) & 0xFFFFFFFF) / 4294967296

    return rnd


def inside_mask(p, r=92.0):
    dx, dy = p[0] - CX, p[1] - CY
    d = math.hypot(dx, dy)
    return p if d <= r else (CX + dx * r / d, CY + dy * r / d)


def sides_and_centers(letters):
    impact = scene((140.0, 138.5))
    centers = [((l["box"][0] + l["box"][2]) / 2, (l["box"][1] + l["box"][3]) / 2) for l in letters]
    mid = sum(c[1] for c in centers) / len(centers)
    tl, tr = scene(anvil((-60, -40))), scene(anvil((40, -40)))
    horn, pl = scene(anvil((70, -10))), scene(anvil((-60, -10)))
    # Left: up out of the strike, over the anvil's end, down its side in S-bends, then along the row (F o r g).
    left = [impact, (impact[0] - 16, impact[1] - 26), (tl[0] - 8, tl[1] - 16), (tl[0] - 20, tl[1] + 4),
            (pl[0] - 6, pl[1] + 2), (pl[0] - 22, pl[1] + 18), (pl[0] - 12, mid - 4)]
    # Right, past the horn (n e G e).
    right = [impact, (impact[0] + 18, impact[1] - 24), (tr[0] + 14, tr[1] - 14), (horn[0] + 10, horn[1] - 12),
             (horn[0] - 2, horn[1] + 10), (horn[0] + 12, horn[1] + 26), (horn[0] + 4, mid - 4)]
    # How far each waypoint may move in a variant: away from the anvil, towards it, up or down.
    reach = [(0, 0, 0), (5, 5, 5), (4, 4, 3), (5, 3, 4), (4, 1, 3), (4, 4, 4), (3, 2, 2)]
    sides = {
        "left": [[round(p[0], 2), round(p[1], 2), *r] for p, r in zip(left, reach)],
        "right": [[round(p[0], 2), round(p[1], 2), *r] for p, r in zip(right, reach)],
    }
    return sides, [[round(c[0], 2), round(c[1], 2)] for c in centers], [round(v, 2) for v in impact]


def routes(sides, centers, variant):
    """Variant 0 is the base route; 1.. move each side's waypoints a little (shared by its four sparks), each spark a
    little more on its own, and vary the wave along the word. Same order of random numbers as the preview."""
    n = len(centers)
    if variant == 0:
        shared = {s: [(p[0], p[1]) for p in sides[s]] for s in ("left", "right")}
        amp = [3.0] * n
        rnd = None
    else:
        rnd = mulberry32(variant * 1013904223)

        def u(a, b):
            return a + (b - a) * rnd()

        shared = {}
        for side in ("left", "right"):
            pts = []
            for x, y, away, toward, dy in sides[side]:
                px = x + (u(-away, toward) if side == "left" else u(-toward, away))
                py = y + u(-dy, dy)
                pts.append((px, py))
            shared[side] = pts
        amp = [u(1.5, 4.5) for _ in centers]
    out = []
    for k in range(n):
        is_left = k < n / 2
        side = []
        for i, (x, y) in enumerate(shared["left" if is_left else "right"]):
            if i == 0 or rnd is None:
                side.append((x, y))
            else:
                jx = (rnd() * 2 - 1) * 1.8
                jy = (rnd() * 2 - 1) * 1.8
                side.append(inside_mask((x + jx, y + jy)))
        idx = list(range(0, k + 1)) if is_left else list(range(n - 1, k - 1, -1))
        row = [(centers[i][0], centers[i][1] + (amp[i] if (i % 2 == 0 if is_left else i % 2 == 1) else -amp[i]))
               for i in idx]
        pts = side + row
        pts[-1] = tuple(centers[k])
        out.append(catmull_rom(pts))
    return out


# ---------------------------------------------------------------------------------------------------------------------
# The drawables


def gradient(kind, attrs, stops, indent):
    pad = " " * indent
    items = "\n".join(f'{pad}        <item android:offset="{fmt(o)}" android:color="{c}" />' for o, c in stops)
    a = " ".join(f'android:{k}="{v}"' for k, v in attrs.items())
    return (f'{pad}<aapt:attr name="android:fillColor">\n'
            f'{pad}    <gradient android:type="{kind}" {a}>\n{items}\n{pad}    </gradient>\n'
            f'{pad}</aapt:attr>')


def circle(r):
    return f"M-{fmt(r)},0 a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0 a{fmt(r)},{fmt(r)} 0 1,0 -{fmt(2 * r)},0"


SCENE = """        <path
            android:name="glow"
            android:fillAlpha="0.7"
            android:pathData="M64,150 a80,80 0 1,0 160,0 a80,80 0 1,0 -160,0">
            <aapt:attr name="android:fillColor">
                <gradient android:type="radial" android:centerX="144" android:centerY="150" android:gradientRadius="80">
                    <item android:offset="0" android:color="#806A3DE8" />
                    <item android:offset="0.6" android:color="#306A3DE8" />
                    <item android:offset="1" android:color="#006A3DE8" />
                </gradient>
            </aapt:attr>
        </path>
        <group
            android:name="anvil"
            android:pivotX="5"
            android:pivotY="50"
            android:scaleX="0.85"
            android:scaleY="0.85"
            android:translateX="139"
            android:translateY="166.25">
            <path android:pathData="M-60,-40 L40,-40 Q70,-40 70,-10 Q40,-10 30,-10 Q15,-10 15,20 L30,50 L-30,50 L-15,20 Q-15,-10 -60,-10 Z">
                <aapt:attr name="android:fillColor">
                    <gradient android:type="linear" android:startX="0" android:startY="-40" android:endX="0" android:endY="50">
                        <item android:offset="0" android:color="#FF8CC4F5" />
                        <item android:offset="0.45" android:color="#FF3F86CF" />
                        <item android:offset="1" android:color="#FF24508F" />
                    </gradient>
                </aapt:attr>
            </path>
            <path
                android:pathData="M-56,-37 L40,-37"
                android:strokeWidth="3"
                android:strokeAlpha="0.8"
                android:strokeColor="#FFE3F1FF"
                android:strokeLineCap="round" />
        </group>
        <path
            android:name="flash"
            android:fillAlpha="0"
            android:fillColor="#FFFFE9A8"
            android:pathData="M114,139 a26,8 0 1,0 52,0 a26,8 0 1,0 -52,0" />
        <group android:name="sparks" android:translateX="140.0" android:translateY="138.5">
{rays}
        </group>
        <group
            android:name="hammer"
            android:pivotX="218"
            android:pivotY="116"
            android:rotation="8">
            <path android:fillColor="#FF4B3F72" android:pathData="M152,112 L218,112 A4,4 0 0 1 218,120 L152,120 Z" />
            <path android:pathData="M128,98 h24 a4,4 0 0 1 4,4 v32 a4,4 0 0 1 -4,4 h-24 a4,4 0 0 1 -4,-4 v-32 a4,4 0 0 1 4,-4 z">
                <aapt:attr name="android:fillColor">
                    <gradient android:type="linear" android:startX="124" android:startY="0" android:endX="156" android:endY="0">
                        <item android:offset="0" android:color="#FF9FB4DA" />
                        <item android:offset="1" android:color="#FF55688F" />
                    </gradient>
                </aapt:attr>
            </path>
        </group>"""

RAYS = [("M-9.9,-1.4 L-49.5,-7.0", "#4FE3FF"), ("M-8.5,-5.3 L-42.4,-26.5", "#B06CFF"),
        ("M-5.6,-8.3 L-28.0,-41.5", "#FFC857"), ("M-2.1,-9.8 L-10.4,-48.9", "#4FE3FF"),
        ("M1.7,-9.8 L8.7,-49.2", "#FFC857"), ("M5.3,-8.5 L26.5,-42.4", "#B06CFF"),
        ("M8.3,-5.6 L41.5,-28.0", "#4FE3FF"), ("M9.8,-1.7 L49.2,-8.7", "#FFC857")]
RAY_OFFSETS = [0, 15, 30, 0, 15, 30, 0, 15]


def vector_body(letters, centers, impact, route_paths, indent):
    """The drawing (a <vector>'s children and attributes), with the routes of one variant."""
    rays = "\n".join(
        f'            <path\n                android:name="ray{i}"\n                android:pathData="{d}"\n'
        f'                android:strokeColor="{c}"\n                android:strokeWidth="3.5"\n'
        f'                android:strokeLineCap="round"\n                android:trimPathEnd="0" />'
        for i, (d, c) in enumerate(RAYS))
    top = min(l["box"][1] for l in letters)
    bottom = max(l["box"][3] for l in letters)
    cy = (top + bottom) / 2
    parts = [
        f'    <group android:name="scene" android:pivotX="{fmt(SCENE_PIVOT[0])}" android:pivotY="{fmt(SCENE_PIVOT[1])}" '
        f'android:scaleX="{fmt(SCENE_S)}" android:scaleY="{fmt(SCENE_S)}" android:translateY="{fmt(SCENE_TY)}">',
        SCENE.replace("{rays}", rays),
        "    </group>",
        # The glow under the word: a circle squashed into an ellipse.
        f'    <group android:pivotX="144" android:pivotY="{fmt(cy)}" android:scaleY="0.32">',
        f'        <path\n            android:name="wordglow"\n            android:fillAlpha="0"\n'
        f'            android:pathData="M84,{fmt(cy)} a60,60 0 1,0 120,0 a60,60 0 1,0 -120,0">',
        gradient("radial", {"centerX": "144", "centerY": fmt(cy), "gradientRadius": "60"},
                 [(0, "#8C6A3DE8"), (0.6, "#336A3DE8"), (1, "#006A3DE8")], 12),
        "        </path>",
        "    </group>",
    ]
    for k, l in enumerate(letters):
        cx, lcy = centers[k]
        parts += [
            f'    <group android:name="letter{k}" android:pivotX="{fmt(cx)}" android:pivotY="{fmt(lcy)}" '
            f'android:scaleX="0.45" android:scaleY="0.45">',
            f'        <path android:name="base{k}" android:fillAlpha="0" android:pathData="{l["d"]}">',
            gradient("linear", {"startX": "0", "startY": fmt(top), "endX": "0", "endY": fmt(bottom)}, STEEL, 12),
            "        </path>",
            f'        <path android:name="hot{k}" android:fillAlpha="0" android:fillColor="#FF{LETTER_COLORS[k][1:]}" '
            f'android:pathData="{l["d"]}" />',
            "    </group>",
        ]
    for k, route in enumerate(route_paths):
        c = "#FF" + LETTER_COLORS[k][1:]
        parts += [
            f'    <path\n        android:name="trail{k}"\n        android:pathData="{route}"\n'
            f'        android:strokeColor="{c}"\n        android:strokeWidth="1.7"\n'
            f'        android:strokeLineCap="round"\n        android:trimPathEnd="0" />',
            f'    <group android:name="spark{k}" android:translateX="{fmt(impact[0])}" android:translateY="{fmt(impact[1])}">',
            f'        <path android:name="halo{k}" android:fillAlpha="0" android:fillColor="{c}" android:pathData="{circle(4.4)}" />',
            f'        <path android:name="core{k}" android:fillAlpha="0" android:fillColor="{c}" android:pathData="{circle(2.1)}" />',
            f'        <path android:name="hotspot{k}" android:fillAlpha="0" android:fillColor="#FFFFFFFF" android:pathData="{circle(0.9)}" />',
            "    </group>",
        ]
    body = "\n".join(parts)
    pad = " " * indent
    return "\n".join(pad + line if line else line for line in body.split("\n"))


VECTOR_ATTRS = ('android:width="288dp" android:height="288dp" android:viewportWidth="288" android:viewportHeight="288"')

INTERP = {
    "dq": "@android:interpolator/decelerate_quad",
    "aq": "@android:interpolator/accelerate_quad",
    "ad": "@android:interpolator/accelerate_decelerate",
    "os": "@android:interpolator/overshoot",
    "spark": "@interpolator/splash_spark",
}


def anim(prop, frm, to, start, dur, interp):
    return (f'<objectAnimator android:propertyName="{prop}" android:valueFrom="{fmt(frm)}" android:valueTo="{fmt(to)}" '
            f'android:startOffset="{start}" android:duration="{dur}" android:valueType="floatType" '
            f'android:interpolator="{INTERP[interp]}" />')


def target(name, animators):
    inner = "\n".join(f"                {a}" for a in animators)
    return (f'    <target android:name="{name}">\n        <aapt:attr name="android:animation">\n'
            f'            <set android:ordering="together">\n{inner}\n            </set>\n'
            f'        </aapt:attr>\n    </target>')


def animation_targets(route_paths):
    t = [
        target("hammer", [anim("rotation", 34, 44, 0, 140, "dq"), anim("rotation", 44, 0, 140, 240, "aq"),
                          anim("rotation", 0, 14, 380, 150, "dq"), anim("rotation", 14, 8, 530, 220, "ad")]),
        target("anvil", [anim("scaleY", .85, .8, 380, 70, "dq"), anim("scaleY", .8, .85, 450, 220, "os"),
                         anim("scaleX", .85, .875, 380, 70, "dq"), anim("scaleX", .875, .85, 450, 220, "os")]),
        target("flash", [anim("fillAlpha", 0, .9, 380, 40, "ad"), anim("fillAlpha", .9, 0, 420, 320, "dq")]),
        target("glow", [anim("fillAlpha", .7, 1, 380, 60, "ad"), anim("fillAlpha", 1, .7, 440, 400, "dq")]),
    ]
    for i, off in enumerate(RAY_OFFSETS):
        t.append(target(f"ray{i}", [anim("trimPathEnd", 0, 1, 380 + off, 200, "dq"),
                                    anim("trimPathStart", 0, 1, 450 + off, 260, "aq")]))
    fly = SPARK_END - SPARK_START
    for k, route in enumerate(route_paths):
        t.append(target(f"trail{k}", [anim("trimPathEnd", 0, 1, SPARK_START, fly, "spark"),
                                      anim("trimPathStart", 0, 1, SPARK_START + TRAIL_LAG, fly, "spark")]))
        move = (f'<objectAnimator android:propertyXName="translateX" android:propertyYName="translateY" '
                f'android:pathData="{route}" android:startOffset="{SPARK_START}" android:duration="{fly}" '
                f'android:interpolator="{INTERP["spark"]}" />')
        t.append(target(f"spark{k}", [move]))
        t.append(target(f"halo{k}", [anim("fillAlpha", 0, .35, SPARK_START, 20, "ad"),
                                     anim("fillAlpha", .35, 0, SPARK_END - 10, 70, "aq")]))
        for name in (f"core{k}", f"hotspot{k}"):
            t.append(target(name, [anim("fillAlpha", 0, 1, SPARK_START, 20, "ad"),
                                   anim("fillAlpha", 1, 0, SPARK_END - 10, 70, "aq")]))
        t.append(target(f"letter{k}", [anim("scaleX", .45, 1, SPARK_END, 260, "os"),
                                       anim("scaleY", .45, 1, SPARK_END, 260, "os")]))
        t.append(target(f"base{k}", [anim("fillAlpha", 0, 1, SPARK_END, 70, "dq")]))
        t.append(target(f"hot{k}", [anim("fillAlpha", 0, 1, SPARK_END, 40, "ad"),
                                    anim("fillAlpha", 1, 0, SPARK_END + 40, COOL - 40, "dq")]))
    t.append(target("wordglow", [anim("fillAlpha", 0, .85, SPARK_END, 60, "ad"),
                                 anim("fillAlpha", .85, 0, SPARK_END + 60, 420, "dq")]))
    return "\n".join(t)


HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by tools/splash/gen_splash.py: edit that script, not this file. -->\n"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"'


def write(path, text):
    with open(path, "w") as f:
        f.write(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", required=True, help="Roboto Black (roboto-latin-900-normal.woff or .ttf)")
    ap.add_argument("--res", default="app/src/main/res", help="the app's res directory")
    ap.add_argument("--preview-json", help="also write the geometry for the HTML preview")
    args = ap.parse_args()

    letters = letters_for(args.font)
    sides, centers, impact = sides_and_centers(letters)
    worst = max(math.dist((x, y), (CX, CY)) for l in letters
                for x in (l["box"][0], l["box"][2]) for y in (l["box"][1], l["box"][3]))
    if worst > R - 2:
        sys.exit(f"The word leaves the 192 dp circle: {worst:.1f} > {R - 2}")

    drawable = os.path.join(args.res, "drawable")
    for v in range(VARIANTS + 1):
        route_paths = routes(sides, centers, v)
        targets = animation_targets(route_paths)
        if v == 0:
            # The base: the static drawing (also the slow start's image) and its animation.
            write(os.path.join(drawable, "splash_anvil.xml"),
                  HEADER + "<!-- The splash logo: anvil, hammer, the strike's sparks and the word they form (hidden at "
                  "rest). splash_anvil_animated.xml animates it. -->\n"
                  f"<vector {NS}\n    {VECTOR_ATTRS}>\n{vector_body(letters, centers, impact, route_paths, 0)}\n</vector>\n")
            write(os.path.join(drawable, "splash_anvil_animated.xml"),
                  HEADER + "<!-- The splash (base spark routes): the hammer strikes, sparks snake down to \"ForgeGen\". "
                  f"About {TOTAL_MS} ms. -->\n"
                  f'<animated-vector {NS}\n    android:drawable="@drawable/splash_anvil">\n{targets}\n</animated-vector>\n')
        else:
            write(os.path.join(drawable, f"splash_anvil_animated_{v}.xml"),
                  HEADER + f"<!-- The splash with spark route variant {v} (MainActivity picks one for the next start). -->\n"
                  f"<animated-vector {NS}>\n"
                  f'    <aapt:attr name="android:drawable">\n        <vector {VECTOR_ATTRS}>\n'
                  f"{vector_body(letters, centers, impact, route_paths, 8)}\n        </vector>\n    </aapt:attr>\n"
                  f"{targets}\n</animated-vector>\n")
    if args.preview_json:
        with open(args.preview_json, "w") as f:
            json.dump({"letters": letters, "centers": centers, "impact": impact, "sides": sides,
                       "routes": {v: routes(sides, centers, v) for v in range(VARIANTS + 1)}}, f)
    print(f"word inside the mask (farthest corner {worst:.1f}); {VARIANTS} variants; animation {TOTAL_MS} ms")


if __name__ == "__main__":
    main()
