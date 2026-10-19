const {test}=require('node:test'),assert=require('node:assert/strict');
const {RelayMapCamera,RelayMapViewer}=require('../home-server/web/relay-map.js');
const camera=()=>{const c=new RelayMapCamera();c.width=800;c.height=400;return c;};
test('zoom keeps the point under the finger',()=>{const c=camera(),p=c.world(170,90);c.zoom(4,170,90);assert.deepEqual(c.world(170,90),p);});
test('panning follows the finger and Y keeps pointing up',()=>{const c=camera();c.pan(60,30);assert.deepEqual(c.screen(0,0),[460,230]);assert(c.screen(0,1)[1]<230);});
test('fitting includes negative coordinates and an outlying base',()=>{const c=camera();c.fit([[-20,-10],[10,5],[100,0]]);for(const p of [[-20,-10],[10,5],[100,0]]){const [x,y]=c.screen(...p);assert(x>=0&&x<=800);assert(y>=0&&y<=400);}});
test('an empty map and a single point do not produce an invalid camera',()=>{const c=camera();c.fit([]);c.fit([[2,3],[NaN,5]]);assert.deepEqual(c.screen(2,3),[400,200]);assert(Number.isFinite(c.scale));});

test('a map arriving after the position is fitted once, then the user zoom is kept',()=>{
  const v=Object.create(RelayMapViewer.prototype);v.camera=camera();v.map=null;v.position={x:0,y:0};v.fitted=false;v.draw=()=>{};
  v.setPosition(v.position);const map={shapes:[{points:[[0,0],[100,100]]}],base:{x:-10,y:0}};v.setMap(map);
  assert(v.camera.screen(100,100)[0]<800);v.zoom(2);const scale=v.camera.scale;v.setMap(map);assert.equal(v.camera.scale,scale);
});
