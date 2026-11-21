"""从固定底包的 copy、怪物组、挂机怪物和装备表生成普通冒险目录。"""
import argparse
import hashlib
import json
from pathlib import Path


def build(tables, equipment):
    evidence = json.loads((tables / "npc-source-evidence.json").read_bytes())

    def read(name):
        raw = (tables / (name + ".json")).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == evidence[name]["exportSha256"], name
        return json.loads(raw)

    def combine(names):
        result = {}
        for name in names:
            rows = read(name)
            assert not (result.keys() & rows.keys()), name
            result.update(rows)
        return result

    stages = combine(("copy", "copy2"))
    groups = combine(("guaiwuteam", "guaiwuteam2"))
    actors = combine(("hangup_guaiwu", "hangup_guaiwu2", "hangup_guaiwu3"))
    species = read("jingling")
    skills = read("skill")
    gear = json.loads(equipment.read_bytes())
    assert len(gear) == 930
    stage_rows, monster_rows, gear_rows = {}, {}, {}
    assert sorted(map(int, stages)) == list(range(10001, 30001))
    for stage_id in range(10001, 30001):
        stage = stages[str(stage_id)]
        assert stage["nextid"] == stage_id + 1
        assert stage["needworld"] == (0 if stage_id < 11850 else 1 if stage_id < 15149 else 2)
        team = groups[str(stage["monster1"])]
        slots = sorted(team["team"], key=int)
        assert 1 <= len(slots) <= 6
        positions = [team["formation"][slot] for slot in slots]
        assert len(set(positions)) == len(slots) and all(1 <= pos <= 6 for pos in positions)
        row = ["S", stage_id, stage["nextid"], team["military"], stage["money"], stage["exp1"],
               stage["exp2"], len(slots)]
        for slot, position in zip(slots, positions):
            monster = team["team"][slot]
            row.extend((monster, position))
            sprite = actors[str(monster)]["sprite"]
            elf = species[str(sprite)]
            skill = elf["showskill"]["1"]
            assert str(skill) in skills
            monster_rows[monster] = ("M", monster, sprite, elf["attribute1"], elf["attribute2"], skill)
        reward = stage["bossget"]
        assert len(reward) % 3 == 0
        for i in range(1, len(reward) + 1, 3):
            kind, sid, quantity = (reward[str(i+n)] for n in range(3))
            assert kind in (1,10) and quantity > 0
            row.extend((kind, sid, quantity))
            if kind == 10:
                pos = gear[str(sid)]["position"]
                assert 1 <= pos <= 6
                gear_rows[sid] = ("E", sid, pos)
        stage_rows[stage_id] = row
    assert (len(stage_rows), len(monster_rows), len(gear_rows)) == (20000,119895,126)
    # 既有首区域目录同时列出初始装备的六个部位，均沿用原 equip 表。
    for sid in range(10011, 10017):
        gear_rows[sid] = ("E", sid, gear[str(sid)]["position"])
    lines = ["# copy_proto, guaiwuteam_proto, hangup_guaiwu_proto, jingling_proto, equip_proto (origin F010A675)"]
    for collection in (stage_rows, monster_rows, gear_rows):
        lines.extend(",".join(map(str, collection[key])) for key in sorted(collection))
    return lines


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tables", type=Path, help="已核 SHA-256 的数值表导出目录")
    parser.add_argument("equipment", type=Path, help="原始双 ABI 一致的 equip_proto JSON")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    lines = build(args.tables, args.equipment)
    if args.output.exists():
        # 已发布首区域必须与原始底表逐字段一致。
        original = set(args.output.read_text("ascii").splitlines())
        missing = original.difference(lines)
        assert not missing, next(iter(missing), "")
    args.output.write_bytes(("\n".join(lines) + "\n").encode("ascii"))
