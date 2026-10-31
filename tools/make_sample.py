"""生成一张用于验证的示例图纸（GBK 编码的 ASCII DXF）。

不是随便画几根线：墙体用带宽度多段线、门用 bulge 圆弧、标注用匿名块 + DIMENSION、
房间名是中文，正好覆盖解析器最容易出错的几条路径。
"""
import math
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "samples", "demo-floor.dxf")


class Dxf:
    def __init__(self):
        self.lines = []

    def p(self, code, value):
        self.lines.append(str(code))
        self.lines.append(str(value))

    def text(self):
        return "\n".join(self.lines) + "\n"


def wall(d, pts, width=0.24, layer="WALL", closed=False):
    d.p(0, "LWPOLYLINE")
    d.p(8, layer)
    d.p(90, len(pts))
    d.p(70, 1 if closed else 0)
    d.p(43, width)
    for x, y in pts:
        d.p(10, f"{x:.3f}")
        d.p(20, f"{y:.3f}")


def door(d, hx, hy, r, start, end, layer="DOOR"):
    d.p(0, "ARC")
    d.p(8, layer)
    d.p(10, f"{hx:.3f}")
    d.p(20, f"{hy:.3f}")
    d.p(40, f"{r:.3f}")
    d.p(50, f"{start:.3f}")
    d.p(51, f"{end:.3f}")
    d.p(0, "LINE")
    d.p(8, layer)
    d.p(10, f"{hx:.3f}")
    d.p(20, f"{hy:.3f}")
    d.p(11, f"{hx + r * math.cos(math.radians(end)):.3f}")
    d.p(21, f"{hy + r * math.sin(math.radians(end)):.3f}")


