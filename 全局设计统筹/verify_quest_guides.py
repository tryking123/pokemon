"""按固定底包逐项执行主线点击、进度推送、领奖分支，核对所有对应引导链。"""
from argparse import ArgumentParser
from collections import Counter, defaultdict
from pathlib import Path
import json
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / p) for p in (".tools/lua-qa", ".tools/offline-pylibs", "app端代码/local-patch")]
from apk_table_tools import ApkAssets, decode_table
from patch_quest_ui import arena, body, parse, patch_task_guides, patch_task_lock, patch_task_navigation
from lupa.luajit21 import LuaRuntime
import UnityPy


def script(assets, abi, bundle, name):
    objects = UnityPy.load(assets.read(f"lua{abi}_{bundle}.ab")).objects
    return next(obj.read().m_Script.encode("utf8", "surrogateescape") for obj in objects
                if obj.type.name == "TextAsset" and obj.read().m_Name == name + ".lua")


def validate(origin, target):
    assets = target or origin
    vm = LuaRuntime(encoding=None)
    vm.execute(b"""
    callbacks={}; data_mgr={get_one=function(_,id) return missions[id] end}
    Network={AddListener=function(k,f)callbacks[k]=f end,
             Request=function(k,p) assert(k=='task_submit'); claim=p.taskid end}
    modulename=setmetatable({},{__index=function(_,k)return k end}); Layer={UIEffect=4}
    local navi={vbrocast=function(self,e,id)assert(e=='click_navi'); dest=id end}
    module_mgr={get=function(k)assert(k=='navi');return navi end,isopen=function()return false end,
                open=function(k,_,_,id)assert(k=='guide'); guide=id end}
    Event={Brocast=function(k)assert(k=='SYNC_TASK')end}
    label_popup={show=function(s)hint=s end}
    function clear() guide=nil;claim=nil;hint=nil;dest=nil end
    function setup(raw)
      callbacks={}; local m=assert(loadstring(raw))()
      local tasks={}; debug.setupvalue(m.track_task,1,tasks)
      return function(id, action, choice)
        clear()
        if action=='publish' then callbacks.sc_newtask({id=id,state=1,progress=0})
        else
          tasks[id]={id=id,state=action=='claim' and 2 or 1}
          math.random=function()return choice end
          m.track_task(id)
        end
        return {guide=guide,claim=claim,hint=hint,dest=dest}
      end
    end
    """)
    def get_steps(table, start):
        todo, entries, missing = [start], {}, set()
        while todo:
            node = todo.pop()
            if node in entries or node in missing:
                continue
            row = table[node]
            if row is None:
                missing.add(node)
                continue
            entries[node] = row
            jump = [row[key] for key in (b"next", b"to_hook", b"to_main")]
            if row[b"in_scene"] is not None:
                jump.append(row[b"in_scene"][b"prev"])
            todo.extend(value for value in jump if isinstance(value, (float, int)) and value)
        return entries, sorted(missing)
    mission_key = "lua64_database_mission_proto.lua.bytes"
    rows = decode_table(origin.read(mission_key))
    assert rows == decode_table(assets.read(mission_key))
    assert rows == decode_table(assets.read(mission_key.replace("64", "32")))
    vm.globals().missions = vm.execute(origin.read(mission_key))
    guide_data = script(origin, 64, "module_guide", "guide_data")
    guide = vm.execute(guide_data)
    results, by_root, by_finish = {}, defaultdict(set), defaultdict(list)
    counts = Counter()
    for abi in (32,64):
        assert script(assets, abi, "module_guide", "guide_data") == script(origin, abi, "module_guide", "guide_data")
        for bundle, name, patch in (("gmodel", "gtask_model", lambda b:patch_task_guides(patch_task_lock(b))),
                                    ("module_navi", "navi_view", patch_task_navigation)):
            current = script(assets, abi, bundle, name)
            baseline = script(origin, abi, bundle, name)
            if name == "navi_view":
                # 构建先在原型 13、25 叠加冒险标签和盲盒入口，再补原型 26 的任务显示。
                baseline, _ = arena.update_lua(baseline, "adventure_label")
            expected = patch(baseline)
            if target:
                assert current == expected, (abi,name,"部署内容不符")
            assert patch(expected) == expected
    main = sorted(int(k) for k, r in rows.items() if r.get("type")==1)
    assert len(main)==2105
    # 两个版本使用独立的回调表，避免一个实例覆盖另一个实例的监听。
    setup = vm.eval(b"setup")
    original = script(origin, 64, "gmodel", "gtask_model")
    for mode in ("original","candidate"):
        raw = original if mode=="original" else (script(target,64,"gmodel","gtask_model") if target
                                                  else patch_task_guides(patch_task_lock(original)))
        click = setup(raw)
        for task in main:
            finish = rows[str(task)]["finish"]
            publish = click(task, b"publish", 1)
            states = [click(task, b"click", i) for i in (range(1,4) if finish==19 else (1,))]
            claim = click(task, b"claim", 1)
            assert claim[b"claim"]==task and not claim[b"guide"] and not claim[b"dest"]
            if mode=="original":
                roots = sorted({int(s[b"guide"]) for s in states if s[b"guide"]})
                auto = publish[b"guide"]
                for root in roots + ([auto] if auto else []):
                    by_root[root].add(task)
                results[task] = {"click": roots,"auto":auto}
            else:
                assert not publish[b"guide"] and not publish[b"claim"] and not publish[b"dest"]
                assert not any(s[b"claim"] for s in states)
                if task in (10001,10002,10004):
                    assert [s[b"guide"] for s in states] == results[task]["click"]
                    counts["highlight"]+=1
                else:
                    assert all(s[b"hint"] and not s[b"guide"] for s in states)
                    dest = states[0][b"dest"]
                    by_finish[finish].append(dest.decode() if dest else "原页说明")
                    counts["manual"]+=1
            counts[mode+"Checks"] += len(states)+2
    roots = {}
    for root in sorted(by_root):
        entries,missing = get_steps(guide,root)
        assert entries
        roots[root]={"steps": sorted(entries), "missing":missing,
                     "events":sorted({r[b"event"].decode() for r in entries.values() if r[b"event"]}),
                     "locks":sorted(n for n,r in entries.items() if r[b"lock"])}
    # 保留教学仅允许指向原导航与选项，禁止业务请求和外部等待。
    for root in (4300,3600,3000):
        entries,missing=get_steps(guide,root)
        assert not missing and not roots[root]["locks"] and not roots[root]["events"]
        for row in entries.values():
            assert set(row.keys())<=set(b"mask frame next fire".split())
            if row[b"fire"]:
                assert row[b"fire"][b"name"] in (b"click_navi",b"GUIDE_GASHAPON_1",b"GUIDE_SPRITE_1")
    # 日常页面有自己的 Trace，实际按完成类型查本地跳转表，不经过 track_task。
    daily = parse(script(origin,64,"module_task","task_sub_view3"))[2]
    route_keys = {c for op,a,c,b in struct.iter_unpack("BBBB",daily[17]["code"]) if op==62 and b==19}
    assert daily[8]["numbers"]==bytes.fromhex("004c2a")  # 广告项、世界首领、快速战斗的分支。
    guided_daily = [int(k) for k,r in rows.items() if r.get("type")==4 and r["finish"] in route_keys|{38}]
    assert guided_daily==[40002,40003,40004,40006,40007,40008,40009,40010,40011,40012]
    pass_trace=parse(script(origin,64,"module_premit","premit_subview2"))[2][4]
    assert arena.ins("KSHORT",5,35) in pass_trace["code"]
    assert decode_table(script(origin,64,"languages_zh","premit"))["35"]=="任务暂未完成"
    # 执行原导航控制器，确认新入口值可达实际模块，并保留原场景及精灵资格判断。
    prefix,_,protos=parse(script(assets,64,"module_navi","navi_ctrl"))
    nav=body(protos[9])
    vm.globals().nav=vm.eval(b"function(raw)return assert(loadstring(raw)) end")(
        prefix+arena.encode(len(nav))+nav+b"\0")
    vm.execute(b"""
        local opened
        module_mgr.open=function(k)opened=k end
        elf_model={user_pkm=function()return true end}
        ghelper={check_hitrun=function()return false end}
        Event.Brocast=function(e)assert(e=='SYNC_RED')end
        for k,v in pairs({Bag='bag',Gashapon='gashapon',Pokemon='sprite',Union='union',Pandect='pandect'}) do
          opened=nil; nav(k); assert(opened==v,k)
        end
        elf_model.user_pkm=function() return false end
        ghelper.check_hitrun=function() return true end
        opened=nil;nav('Pokemon');nav('Union');nav('Pandect');assert(opened==nil)
    """)
    return {"主线任务":len(main),"原条件种类":len({rows[str(i)]["finish"] for i in main}),
            "原自动教学任务":{i:v["auto"] for i,v in results.items() if v["auto"]},
            "任务原分支":results,"原引导根节点":roots,"改后手动入口":{f:sorted(set(v)) for f,v in by_finish.items()},
            "原日常有导航":guided_daily,
            "检查":dict(counts),"目标":str(assets.zip.filename)}


if __name__ == "__main__":
    parser=ArgumentParser(description=__doc__)
    parser.add_argument("--apk",type=Path,help="直接验证已签名最终包；省略时对固定底包在内存应用补丁。")
    parser.add_argument("--details",action="store_true",help="输出逐链步骤与等待事件")
    args=parser.parse_args()
    result=validate(ApkAssets(ROOT/"app包/origin/origin.apk"), ApkAssets(args.apk) if args.apk else None)
    if not args.details:
        del result["任务原分支"]
        result["原引导根节点"]=len(result["原引导根节点"])
        result["原自动教学任务"]=len(result["原自动教学任务"])
    print(json.dumps(result,ensure_ascii=False,indent=2))
