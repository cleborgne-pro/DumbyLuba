/* Visible texts: French when the browser language is French, English otherwise. */
function t(en,fr){return typeof navigator!=='undefined'&&String(navigator.language||'').toLowerCase().startsWith('fr')?fr:en;}
/* Local coordinates in metres: +Y points upwards, headings are mathematical degrees. */
class RelayMapCamera {
  constructor(){this.x=0;this.y=0;this.scale=20;this.width=1;this.height=1;}
  screen(x,y){return [this.width/2+(x-this.x)*this.scale,this.height/2-(y-this.y)*this.scale];}
  world(x,y){return [this.x+(x-this.width/2)/this.scale,this.y-(y-this.height/2)/this.scale];}
  pan(dx,dy){this.x-=dx/this.scale;this.y+=dy/this.scale;}
  zoom(factor,x=this.width/2,y=this.height/2){const before=this.world(x,y);this.scale=Math.max(.05,Math.min(2000,this.scale*factor));const after=this.world(x,y);this.x+=before[0]-after[0];this.y+=before[1]-after[1];}
  fit(points){const valid=points.filter(p=>Array.isArray(p)&&Number.isFinite(p[0])&&Number.isFinite(p[1]));if(!valid.length)return;
    let minX=Infinity,maxX=-Infinity,minY=Infinity,maxY=-Infinity;for(const [x,y] of valid){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
    this.x=(minX+maxX)/2;this.y=(minY+maxY)/2;this.scale=Math.max(.05,Math.min(2000,Math.min(this.width*.8/Math.max(2,maxX-minX),this.height*.8/Math.max(2,maxY-minY))));
  }
}
class RelayMapViewer {
  constructor(canvas){this.canvas=canvas;this.ctx=canvas.getContext('2d');this.camera=new RelayMapCamera();this.map=null;this.position=null;this.fitted=false;this.pointers=new Map();
    new ResizeObserver(()=>this.resize()).observe(canvas);this.resize();
    canvas.addEventListener('wheel',e=>{e.preventDefault();const p=this.local(e);this.camera.zoom(Math.exp(-Math.max(-100,Math.min(100,e.deltaY))*.005),...p);this.draw();},{passive:false});
    canvas.addEventListener('pointerdown',e=>{canvas.setPointerCapture(e.pointerId);this.pointers.set(e.pointerId,this.local(e));});
    canvas.addEventListener('pointermove',e=>{if(!this.pointers.has(e.pointerId))return;const previous=[...this.pointers.values()];this.pointers.set(e.pointerId,this.local(e));const next=[...this.pointers.values()];
      if(next.length===1)this.camera.pan(next[0][0]-previous[0][0],next[0][1]-previous[0][1]);
      else if(next.length===2){const mid=a=>[(a[0][0]+a[1][0])/2,(a[0][1]+a[1][1])/2],distance=a=>Math.hypot(a[1][0]-a[0][0],a[1][1]-a[0][1]);const a=mid(previous),b=mid(next);this.camera.pan(b[0]-a[0],b[1]-a[1]);const d=distance(previous);if(d>1)this.camera.zoom(distance(next)/d,...b);}
      this.draw();});
    for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(event,e=>this.pointers.delete(e.pointerId));
  }
  local(e){const r=this.canvas.getBoundingClientRect();return [e.clientX-r.left,e.clientY-r.top];}
  resize(){const rect=this.canvas.getBoundingClientRect(),dpr=window.devicePixelRatio||1;this.camera.width=rect.width;this.camera.height=rect.height;this.canvas.width=Math.round(rect.width*dpr);this.canvas.height=Math.round(rect.height*dpr);this.ctx.setTransform(dpr,0,0,dpr,0,0);if(!this.fitted)this.fit();else this.draw();}
  points(){const points=(this.map?.shapes||[]).flatMap(s=>s.points);for(const p of [this.map?.base,this.position])if(p)points.push([p.x,p.y]);return points;}
  setMap(map){const firstContours=!this.map?.shapes?.length&&map?.shapes?.length;this.map=map;if(firstContours||(!this.fitted&&this.points().length))this.fit();else this.draw();}
  setPosition(position){this.position=position;if(!this.fitted&&this.points().length)this.fit();else this.draw();}
  fit(){const points=this.points();this.camera.fit(points);this.fitted=points.length>0;this.draw();}
  zoom(factor){this.camera.zoom(factor);this.draw();}
  marker(p,color,label,heading=null){if(!p||!Number.isFinite(p.x)||!Number.isFinite(p.y))return;const c=this.ctx,[x,y]=this.camera.screen(p.x,p.y);c.fillStyle=color;c.strokeStyle='#fff';c.lineWidth=2;c.beginPath();c.arc(x,y,8,0,Math.PI*2);c.fill();c.stroke();
    if(Number.isFinite(heading)){const a=heading*Math.PI/180;c.strokeStyle=color;c.lineWidth=3;c.beginPath();c.moveTo(x,y);c.lineTo(x+22*Math.cos(a),y-22*Math.sin(a));c.stroke();}
    c.font='bold 14px system-ui';c.lineWidth=4;c.strokeStyle='white';c.strokeText(label,x+12,y-12);c.fillText(label,x+12,y-12);
  }
  draw(){const c=this.ctx,w=this.camera.width,h=this.camera.height;c.clearRect(0,0,w,h);
    const palette=getComputedStyle(this.canvas),color=(key,fallback)=>palette.getPropertyValue(key).trim()||fallback;
    const shapes=[...(this.map?.shapes||[])].sort((a,b)=>a.type-b.type);
    for(const shape of shapes){if(!shape.points.length)continue;c.beginPath();shape.points.forEach(([x,y],i)=>{const p=this.camera.screen(x,y);if(i)c.lineTo(...p);else c.moveTo(...p);});c.lineWidth=2;c.strokeStyle=shape.type===0?color('--stroke','#398e50'):shape.type===1?color('--nogo-stroke','#b85444'):'#697780';
      if(shape.type===0||shape.type===1){c.closePath();c.fillStyle=shape.type===0?color('--zone','#c6ddd0'):color('--nogo','#f0cdc6');c.fill();}c.stroke();}
    this.marker(this.map?.base,'#b96300','Base');this.marker(this.position,this.position?.fresh?color('--info','#175cd3'):'#657589',this.position?.fresh?'Robot':t('Robot · old','Robot · ancien'),this.position?.heading);
    c.font='13px system-ui';c.fillStyle=color('--secondary','#667085');if(!shapes.length)c.fillText(t('No contour loaded — read the map over BLE','Aucun contour chargé — lire la carte BLE'),14,24);
    const meters=Math.pow(10,Math.floor(Math.log10(100/this.camera.scale))),pixels=meters*this.camera.scale;
    c.strokeStyle=color('--secondary','#667085');c.lineWidth=2;c.beginPath();c.moveTo(16,h-22);c.lineTo(16+pixels,h-22);c.stroke();c.fillText(meters+' m',16,h-30);
  }
}
if(typeof module!=='undefined')module.exports={RelayMapCamera,RelayMapViewer,t};
