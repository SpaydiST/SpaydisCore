"""Check advancement resources against an actual Minecraft client and optional pack.

Requires Pillow for checking that icon textures contain visible pixels.
This validates assets and JSON contracts, not the in-game renderer.
"""
import argparse
import io
import json
from pathlib import Path
from zipfile import ZipFile
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client', type=Path, required=True)
parser.add_argument('--pack', type=Path, required=True)
parser.add_argument('--core', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--smp', type=Path)
args = parser.parse_args()
smp = args.smp or args.core.parent / 'untitled1'

def resource(identifier, folder, extension):
    namespace, name = identifier.split(':', 1) if ':' in identifier else ('minecraft', identifier)
    return f'assets/{namespace}/{folder}/{name}{extension}'

with ZipFile(args.client) as client, ZipFile(args.pack) as pack:
    version = json.loads(client.read('version.json'))
    assert version['id'] == '26.3', 'Use the exact target client version'
    formats = version['pack_version']
    resource_format = [formats['resource_major'], formats['resource_minor']]
    data_format = [formats['data_major'], formats['data_minor']]
    meta = json.loads(pack.read('pack.mcmeta'))['pack']
    assert meta['min_format'] == meta['max_format'] == resource_format

    for use_pack in (False, True):
        def read(name):
            return pack.read(name) if use_pack and name in pack.namelist() else client.read(name)

        checked_textures = set()
        def visible_texture(identifier):
            path = resource(identifier, 'textures', '.png')
            with Image.open(io.BytesIO(read(path))) as image:
                assert image.convert('RGBA').getchannel('A').getextrema()[1] > 0, path
            checked_textures.add(path)

        def model_textures(identifier, visiting=None):
            visiting = set() if visiting is None else visiting
            assert identifier not in visiting, f'Model cycle: {identifier}'
            if identifier.startswith(('minecraft:builtin/', 'builtin/')):
                return {}
            visiting.add(identifier)
            model = json.loads(read(resource(identifier, 'models', '.json')))
            textures = model_textures(model['parent'], visiting) if 'parent' in model else {}
            textures.update(model.get('textures', {}))
            scale = model.get('display', {}).get('gui', {}).get('scale', [1, 1, 1])
            assert all(value != 0 for value in scale), f'Invisible GUI model: {identifier}'
            visiting.remove(identifier)
            return textures

        count = 0
        for root, folder in ((args.core, 'spaydiscore_datapack'), (smp, 'smprevive_advancements')):
            base = root / 'src/main/resources' / folder
            meta = json.loads((base / 'pack.mcmeta').read_text(encoding='utf-8-sig'))['pack']
            assert meta['min_format'] == meta['max_format'] == data_format, base
            for path in (base / 'data').glob('*/advancement/*.json'):
                entry = json.loads(path.read_text(encoding='utf-8-sig'))
                display = entry['display']
                if 'background' in display:
                    background = display['background']
                    assert 'textures/' not in background and not background.endswith('.png'), path
                    visible_texture(background)
                icon = display['icon']
                assert icon.get('count', 1) > 0, path
                strings = icon.get('components', {}).get('minecraft:custom_model_data', {}).get('strings', [])
                item = json.loads(read(resource(icon['id'], 'items', '.json')))['model']
                if item['type'].split(':')[-1] == 'select':
                    assert item['property'].split(':')[-1] == 'custom_model_data'
                    index = item.get('index', 0)
                    value = strings[index] if index < len(strings) else None
                    matches = [case['model'] for case in item['cases']
                               if value in (case['when'] if isinstance(case['when'], list) else [case['when']])]
                    if strings and use_pack:
                        assert len(matches) == 1, f'Custom model data has no unique model: {path}'
                    item = matches[0] if matches else item['fallback']
                elif strings and use_pack:
                    raise AssertionError(f'Pack does not select requested custom model: {path}')
                assert item['type'].split(':')[-1] == 'model', (path, item)
                textures = model_textures(item['model'])
                assert textures, f'No visible texture: {path}'
                for value in textures.values():
                    visited = set()
                    while value.startswith('#'):
                        assert value not in visited, f'Texture cycle: {path}'
                        visited.add(value)
                        value = textures[value[1:]]
                    visible_texture(value)
                if path.stem == 'failed_love':
                    assert display['hidden'] is True
                    assert entry['criteria']['unlock']['trigger'] == 'minecraft:impossible'
                if path.stem == 'root' and folder == 'smprevive_advancements':
                    assert display['title'] == {'text': 'Pangea SMP'}, 'Title must work with older packs too'
                count += 1
        print(f'PASS: {count} advancements, {len(checked_textures)} visible textures, '
              f'{"with MacroNux" if use_pack else "vanilla without pack"}; Minecraft {version["id"]}')
