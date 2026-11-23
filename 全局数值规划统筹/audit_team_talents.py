"""枚举 APK 随包天赋、星档与友军动作。用法：py audit_team_talents.py 游戏.apk 输出.json。"""
import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / ".tools/lua-qa"), str(ROOT / ".tools/offline-pylibs"),
                str(ROOT / "app端代码/local-patch")]
from apk_table_tools import ApkAssets
from lupa.luajit21 import LuaRuntime


def array(table):
    return [table[i] for i in range(1, len(table) + 1)] if table else []


def classify(action):
    """按 Formation 的实际动作分支筛选友军效果。序号使用 Lua 的一基约定。"""
    a = [None, *action, *([None] * 10)]
    op = a[1]
    if op == 35:
        return "attribute.all" if a[9] == 1 else "attribute.others"
    if op == 27 and a[8] != 2:
        return "sourceAttribute.others"
    if op in (23, 24):
        return "heal.all"
    if op == 29:
        return "sp.all"
    if op == 31 and a[3] == 2:
        return "heal.others"
    if op == 43 and a[5] == 2:
        return "hpCost.all"
    if (op == 51 and a[3] == 2) or (op == 52 and a[4] == 2):
        return "shield.all"
    if op == 76 and a[3] == 2:
        return "spLimit.others"
    if op == 14 and a[2] == 2:
        return "cleanse.others"
    return None


def damage_property(action):
    """从友方属性动作提取伤害、元素伤害或技能威力字段；减伤有独立编号。"""
    kind = classify(action)
    if kind in ("attribute.all", "attribute.others"):
        prop = action[2]
    elif kind == "sourceAttribute.others":
        prop = action[4]
    else:
        return None
    if prop in (22, 23, 59, 60, 106, 111):
        return prop
    if 34 <= prop <= 41 or 51 <= prop <= 58 or 71 <= prop <= 80 or 91 <= prop <= 100:
        return prop
    return None


def compound_damage(species_rows, rules, label):
    """关联队友增伤与同一物种天赋的其他事件，保留行动者、目标和分支的核验依据。"""
    families = {}
    groups = []
    descriptions = {}
    for species in species_rows:
        bases = [base for base in species["baseIds"] if base > 0]
        candidates = {base + rank for base in bases for rank in species["offsets"] if base + rank in rules}
        candidates.update(p["id"] for p in species["awaken"] if p["id"] in rules)
        damage_ids = []
        for rid in sorted(candidates):
            row = rules[rid]
            if any(damage_property(array(row[f"action{i}".encode()])) for i in range(1, 5)):
                damage_ids.append(rid)
        if not damage_ids:
            continue
        awake_ids = [p["id"] for p in species["awaken"] if p["id"] in rules]
        groups.append(dict(species=species["sid"], name=species["name"], talent=species["talent"],
                           families=bases, offsets=species["offsets"], awaken=species["awaken"],
                           teamDamageRules=damage_ids))
        for base in bases + awake_ids:
            members = families.setdefault(base, set())
            if base in rules:
                members.add(base)
            if base in bases:
                members.update(base + rank for rank in species["offsets"] if base + rank in rules)
                # 百位编号的玩家天赋原表另有说明用基础至十档。实际可携带的档仍以物种 offsets 为准。
                if base % 100 == 0:
                    members.update(base + rank for rank in range(11) if base + rank in rules)
    details = {}
    for rid in sorted(set().union(*families.values())):
        row = rules[rid]
        word_id = row[b"description"] or 0
        if word_id:
            descriptions[word_id] = label(word_id)
        actions = [array(row[f"action{i}".encode()]) for i in range(1, 5)]
        details[rid] = dict(id=rid, event=row[b"event"], mode=row[b"mode"], repeated=row[b"repeated"],
                            wordId=word_id, textValues=(row[b"splitdes"] or b"").decode(),
                            conditions=[array(row[b"condition1"]), array(row[b"condition2"])], actions=actions,
                            teamDamageSlots={i:prop for i,act in enumerate(actions,1)
                                             if (prop:=damage_property(act))})
    return dict(species=groups, families={k:sorted(v) for k,v in sorted(families.items())},
                rules=list(details.values()), texts=descriptions)


