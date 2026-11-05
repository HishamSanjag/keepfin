// Regenerate from the repo root: node fixtures/merge/generate.mjs
// Expectations are declared here; generation never imports or runs the planner.
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const dir = fileURLToPath(new URL('.', import.meta.url));
const uuid = n => '10000000-0000-4000-8000-' + n.toString(16).padStart(12, '0');
const actor = uuid(900), replica = uuid(901), other = uuid(902);
const cfg = { mergeId: 'fixture-merge', actor, replicaId: replica, firstSpaceOrder: 3 };
const hash = n => 'sha256:' + n.toString(16).padStart(64, '0');
const hlc = (n, a = actor) => n.toString().padStart(13, '0') + '.000000.' + a;
const id = (kind, source) => {
  const h = createHash('sha256').update(JSON.stringify(['keepfin-merge-v1', cfg.mergeId, kind, source])).digest('hex');
  return h.slice(0,8)+'-'+h.slice(8,12)+'-5'+h.slice(13,16)+'-8'+h.slice(17,20)+'-'+h.slice(20,32);
};
const wireMusic = '6b8e1c10-2d3a-4f55-8a01-000000000002';
const fields = (title = 'Song') => ({ media: 'audio', space: wireMusic, title, addedAt: 0 });
const s = (n, h = n, more = {}) => ({
  id: uuid(n), fields: fields(), added: hlc(n), revisionHash: hash(h), size: 100, mtimeHint: 10,
  pathOnB: 'Music/song-'+n+'.mp3', file: { readable: true, size: 100, mtime: 10 }, ...more,
});
const t = (n, h = n, more = {}) => ({ id: uuid(n), fields: fields(), added: hlc(n), revisionHash: hash(h), size: 100, ...more });
const added = n => ({ s: uuid(n), t: id('item', uuid(n)), how: 'added' });
const linked = (n, dest) => ({ s: uuid(n), t: uuid(dest), how: 'linked' });
const dup = n => ({ ...added(n), how: 'kept-duplicate' });
const cases = [];
const add = (name, source, target, expected, extra = {}) => cases.push({ name, config: cfg, source, target, expected, ...extra });
add('empty S', [], [t(10)], { map: [], drafts: 0, reviewKinds: [] });
add('empty T', [s(1)], [], { map: [added(1)], drafts: 3, reviewKinds: [] });
add('duplicates in S spanning pages', [s(1,8),s(2,8),s(3,8)], [t(40,8)],
  { map:[linked(1,40),dup(2),dup(3)], reviewKinds:['differingField','duplicateInS','duplicateInS'] }, { pageSize:1 });
add('several T matches choose oldest HLC not id', [s(1,8)], [t(30,8,{added:hlc(2)}),t(10,8,{added:hlc(8)}),t(20,8,{added:hlc(1)})],
  { map:[linked(1,20)], reviewKinds:['manyMatches','manyMatches','differingField'], alternatives:[uuid(30),uuid(10)] });
add('hash hint mismatch needs observed bytes', [s(1,8,{file:{readable:true,size:101,mtime:11}})], [t(40,8),t(41,9)],
  { map:[linked(1,41)], needsHash:1 }, { rehash:{[uuid(1)]:hash(9)} });
add('mtime alone mismatches', [s(1,8,{file:{readable:true,size:100,mtime:11}})], [t(40,8)],
  {map:[linked(1,40)],needsHash:1},{rehash:{[uuid(1)]:hash(8)}});
add('unknown hints require hash', [s(1,8,{mtimeHint:null,file:{readable:true,size:100,mtime:null}})], [],
  {map:[added(1)],needsHash:1},{rehash:{[uuid(1)]:hash(8)}});
add('missing file still its own item', [s(1,8,{file:{readable:false}})], [t(40,8)],
  {map:[added(1)],drafts:2,reviewKinds:['missingFile'],noPlacements:true});
add('unreadable missing revision retained as metadata', [s(1,8,{revisionHash:null,size:null,pathOnB:null,file:{readable:false}})], [],
  {map:[added(1)],drafts:1,reviewKinds:['missingFile'],noPlacements:true});
add('path on another replica is not a clash', [s(1,8)], [t(40,9,{placements:{[other]:'Music/song-1.mp3'}})],
  {map:[added(1)],drafts:3,reviewKinds:[]});
add('plays added once and newer playback wins', [
  s(1,8,{fields:{...fields('Incoming title'),artist:'Incoming artist',lastPlayedAt:200,position:12500,watched:true},
    plays:7,favourite:true,userTags:['b','a','a']})],
  [t(40,8,{fields:{...fields('Target title'),artist:'Target artist',lastPlayedAt:100,position:500,watched:false},plays:11})],
  {map:[linked(1,40)],plays:18,ownPlays:7,lastPlayedAt:200,position:12500,watched:true,
    keptTitle:'Target title',differing:['title','artist','space'],setTags:['a','b']});
add('older playback stays in T', [s(1,8,{fields:{...fields(),lastPlayedAt:20,position:8000,watched:true}})],
  [t(40,8,{fields:{...fields(),lastPlayedAt:100,position:1000,watched:false}})],
  {map:[linked(1,40)],lastPlayedAt:100,position:1000,watched:false});
add('register clocks resolve freshness independently', [s(1,8,{
  fields:{...fields(),lastPlayedAt:20,position:8000,watched:true},fieldClocks:{position:hlc(20),watched:hlc(1)}})],
  [t(40,8,{fields:{...fields(),lastPlayedAt:100,position:1000,watched:false},fieldClocks:{position:hlc(1),watched:hlc(20)}})],
  {map:[linked(1,40)],lastPlayedAt:100,position:8000,watched:false});
