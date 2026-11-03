"""Print LaTeX commands found in saved Lenshift chat answers (reads threads.json on stdin)."""
import json
import re
import sys

pattern = sys.argv[1] if len(sys.argv) > 1 else r"ddot|dot"
data = json.load(sys.stdin)
hits = []
for thread in data["threads"]:
    for turn in thread["turns"]:
        for m in re.finditer(r".{0,50}\\(?:" + pattern + r")\b.{0,50}", turn["answer"]):
            hits.append(m.group(0).replace("\n", " "))
print(len(hits), "hits")
for h in hits[:12]:
    print("  ", h)