def scan(apk):
    assets = ApkAssets(apk)
    lua = LuaRuntime(encoding=None)
    def read(name):
        return lua.execute(assets.read(f"lua64_database_{name}.lua.bytes"))
    species = read("jingling_proto")
    rules = dict(read("character_proto").items())
    rules.update(dict(read("character2_proto").items()))
    words = read("word1_proto")
    def label(word_id):
        word = words[word_id] if word_id else None
        return word[b"data1"].decode() if word and word[b"data1"] else ""
    catalog = json.loads(assets.zip.read("assets/pokemon-local/elf/character.json"))
    training = json.loads(assets.zip.read("assets/pokemon-local/elf/training.json"))["species"]
    allowed = set(catalog["validTriggers"])
    if set(rules) != allowed or catalog["rules"] or not catalog["identityTrigger"]:
        raise ValueError("被动目录或显式映射已变化，请对照 ElfCharacterRules 复核接入路径")
    op_counts = Counter()
    rows = {}
    texts = {}
    for rid, rule in sorted(rules.items()):
        actions = [array(rule[f"action{i}".encode()]) for i in range(1, 5)]
        op_counts.update(a[0] for a in actions if a)
        effects = {str(i):kind for i,a in enumerate(actions,1) if a and (kind:=classify(a))}
        if not effects:
            continue
        word_id = rule[b"description"] or 0
        if word_id:
            texts[word_id] = label(word_id)
        rows[rid] = dict(id=rid, wordId=word_id, event=rule[b"event"], mode=rule[b"mode"],
                         repeated=rule[b"repeated"], conditions=[array(rule[b"condition1"]),array(rule[b"condition2"])],
                         actions=actions, teamActionSlots=effects)
    families = defaultdict(lambda:dict(species=[], rules=set()))
    linked = set()
    missing = defaultdict(list)
    species_rows = []
    for sid,r in sorted(species.items()):
        local = training.get(str(sid), {})
        base_ids = local.get("character", catalog["species"].get(str(sid), []))
        feature_level = local.get("featureslv",catalog["featuresLv"].get(str(sid),0))
        max_star = r[b"maxstar"] or 0
        offsets = sorted({star if star<10 else feature_level for star in range(1,max(1,max_star)+1)})
        all_ids = set()
        for base in base_ids:
            if base<=0:
                continue
            family = families[base]
            family["species"].append(sid)
            for offset in offsets:
                rid = base+offset
                if rid not in allowed:
                    missing[(sid,base)].append(offset)
                    continue
                family["rules"].add(rid)
                all_ids.add(rid)
        passive = local.get("passive",[])
        source_passive = array(r[b"skill3"])
        if source_passive == [0]:
            source_passive = []
        if len(source_passive) % 2 or any(trigger not in allowed for trigger in source_passive[1::2]):
            raise ValueError(f"{sid} 原始 skill3 含无效觉醒档位或触发编号：{source_passive}")
        if passive != source_passive:
            raise ValueError(f"{sid} 离线觉醒被动与随包 skill3 不一致：{passive} / {source_passive}")
        awakes = [{"awaken":passive[i],"id":passive[i+1]} for i in range(0,len(passive),2)]
        all_ids.update(p["id"] for p in awakes if p["id"] in allowed)
        linked.update(all_ids)
        species_rows.append(dict(sid=sid,name=r[b"bz"].decode(),talent=label(r[b"charactername"]),
                                 maxStar=max_star,offsets=offsets,
                                 baseIds=base_ids,awaken=awakes,teamRules=sorted(all_ids.intersection(rows))))
    family_rows = [dict(base=base,species=sorted(set(v["species"])),rules=sorted(v["rules"]))
                   for base,v in sorted(families.items())]
    def effect_counts(ids):
        return dict(sorted(Counter(kind for rid in ids if rid in rows
                                   for kind in rows[rid]["teamActionSlots"].values()).items()))
    summary = dict(species=len(species_rows), families=len(family_rows), validRules=len(allowed),
                   speciesWithAwakenPassives=sum(bool(r["awaken"]) for r in species_rows),
                   awakenPassivePairs=sum(len(r["awaken"]) for r in species_rows),
                   speciesLinkedValidRules=len(linked), alliedRules=len(rows),
                   speciesLinkedAlliedRules=len(linked.intersection(rows)),
                   speciesWithAlliedActions=sum(bool(r["teamRules"]) for r in species_rows),
                   alliedActionCounts=effect_counts(rows),speciesLinkedAlliedActionCounts=effect_counts(linked))
    with apk.open("rb") as f:
        checksum=hashlib.file_digest(f,"sha256").hexdigest()
    damage = compound_damage(species_rows, rules, label)
    summary["speciesWithTeamDamage"] = len(damage["species"])
    summary["linkedTeamDamageRules"] = len({i for item in damage["species"] for i in item["teamDamageRules"]})
    return dict(apk=apk.name,sha256=checksum,summary=summary,opcodeCounts=dict(sorted(op_counts.items())),
                species=species_rows,families=family_rows,rules=list(rows.values()),texts=texts,
                unresolvedOffsets=[dict(sid=s,base=b,offsets=o) for (s,b),o in missing.items()],
                compoundDamage=damage)


def write(path,report):
    # 每个物种和规则各占一行，以便比较后续包的配置差异。
    parts=[]
    for key,value in report.items():
        dump=lambda obj: json.dumps(obj,ensure_ascii=False,separators=(",",":"),allow_nan=False)
        if isinstance(value,list):
            content="[\n"+",\n".join("    "+dump(row) for row in value)+"\n  ]"
        elif key=="compoundDamage":
            fields = ["    "+dump(k)+": [\n"+",\n".join("      "+dump(row) for row in v)+"\n    ]"
                      if isinstance(v,list) else "    "+dump(k)+": "+dump(v) for k,v in value.items()]
            content="{\n"+",\n".join(fields)+"\n  }"
        elif key=="texts":
            content=json.dumps(value,ensure_ascii=False,indent=2,sort_keys=True)
        else:
            content=dump(value)
        parts.append("  "+dump(key)+": "+content)
    path.write_text("{\n"+",\n".join(parts)+"\n}\n",encoding="utf8")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk",type=Path)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    result=scan(args.apk)
    write(args.output,result)
    print(json.dumps(result["summary"],ensure_ascii=False))

