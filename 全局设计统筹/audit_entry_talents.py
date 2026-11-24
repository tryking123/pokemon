"""从指定 APK 审计物种固有特性的触发时点。用 --details 列出每档团队回合规则及同物种登场配对。"""
import argparse
from collections import defaultdict
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / p) for p in (".tools/lua-qa", ".tools/offline-pylibs", "app端代码/local-patch")]
from apk_table_tools import ApkAssets
from lupa.luajit21 import LuaRuntime

ENTRY = re.compile("登场|开场|入场|战斗开始|战斗开场|进入战斗|出场|战斗初始|对战开始|战斗之初|上场|开局|战斗前")


def team_action(rule_id, action):
    """008 readAssists 允许的团队动作，含梦魇例外，位置按原 Lua 数组解释。"""
    op, *args = action
    args += [None] * 7
    return (op in {23, 24, 29, 35, 82} or (op == 27 and args[6] != 2)
            or (op in {31, 51, 76} and args[1] == 2) or (op == 52 and args[2] == 2)
            or (op == 14 and args[0] == 2) or (rule_id // 100 == 270 and op == 20))


def actions(row):
    return [array(row[k]) for k in ("action1", "action2", "action3", "action4") if row[k][1]]


def array(values):
    return tuple(values[i] for i in range(1, len(values) + 1)) if values else ()


def signature(row):
    conditions = tuple(array(row["condition" + str(n)]) for n in range(1, 5))
    return row["mode"], row["repeated"], row["description"], conditions


def run(args):
    apk = ApkAssets(args.apk)
    lua = LuaRuntime()

    def table(name):
        return dict(lua.execute(apk.read(f"lua64_database_{name}_proto.lua.bytes")).items())

    species, words = table("jingling"), table("word1")
    first, second = table("character"), table("character2")
    rules = first | second
    owners, keyword_owners, no_words = defaultdict(set), set(), set()
    event2, pairs = defaultdict(set), defaultdict(set)
    entry, shared = set(), set()
    meaningful = set()
    occurrences = 0
    for sid, sp in sorted(species.items()):
        offsets = {n if n < 10 else sp["featureslv"] for n in range(1, sp["maxstar"] + 1)}
        for offset in sorted(offsets):
            ids = [b + offset for b in array(sp["character"]) if b > 0 and b + offset in rules]
            for i in ids:
                owners[i].add(sid)
                row = rules[i]
                word = words.get(row["description"])
                desc = word["data1"] if word else ""
                if not desc:
                    no_words.add(i)
                if ENTRY.search(desc):
                    keyword_owners.add(sid)
                if row["event"] == 1:
                    entry.add(sid)
                    if any(team_action(i, a) for a in actions(row)):
                        shared.add(sid)
                if row["event"] != 2:
                    continue
                team = {a for a in actions(row) if team_action(i, a)}
                if not team:
                    continue
                occurrences += 1
                event2[i].add(sid)
                # 同一物种、同一星级的实际规则，核对完整动作与全部条件。
                # mode、repeated、description 相同才能视为持有者的常驻光环配对。
                siblings = [j for j in ids if rules[j]["event"] == 1 and row["description"]
                            and signature(row) == signature(rules[j])
                            and team <= set(actions(rules[j]))]
                if siblings:
                    pairs[i].add(sid)
                if args.details:
                    print(sid, sp["bz"], offset, i, "动作", sorted(team), "登场配对", siblings)
            if ids:
                meaningful.add(sid)
    print("配置物种/有效物种", len(species), len(meaningful))
    print("character/character2/合并后", len(first), len(second), len(rules))
    print("物种固有有效星档编号/物种编号组合/无独立中文说明编号", len(owners),
          sum(map(len, owners.values())), len(no_words))
    print("包含登场等文字的物种", len(keyword_owners), sorted(keyword_owners))
    for label, sids in (("有后排许可的登场动作", shared), ("仅有后排过滤的登场动作", entry-shared),
                        ("只有后续事件", meaningful-entry)):
        hits = sorted(keyword_owners & sids)
        print(label, len(hits), hits)
    missing = {i: event2[i] - pairs[i] for i in event2 if event2[i] - pairs[i]}
    for label, group in (("团队事件2", event2), ("有对应登场", pairs), ("缺对应登场", missing)):
        ids = [i for i in group if group[i]]
        print(label, "规则基号", sorted({i // 100 for i in ids}), "档位编号", len(ids),
              "物种与档位组合", sum(len(group[i]) for i in ids))
    print("团队事件2检查次数（物种、不同星级偏移、规则编号）", occurrences)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--details", action="store_true")
    run(parser.parse_args())