def main():
    d = Dxf()
    d.p(0, "SECTION"); d.p(2, "HEADER")
    d.p(9, "$ACADVER"); d.p(1, "AC1015")
    d.p(9, "$DWGCODEPAGE"); d.p(3, "ANSI_936")
    d.p(9, "$INSBASE"); d.p(10, "0.0"); d.p(20, "0.0"); d.p(30, "0.0")
    d.p(9, "$EXTMIN"); d.p(10, "-1.0"); d.p(20, "-1.0"); d.p(30, "0.0")
    d.p(9, "$EXTMAX"); d.p(10, "12.0"); d.p(20, "9.0"); d.p(30, "0.0")
    d.p(0, "ENDSEC")

    d.p(0, "SECTION"); d.p(2, "TABLES")
    d.p(0, "TABLE"); d.p(2, "LAYER"); d.p(70, 6)
    for name, color in (("0", 7), ("WALL", 7), ("DOOR", 3), ("WIN", 4),
                        ("DIM", 2), ("TEXT", 7), ("COL", 1)):
        d.p(0, "LAYER"); d.p(2, name); d.p(70, 0); d.p(62, color); d.p(6, "CONTINUOUS")
    d.p(0, "ENDTAB")

    d.p(0, "TABLE"); d.p(2, "STYLE"); d.p(70, 1)
    d.p(0, "STYLE"); d.p(2, "HZ"); d.p(70, 0); d.p(40, "0.0"); d.p(41, "1.0")
    d.p(50, "0.0"); d.p(71, "0"); d.p(42, "2.5"); d.p(3, "gbcbig.shx"); d.p(4, "")
    d.p(0, "ENDTAB")
    d.p(0, "ENDSEC")

    # ---- 尺寸标注的匿名块：一条尺寸线 + 两个端点短线 + 文字，基准点 (0,0)
    d.p(0, "SECTION"); d.p(2, "BLOCKS")
    d.p(0, "BLOCK"); d.p(8, "0"); d.p(2, "*D1"); d.p(70, 1)
    d.p(10, "0.0"); d.p(20, "0.0"); d.p(30, "0.0")
    d.p(0, "LINE"); d.p(8, "DIM"); d.p(10, "0.0"); d.p(20, "0.0")
    d.p(11, "8.0"); d.p(21, "0.0")
    for tx in (0.0, 8.0):
        d.p(0, "LINE"); d.p(8, "DIM"); d.p(10, f"{tx}"); d.p(20, "0.0")
        d.p(11, f"{tx - 0.15}"); d.p(21, "-0.3")
    d.p(0, "SOLID"); d.p(8, "DIM")
    d.p(10, "0.0"); d.p(20, "0.0"); d.p(11, "0.4"); d.p(21, "0.15")
    d.p(12, "0.4"); d.p(22, "-0.15"); d.p(13, "0.0"); d.p(23, "0.0")
    d.p(0, "TEXT"); d.p(8, "DIM"); d.p(10, "3.2"); d.p(20, "0.35")
    d.p(40, "0.35"); d.p(1, "8000")
    d.p(0, "ENDBLK"); d.p(8, "0")
    d.p(0, "ENDSEC")

    d.p(0, "SECTION"); d.p(2, "ENTITIES")

    # 外墙 12 x 9，带 0.24 厚度
    wall(d, [(0, 0), (12, 0), (12, 9), (0, 9)], closed=True)
    # 内墙
    wall(d, [(4.5, 0), (4.5, 5.2)])
    wall(d, [(4.5, 5.2), (12, 5.2)])
    wall(d, [(8.0, 5.2), (8.0, 9)])

    # 门（弧 + 门扇）
    door(d, 4.5, 1.2, 0.9, 0, 90)
    door(d, 9.0, 5.2, 0.9, 180, 270)
    door(d, 2.0, 0.0, 0.85, 0, 90)

    # 窗
    for x0, x1, y in ((5.6, 7.0, 0.0), (0.0, 0.0, 0.0)):
        if x0 == x1:
            continue
        d.p(0, "LINE"); d.p(8, "WIN"); d.p(10, f"{x0}"); d.p(20, f"{y - 0.06}")
        d.p(11, f"{x1}"); d.p(21, f"{y - 0.06}")
        d.p(0, "LINE"); d.p(8, "WIN"); d.p(10, f"{x0}"); d.p(20, f"{y + 0.06}")
        d.p(11, f"{x1}"); d.p(21, f"{y + 0.06}")
    for y0, y1 in ((6.4, 7.8), (1.5, 2.9)):
        d.p(0, "LINE"); d.p(8, "WIN"); d.p(10, "11.94"); d.p(20, f"{y0}")
        d.p(11, "11.94"); d.p(21, f"{y1}")
        d.p(0, "LINE"); d.p(8, "WIN"); d.p(10, "12.06"); d.p(20, f"{y0}")
        d.p(11, "12.06"); d.p(21, f"{y1}")

    # 柱子：两个圆 + 填充用的内接方
    d.p(0, "CIRCLE"); d.p(8, "COL"); d.p(10, "0.5"); d.p(20, "8.5"); d.p(40, "0.3")
    d.p(0, "POLYLINE"); d.p(8, "COL"); d.p(66, 1); d.p(70, 1)
    for px, py in ((4.2, 8.5), (4.8, 8.5), (4.8, 7.9), (4.2, 7.9)):
        d.p(0, "VERTEX"); d.p(8, "COL"); d.p(10, f"{px}"); d.p(20, f"{py}")
    d.p(0, "SEQEND"); d.p(8, "COL")

    # 房间名
    for label, x, y, h in (("客厅", 2.2, 4.2, 0.45), ("卧室", 8.1, 2.4, 0.45),
                           ("厨房", 10.0, 7.1, 0.4), ("卫生间", 6.0, 7.0, 0.35),
                           ("车库", 2.0, 7.3, 0.35), ("阳台", 9.2, 4.2, 0.32)):
        d.p(0, "TEXT"); d.p(8, "TEXT"); d.p(10, f"{x}"); d.p(20, f"{y}")
        d.p(40, f"{h}"); d.p(1, label); d.p(7, "HZ"); d.p(50, "0.0")

    # 多行说明，带字体码和换行
    d.p(0, "MTEXT"); d.p(8, "TEXT"); d.p(10, "0.4"); d.p(20, "0.6")
    d.p(40, "0.3"); d.p(41, "8.0")
    d.p(3, "{\\fSimSun|b0|i0|c134;一层平面图 1:100\\P}")
    d.p(1, "%%c20 钢筋 混凝土 C30")
    d.p(71, "1")

    # 尺寸标注（引用匿名块）
    d.p(0, "DIMENSION"); d.p(8, "DIM"); d.p(2, "*D1"); d.p(70, 1)
    d.p(10, "0.0"); d.p(20, "-1.2"); d.p(11, "8.0"); d.p(21, "-1.2")

    # 带 bulge 的圆弧墙（转角）
    d.p(0, "LWPOLYLINE"); d.p(8, "WALL"); d.p(90, 3); d.p(70, 0); d.p(43, "0.2")
    d.p(10, "8.2"); d.p(20, "9.0"); d.p(42, "-0.4142")
    d.p(10, "12.0"); d.p(20, "5.4"); d.p(42, "0")
    d.p(10, "12.0"); d.p(20, "4.6")

    d.p(0, "ENDSEC")
    d.p(0, "EOF")

    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    with open(os.path.abspath(OUT), "wb") as f:
        f.write(d.text().encode("gbk"))
    print("wrote", os.path.abspath(OUT), os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