add('duplicates in S with new content', [s(1,8),s(2,8)], [], {map:[added(1),dup(2)],reviewKinds:['duplicateInS']});
add('T HLC tie uses stable item id', [s(1,8)], [t(30,8,{added:hlc(2)}),t(10,8,{added:hlc(2)})], {map:[linked(1,10)],alternatives:[uuid(30)]});
add('missing oldest still preserves duplicate identity', [s(1,8,{file:{readable:false}}),s(2,8)], [t(40,8)], {map:[added(1),dup(2)],reviewKinds:['missingFile','duplicateInS']}, {pageSize:1});
add('missing duplicate remains its own item', [s(1,8),s(2,8,{file:{readable:false}})], [t(40,8)], {map:[linked(1,40),dup(2)],reviewKinds:['differingField','duplicateInS','missingFile']}, {pageSize:1});
const playlist = uuid(70), space = uuid(71);
const metadata = {
  playlists:[{id:playlist,present:true,fields:{kind:'collection',collectionKind:'album',name:'Same name',space,
    source:{url:'https://example.test/album',provider:'Example',id:'remote'},
    follow:{mode:'auto',intervalHours:6,wifiOnly:true,notify:true,confirmAbove:50,pending:{count:2,at:10}},
    coverItemId:uuid(1),createdAt:1,updatedAt:2,
    rules:{rules:[{field:'item',values:[uuid(1)]},{field:'space',values:[space]}]}},
    songs:[{item:uuid(2)},{item:uuid(1)}]}],
  spaces:[{id:space,present:true,fields:{name:'Same name',kind:'music',folder:'',type:'smart',layout:'flat',
    naming:'title',skipExisting:true,color:'#334455',icon:'star',pinned:true,
    rules:{matchAll:true,rules:[{field:'item',values:[uuid(1)]},{field:'collection',values:[playlist]},
      {field:'artist',values:[uuid(1)]}],excludeSpaces:['music']}}}],
  sets:{['seen:'+playlist]:['external-source-id'],['new:'+playlist]:[uuid(1)],['removed:'+playlist]:[uuid(2)]},
};
add('same names keep entities with remapped rules covers follows sets', [s(1),s(2)], [], {
  map:[added(1),added(2)],reviewKinds:['sameNameSpace','sameNamePlaylist'],
  playlist:id('playlist',playlist),space:id('space',space),entries:[id('item',uuid(2)),id('item',uuid(1))],
},{metadata,names:[['playlist','Same name',uuid(80)],['space','Same name',uuid(81)]],
  art:[{ownerKind:'item',ownerId:uuid(1),source:'S/art/a.jpg',name:'a.jpg'},
    {ownerKind:'playlist',ownerId:playlist,source:'S/art/p.jpg',name:'p.jpg'}]});
add('batch boundary and every recovery offset', Array.from({length:180},(_,i)=>s(i+1)), [],
  {map:Array.from({length:180},(_,i)=>added(i+1)),drafts:540,batchSizes:[500,40],reviewKinds:[]}, {pageSize:17});
add('reverse only B additions after activation', [
  s(1,1,{addActor:actor,addSeq:50}),s(2,2,{addActor:other,addSeq:101}),s(3,3,{addActor:actor,addSeq:101,plays:4}),
  s(4,4,{addActor:actor,addSeq:102}),
], [t(40,3)], {map:[linked(3,40),added(4)]}, {config:{...cfg,actor:uuid(950),replicaId:uuid(951)},reverse:{bActorInT:actor,activationSeq:100}});
const separate = {
  name:'separate never deletes linked; Keep default; Remove explicit; no other replica deletes',
  config:cfg,
  target:[t(1,1,{placements:{[replica]:'b', [other]:'a'}}),t(2,2,{placements:{[replica]:'b'}}),
    t(3,3,{placements:{[replica]:'b',[other]:'a'}}),t(4,4,{placements:{[replica]:'b',[other]:'a'}}),
    t(5,5,{placements:{[other]:'a'}})],
  map:[{s:uuid(101),t:uuid(1),how:'linked'},...Array.from({length:4},(_,i)=>({s:uuid(102+i),t:uuid(2+i),how:i===0?'kept-duplicate':'added'}))],
  choices:{[uuid(1)]:'Remove',[uuid(4)]:'Remove'},
  expected:{deletes:[uuid(2),uuid(4)],clears:[uuid(1),uuid(3)]},
};
const canonical = v => v === null || typeof v !== 'object' ? JSON.stringify(v) : Array.isArray(v)
  ? '['+v.map(canonical).join(',')+']' : '{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';
for(const c of cases.filter(c=>c.name==='empty T'||c.name==='missing file still its own item')) {
  const row=c.source[0], item=c.expected.map[0].t;
  const ds=[{kind:'item.add',body:{item,fields:{...row.fields,space:id('space',wireMusic)}}},
    {kind:'item.revise',body:{item,content:row.revisionHash,size:row.size,parents:[]}}];
  if(row.file.readable) ds.push({kind:'item.place',body:{item,place:replica,path:row.pathOnB}});
  c.expected.draftsJson=canonical(ds);
  c.expected.draftsHash=createHash('sha256').update(c.expected.draftsJson).digest('hex');
}
writeFileSync(dir+'planner.json',JSON.stringify({cases},null,2)+'\n');
writeFileSync(dir+'separate.json',JSON.stringify({cases:[separate]},null,2)+'\n');
console.log('Generated '+cases.length+' merge fixtures and 1 separate fixture.');
