"""提取饲育屋蛋组、原种族性别、孵化属性、光环与互动配置。"""
import json
import sys
from pathlib import Path

source, target = map(Path, sys.argv[1:])

def read(table):
    return json.loads((source / (table + ".json")).read_bytes())

variables = read("variable")
data = {
    "species": {key: [row["male"], row["egg"], row["all_up"], row["assess"]] for key, row in read("jingling").items()},
    "groups": {key: [row["pest"]["2"], row["petid"]["2"]] for key, row in read("eggingparty").items()},
    "pets": read("pet"),
    "interactions": read("interaction"),
    "love": {key: row["architectureid"] for key, row in read("cwintimacy").items()},
    "poolWeights": {str(i): variables[str(4211+i)]["data6"] for i in range(1, 5)},
    "auras": {key: row["aureole_Library"] for key, row in read("pet_aureole_level").items()},
}
levels = read("pet_up")
assert set(levels) == {str(level) for level in range(1, 121)}
data["growthGate"] = read("leading")["1179"]["gatelevel"]
wash_limit = variables["4211"]
data["washLimit"] = {"base": wash_limit["data1"], "extra": wash_limit["data2"],
                     "item": wash_limit["data6"]["2"], "cost": wash_limit["data6"]["3"]}
data["washAptitude"] = [list(variables[str(i)]["data6"].values()) for i in (4208, 4209, 4210)]
data["washAuras"] = {k: row["Probability"] for k, row in read("pet_washing").items()}
data["talentSlots"] = {k: [row["num"], list(row["stations"].values())] for k, row in read("pet_talent").items()}
data["growth"] = {}
for level in range(1, 121):
    row = levels[str(level)]
    config = {"love": row["close_level"], "star": row["star_num"]}
    for source_field in ("cost1", "stat_cost"):
        if source_field not in row:
            continue
        cost = row[source_field]
        assert [cost[str(i)] for i in (1, 2, 4, 5, 7, 8)] == [1, 30011, 1, 30012, 103, 0]
        config[source_field] = [cost[str(i)] for i in (3, 6, 9)]
    if "stat_cost" in row:
        config["count"] = row["qualified_num"]
    data["growth"][str(level)] = config
assert len(data["groups"]) == len(data["pets"]) == 6
target.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
