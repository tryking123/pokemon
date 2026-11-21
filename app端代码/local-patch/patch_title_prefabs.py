"""按底包序列化布局迁入称号完整预制体，保留源文字、渐变、描边与对象引用。"""
import copy,hashlib
from patch_imported_images import clone_reader,merge_fields,object_reader,pointer

PREFIX='assets/game/assets/modules/titleshow/'
ICON='assets/game/assets/sprites/icontitle/'

def pointers(value):
    if isinstance(value,dict):
        if set(value)=={'m_FileID','m_PathID'}:yield value
        else:
            for child in value.values():yield from pointers(child)
    elif isinstance(value,(list,tuple)):
        for child in value:yield from pointers(child)

def graph(reader):
    reader=object_reader(reader);objects={};queue=[reader]
    while queue:
        obj=queue.pop(0)
        if obj.path_id in objects:continue
        objects[obj.path_id]=obj
        for p in pointers(obj.read_typetree()):
            if p['m_FileID']==0 and p['m_PathID']:
                queue.append(obj.assets_file.objects[p['m_PathID']])
    return objects

def script_key(obj):
    t=obj.read_typetree();return tuple(t[k] for k in ['m_AssemblyName','m_Namespace','m_ClassName'])

def component_key(obj):
    if obj.type.name!='MonoBehaviour':return (obj.type.name,)
    p=obj.read_typetree()['m_Script'];assert p['m_FileID']==0
    return ('MonoBehaviour',)+script_key(obj.assets_file.objects[p['m_PathID']])

def external_name(sf,id):return sf.externals[id-1].path.lower().split('/')[-1]

def canonical(reader,icons):
    """忽略对象编号及 Unity 新增默认字段，对完整局部对象图作语义比较。"""
    objs=graph(reader);ids={id:i for i,id in enumerate(objs)}
    iconpaths={object_reader(o).path_id:p for p,o in icons.container.items() if o.type.name=='Sprite'}
    ignored={'m_PropertiesHash','m_TypeName','m_RaycastPadding','m_Maskable','m_PixelsPerUnitMultiplier','m_UseSpriteMesh'}
    sf=object_reader(reader).assets_file
    def norm(v):
        if isinstance(v,dict):
            if set(v)=={'m_FileID','m_PathID'}:
                if not v['m_PathID']:return ['null']
                if v['m_FileID']==0:return ['local',ids[v['m_PathID']]]
                name=external_name(sf,v['m_FileID'])
                return ['external',name,iconpaths.get(v['m_PathID'],v['m_PathID'])]
            return {k:norm(x) for k,x in v.items() if k not in ignored}
        if isinstance(v,(list,tuple)):return [norm(x) for x in v]
        if isinstance(v,float):return round(v,5)
        return v
    result=[]
    for o in objs.values():
        t=o.read_typetree()
        if o.type.name=='RectTransform':
            # XY 由锚点、轴心和 AnchoredPosition 重建；2018 保留编辑器缓存，2020 写零。
            assert 'm_AnchoredPosition' in t and 'm_AnchorMin' in t and 'm_AnchorMax' in t
            t['m_LocalPosition']={'z':t['m_LocalPosition']['z']}
        result.append((o.type.name,norm(t)))
    return result

