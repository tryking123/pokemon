"""以底包的 Unity 序列化版本补齐外观图像，保留所有原有对象与路径。"""
import copy,hashlib

def object_reader(pointer):
    deref=getattr(pointer,'deref',None)
    if callable(deref):return deref()
    getter=getattr(pointer,'get_obj',None)
    return getter() if callable(getter) else pointer
def clone_reader(template):
    template=object_reader(template);new=copy.copy(template);id=1
    while id in template.assets_file.objects:id+=1
    new.path_id=id;new.set_raw_data(template.get_raw_data());template.assets_file.objects[id]=new;return new
def pointer(reader):return {'m_FileID':0,'m_PathID':reader.path_id}
def merge_fields(template,source):
    if isinstance(template,dict) and isinstance(source,dict):return {k:merge_fields(v,source[k]) if k in source else copy.deepcopy(v) for k,v in template.items()}
    return copy.deepcopy(source)

def add_images(original,source,paths,unity,allow_replacement=False):
    """只补入缺少的 Texture2D/Sprite，图像像素与来源逐项核对。"""
    from UnityPy.enums import TextureFormat
    env=unity.load(original);donor=unity.load(source)
    old={(p,o.type.name):o for p,o in env.container.items()};src={(p,o.type.name):o for p,o in donor.container.items()}
    ab=next(o for o in env.objects if o.type.name=='AssetBundle');tree=ab.read_typetree();added=[];reused=[];replaced=[];replaced_ids=set()
    texture_template=next(o for o in env.objects if o.type.name=='Texture2D')
    sprite_template=next((o for o in env.objects if o.type.name=='Sprite'),None)
    def register(path,reader):
        pos=len(tree['m_PreloadTable']);ptr=pointer(reader);tree['m_PreloadTable'].append(ptr)
        tree['m_Container'].append((path,{'preloadIndex':pos,'preloadSize':1,'asset':ptr}));added.append((path,reader.type.name))
    def texture(reader,name):
        new=clone_reader(texture_template);data=new.read();source_data=reader.read();data.m_Name=name
        data.set_image(source_data.image.convert('RGBA'),target_format=TextureFormat.RGBA32);data.save();return new
    for path in paths:
        available=[kind for kind in ['Texture2D','Sprite'] if (path,kind) in src]
        assert available,('源资源路径不存在',path)
        for kind in available:
            if (path,kind) in old:
                if kind in ['Texture2D','Sprite']:
                    x=old[path,kind].read().image.convert('RGBA');y=src[path,kind].read().image.convert('RGBA')
                    if x.size==y.size and x.tobytes()==y.tobytes():reused.append((path,kind));continue
                    assert allow_replacement,('共享图片与来源不同，禁止覆盖',path,kind)
                new=object_reader(old[path,kind]);source_obj=src[path,kind];replaced_ids.add(new.path_id);replaced.append((path,kind))
                if kind=='Texture2D':
                    data=new.read();data.set_image(source_obj.read().image.convert('RGBA'),target_format=TextureFormat.RGBA32);data.save();continue
                source_tree=source_obj.read_typetree();sprite=source_obj.read();tex=texture(sprite.m_RD.texture,sprite.m_Name+'-texture')
                tree_data=merge_fields(new.read_typetree(),source_tree);tree_data['m_RD']['texture']=pointer(tex);tree_data['m_RD']['alphaTexture']={'m_FileID':0,'m_PathID':0};tree_data['m_SpriteAtlas']={'m_FileID':0,'m_PathID':0};tree_data['m_AtlasTags']=[];new.save_typetree(tree_data);continue
            source_obj=src[path,kind]
            if kind=='Texture2D':
                new=texture(source_obj,source_obj.read().m_Name);register(path,new);old[path,kind]=new
            else:
                assert sprite_template is not None
                new=clone_reader(sprite_template);source_tree=source_obj.read_typetree();target_tree=new.read_typetree()
                sprite=source_obj.read();tex=texture(sprite.m_RD.texture,sprite.m_Name+'-texture')
                tree_data=merge_fields(target_tree,source_tree);tree_data['m_RD']['texture']=pointer(tex)
                assert source_tree['m_RD']['alphaTexture']['m_PathID']==0,'需要单独迁移分离Alpha纹理'
                tree_data['m_RD']['alphaTexture']={'m_FileID':0,'m_PathID':0};tree_data['m_SpriteAtlas']={'m_FileID':0,'m_PathID':0};tree_data['m_AtlasTags']=[]
                new.save_typetree(tree_data);register(path,new);old[path,kind]=new
    if added or replaced:
        ab.save_typetree(tree);result=env.file.save(packer='lz4')
    else:result=original
    check=unity.load(result);actual={(p,o.type.name):o for p,o in check.container.items()}
    for path,kind in added+replaced:
        a=actual[path,kind].read().image.convert('RGBA');b=src[path,kind].read().image.convert('RGBA')
        assert a.size==b.size and a.tobytes()==b.tobytes(),('新图像序列化后像素不符',path,kind)
    # 没有改动任何旧对象的原始字节；新对象仅通过原容器追加引用。
    saved={o.path_id:o.get_raw_data() for o in check.objects}
    for obj in unity.load(original).objects:
        if obj.type.name!='AssetBundle' and obj.path_id not in replaced_ids:assert saved[obj.path_id]==obj.get_raw_data(),('原对象被改动',obj.path_id)
    return result,{'added':added,'reused':reused,'replaced':replaced,'sourceSha256':hashlib.sha256(source).hexdigest(),'outputSha256':hashlib.sha256(result).hexdigest()}
