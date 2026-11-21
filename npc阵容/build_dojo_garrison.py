"""从原道馆地图的最后一个守卫组投影驻防NPC。"""
import json
import sys
from pathlib import Path

source, stage_file, target = map(Path, sys.argv[1:])
read = lambda name: json.loads((source / (name + ".json")).read_bytes())
gyms, monsters, skills, teams = map(read, ("gyms", "guaiwu", "skill", "guaiwuteam"))
stages = json.loads(stage_file.read_bytes())["stages"]
output = {}
for key, row in stages.items():
    gym = gyms[key]
    group, x, y, formation, roster = row[3][-1]
    assert list(gym["monster"].values())[-3] == group
    actors = []
    for enemy, position, sprite, type1, type2, quality, skill in roster:
        base = monsters[str(enemy)]
        assert base["sprite"] == sprite
        actor = {k:v for k,v in base.items() if k in ("physical_defense","dodge_chance","hit_chance","hp_max","special_attack","physical_attack","crit_chance","damage_bonus","speed","element_provide","toughness","special_defense")}
        actor.update(enemy=enemy,position=position,sprite=sprite,type1=type1,type2=type2,quality=quality,skill=skill,targetLimit=skills[str(skill)]["targetnum"])
        actors.append(actor)
    output[key] = dict(group=group,scene=row[0],level=row[1],groupLevel=teams[str(group)].get("level",0),formation=formation,actors=actors,
                       instructions1=list(gym["instructions1"].values()),instructions2=gym["instructions2"])
assert len(output)==48 and all(len(entry["actors"])==6 for entry in output.values())
target.write_text(json.dumps(output,separators=(",",":")),encoding="utf-8")