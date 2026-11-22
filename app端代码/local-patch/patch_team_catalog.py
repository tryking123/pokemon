"""按游戏内对应星档说明修正团队天赋的战斗表动作。"""
import copy

from apk_table_tools import decode_table, sequence


def corrected_team_rows(raw):
    """返回固定底包需要替换的既有行，保留整行的其他动作、事件及条件。"""
    source = decode_table(raw)
    changes = {}

    def action(rule_id, field):
        key = str(rule_id)
        row = copy.deepcopy(source[key])
        current = row['action1']
        assert sequence(current) == [35, 1, field, current['4'], 0, 0], rule_id
        changes[key] = row
        return current

    # AR系统的18系技能减伤使用同一组条件；妖精一支误填为55（威力）。
    for rank in range(11):
        rule_id = 63000 + rank
        assert sequence(source[str(rule_id)]['condition1']) == [20, 18, 3, 2]
        action(rule_id, 55)['3'] = 47
        # 韧性项的“全体友方”含本人，另一个减伤项明确只给其他友方。
        for family in (61100, 61200):
            action(family + rank, 13).update({'7': 0, '8': 0, '9': 1})

    # 阿尔宙斯的主说明与18个属性分支共用星档。只改实际低于文案的档位。
    fields = (71, 35, 34, 36, 74, 40, 77, 73, 76, 75, 78, 72, 79, 41, 39, 37, 80, 38)
    for element, field in enumerate(fields, 1):
        family = 30200 + element * 100
        for rank, expected in ((0, 250), (1, 250), (3, 350), (5, 450), (7, 550)):
            rule_id = family + rank
            assert sequence(source[str(rule_id)]['condition1']) == [20, element, 3, 2]
            current = action(rule_id, field)
            assert current['4'] == expected - 100, rule_id
            current['4'] = expected
    return changes
