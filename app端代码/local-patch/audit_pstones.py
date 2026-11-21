"""只读核验 P 力石随包数值与固定底包一致性，不改玩家存档。"""
import argparse,json,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
from apk_table_tools import ApkAssets,decode_table,file_sha

def audit(origin):
    """核对双架构原表、108 个模板及所有合成、分解和词条引用。"""
    assets=ApkAssets(origin)
    raw=decode_table(assets.read('lua64_database_gem_proto.lua.bytes'))
    assert raw==decode_table(assets.read('lua32_database_gem_proto.lua.bytes'))
    local=json.loads((HERE/'offline/resources/elf/training.json').read_text(encoding='utf8'))
    refine=json.loads((HERE/'offline/resources/elf/pstone-refine.json').read_text(encoding='utf8'))
    assert len(raw)==len(local['gem'])==108
    rows=[];recipes=0;links=0
    for sid,row in raw.items():
        assert all(row.get(k)==v for k,v in local['gem'][sid].items()),sid
        visible=str(row.get('hecid',sid));shown=raw[visible]
        expected=[list(row['add'+str(i)].values()) for i in range(1,5) if 'add'+str(i) in row]
        assert refine['pools'][sid]==expected,sid
        for pool in expected:
            for attr in pool:
                assert str(attr) in refine['attrs'],attr
                links+=1
        result={'id':int(sid),'visibleId':int(visible),'level':row['lv'],'splitPowder':shown['powder'],'slots':len(expected)}
        if 'synthesis' in shown:
            target=raw[str(shown['synthesis'])]
            assert target['lv']==shown['lv']+1
            assert shown['cost']['1']==103 and shown['cost']['3']>0
            assert target['powder']==shown['powder']*4
            result.update(target=shown['synthesis'],ingredients=4,coins=shown['cost']['3'])
            recipes+=1
        else:assert shown['lv']==9
        rows.append(result)
    return {'originSha256':file_sha(origin),'templates':len(rows),'recipes':recipes,'poolLinks':links,'results':rows}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();report=audit(args.origin)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