def add_title_prefabs(original,source,original_icons,source_icons,unity,shows):
    """迁移缺失路径，核对已有路径；不更换整个来源包或静态降级。"""
    env=unity.load(original);donor=unity.load(source);icons=unity.load(original_icons);srcicons=unity.load(source_icons)
    old={p:o for p,o in env.container.items()};src={p:o for p,o in donor.container.items()}
    sf=object_reader(next(iter(old.values()))).assets_file;dsf=object_reader(next(iter(src.values()))).assets_file
    templates={};scripts={}
    for o in list(sf.objects.values()):
        if o.type.name=='MonoScript':scripts[script_key(o)]=o
        elif o.type.name in ['GameObject','RectTransform','CanvasRenderer','MonoBehaviour']:templates.setdefault(component_key(o),o)
    srciconpaths={object_reader(o).path_id:p for p,o in srcicons.container.items() if o.type.name=='Sprite'}
    dsticonpaths={p:object_reader(o) for p,o in icons.container.items() if o.type.name=='Sprite'}
    extids={external_name(sf,i+1):i+1 for i in range(len(sf.externals))}
    image_cab=next(o.assets_file.name.lower() for o in srcicons.objects if o.type.name=='Sprite')
    image_ext=next(i+1 for i in range(len(dsf.externals)) if image_cab==external_name(dsf,i+1))
    def extref(p):
        name=external_name(dsf,p['m_FileID']);assert name in extids,('缺失外部依赖',name)
        id=p['m_PathID']
        if p['m_FileID']==image_ext:
            path=srciconpaths[id];assert path in dsticonpaths,('需要先迁入称号图标',path)
            id=dsticonpaths[path].path_id
        else:
            assert (extids[name],id) in old_external_refs,('底包没有现成的非图标依赖引用',name,id)
        return {'m_FileID':extids[name],'m_PathID':id}
    bundle=next(o for o in env.objects if o.type.name=='AssetBundle');ab=bundle.read_typetree()
    old_external_refs={(p['m_FileID'],p['m_PathID']) for p in ab['m_PreloadTable'] if p['m_FileID'] and p['m_PathID']}
    proof={'added':[],'reused':[],'sourceSha256':hashlib.sha256(source).hexdigest(),'sourceVersion':donor.file.version_engine,'targetVersion':env.file.version_engine,'oldObjectsPreserved':True,'newObjects':0}
    planned={}
    for show in sorted(set(shows)):
        path=PREFIX+str(show)+'.prefab';assert path in src,('来源没有完整称号预制体',path)
        if path in old:
            assert canonical(old[path],icons)==canonical(src[path],srcicons),('已有称号预制体语义不同，需人工核对',path)
            proof['reused'].append(show);continue
        objects=graph(src[path]);mapping={}
        for id,obj in objects.items():
            if obj.type.name=='MonoScript':mapping[id]=scripts[script_key(obj)]
            else:
                key=component_key(obj);assert key in templates,('底包没有匹配的组件布局',key)
                mapping[id]=clone_reader(templates[key]);proof['newObjects']+=1
        def remap(v):
            if isinstance(v,dict):
                if set(v)=={'m_FileID','m_PathID'}:
                    if not v['m_PathID']:return {'m_FileID':0,'m_PathID':0}
                    return pointer(mapping[v['m_PathID']]) if v['m_FileID']==0 else extref(v)
                return {k:remap(x) for k,x in v.items()}
            if isinstance(v,list):return [remap(x) for x in v]
            if isinstance(v,tuple):return tuple(remap(x) for x in v)
            return copy.deepcopy(v)
        for id,obj in objects.items():
            if obj.type.name=='MonoScript':continue
            new=mapping[id];t=merge_fields(new.read_typetree(),remap(obj.read_typetree()));new.save_typetree(t);planned[new.path_id]=t
        # 预加载真实对象闭包；外部字体及图标均沿用底包清单已有依赖。
        refs=[pointer(o) for id,o in mapping.items()]
        for obj in objects.values():
            for p in pointers(obj.read_typetree()):
                if p['m_FileID'] and p['m_PathID']:
                    ref=extref(p)
                    if ref not in refs:refs.append(ref)
        start=len(ab['m_PreloadTable']);ab['m_PreloadTable'].extend(refs)
        ab['m_Container'].append((path,{'preloadIndex':start,'preloadSize':len(refs),'asset':pointer(mapping[object_reader(src[path]).path_id])}))
        proof['added'].append(show)
    if proof['added']:bundle.save_typetree(ab);result=env.file.save(packer='lz4')
    else:result=original
    check=unity.load(result);saved={o.path_id:o for o in check.objects}
    for obj in unity.load(original).objects:
        if obj.type.name!='AssetBundle':assert saved[obj.path_id].get_raw_data()==obj.get_raw_data(),('旧对象意外变化',obj.path_id)
    for id,t in planned.items():assert saved[id].read_typetree()==t,('新对象重读不符',id)
    actual={p:o for p,o in check.container.items()}
    for show in shows:
        path=PREFIX+str(show)+'.prefab'
        assert canonical(actual[path],icons)==canonical(src[path],srcicons),('完整称号重读语义不符',show)
    proof['outputSha256']=hashlib.sha256(result).hexdigest();return result,proof
