"""导出巢穴原怪物，在指定难度下按原系数换算六维面板。"""
import json
import sys
from decimal import Decimal
from pathlib import Path


def export(source):
    def read(name):
        return json.loads((source / (name + ".json")).read_bytes())

    dens, monsters, species = map(read, ("den", "denmaster", "jingling"))
    result = {str(tier): {} for tier in (1, 2, 3)}
    for den in dens.values():
        ids = {enemy for key in ("monster1", "monster2", "monster3") for enemy in den[key].values()}
        for tier, groups in result.items():
            ratio = Decimal(str(den["difficulty" + tier]["1"]))
            for enemy in ids:
                stats = monsters[str(enemy)].copy()
                elf = species[str(stats["sprite"])]
                for key in ("hp_max", "physical_attack", "special_attack", "physical_defense", "special_defense", "speed"):
                    stats[key] = int(stats[key] * ratio)
                stats.update(enemy=enemy, position=1, type1=elf["attribute1"], type2=elf["attribute2"],
                             quality=elf["quality"], skill=elf["showskill"]["1"])
                group = {"level": 1, "formation": 501, "actors": [stats]}
                if str(enemy) in groups and groups[str(enemy)] != group:
                    raise ValueError((tier, enemy, ratio))
                groups[str(enemy)] = group
    return result


if __name__ == "__main__":
    Path(sys.argv[2]).write_text(json.dumps(export(Path(sys.argv[1])), sort_keys=True, separators=(",", ":")),
                                 encoding="utf-8")
