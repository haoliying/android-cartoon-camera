#!/usr/bin/env python3
"""把根目录的 USER_GUIDE.md / CHANGELOG.md 转成 APK 内置的离线 HTML。

运行：python3 docs/build_apk_docs.py
依赖：pandoc

生成：app/src/main/assets/docs/user_guide.html、changelog.html
"""
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "assets" / "docs"

CSS = """
:root { color-scheme: dark; }
html, body { margin: 0; padding: 0; background: #07070b; color: #e9eaf2; }
body { padding: 0 16px 28px; font-family: -apple-system, "Noto Sans CJK SC",
       "Source Han Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
       font-size: 15px; line-height: 1.78; -webkit-text-size-adjust: 100%; }
main { max-width: 760px; margin: 0 auto; }
h1 { font-size: 22px; color: #ff5c7a; margin: 14px 0 8px; letter-spacing: .5px; }
h2 { font-size: 18px; color: #ffffff; margin: 26px 0 8px; padding-bottom: 6px;
     border-bottom: 1px solid #232634; }
h3 { font-size: 16px; color: #dfe3ee; margin: 18px 0 6px; }
p { margin: 8px 0; }
ul, ol { margin: 8px 0; padding-left: 22px; }
li { margin: 4px 0; }
a { color: #6fb6ff; text-decoration: none; word-break: break-all; }
hr { border: none; border-top: 1px solid #1e2130; margin: 22px 0; }
table { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 14px; }
th:first-child, td:first-child { width: 30%; }
th, td { border: 1px solid #2a2d3a; padding: 8px 10px; text-align: left; vertical-align: top; }
th { background: #151722; color: #ffffff; font-weight: 600; }
td { background: #0c0d13; }
code { background: #151722; color: #ffd0a6; padding: 1px 5px; border-radius: 4px;
       font-size: 13px; word-break: break-all; }
strong { color: #ffffff; }
"""

TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{title}</title>
<style>{css}</style>
</head>
<body><main>
{fragment}
</main></body>
</html>
"""


def markdown_to_fragment(path: pathlib.Path) -> str:
    return subprocess.run(
        ["pandoc", str(path), "-f", "gfm", "-t", "html5"],
        check=True, capture_output=True, text=True,
    ).stdout


def build(md_name: str, html_name: str, title: str) -> None:
    fragment = markdown_to_fragment(ROOT / md_name)
    # 应用内的相对链接指向内置 HTML，而不是 Markdown 文件
    fragment = fragment.replace('href="CHANGELOG.md"', 'href="changelog.html"')
    fragment = fragment.replace('href="USER_GUIDE.md"', 'href="user_guide.html"')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / html_name).write_text(
        TEMPLATE.format(title=title, css=CSS, fragment=fragment), encoding="utf-8"
    )
    print("wrote", OUT / html_name)


if __name__ == "__main__":
    build("USER_GUIDE.md", "user_guide.html", "呆瓜相机 · 操作手册")
    build("CHANGELOG.md", "changelog.html", "呆瓜相机 · 版本更新说明")
