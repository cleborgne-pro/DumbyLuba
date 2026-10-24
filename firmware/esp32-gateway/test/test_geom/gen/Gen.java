// Reference vectors of test_geom (geom_reference.h): surfaces written by JTS WKBWriter() and JTS results of the chassis
// checks on a realistic surface (planner-reference-map.json zones grown by 0.15 m, no-go zones shrunk, 12 obstacles).
//   python3 -c "import json;d=json.load(open('app/src/test/resources/planner-reference-map.json'));
//     open('shapes.txt','w').writelines(str(s['type'])+' '+' '.join('%r %r'%(p[0],p[1]) for p in s['points'])+'\n'
//     for s in d['shapes'] if s['type'] in (0,1) and len(s['points'])>=3)"
//   JTS=~/.gradle/caches/modules-2/files-2.1/org.locationtech.jts/jts-core/1.20.0/*/jts-core-1.20.0.jar
//   javac -cp $JTS -d /tmp/gen Gen.java && java -cp $JTS:/tmp/gen Gen geom_reference.h   (in the shapes.txt directory)
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.*;
import org.locationtech.jts.io.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Gen {
  static GeometryFactory f = new GeometryFactory();
  static final double FRONT = 0.345, REAR = 0.69 - 0.345, HW = 0.513 / 2;
  static PreparedGeometry prep; static Geometry area;
  static StringBuilder out = new StringBuilder();

  static Polygon rect(double x, double y, double w, double h) {
    return f.createPolygon(new Coordinate[]{new Coordinate(x,y),new Coordinate(x+w,y),new Coordinate(x+w,y+h),new Coordinate(x,y+h),new Coordinate(x,y)});
  }
  static Geometry room() { return rect(-5,-5,10,10); }
  static Geometry obstacle(double x, double y, double r) { return f.createPoint(new Coordinate(x,y)).buffer(r); }
  static Polygon poly(List<double[]> pts) {
    Coordinate[] c = new Coordinate[pts.size()+1];
    for (int i=0;i<pts.size();i++) c[i]=new Coordinate(pts.get(i)[0],pts.get(i)[1]);
    c[pts.size()]=c[0];
    return f.createPolygon(c);
  }
  static Polygon footprint(double x, double y, double h) {
    double a = Math.toRadians(h);
    double[][] l = {{FRONT,HW},{FRONT,-HW},{-REAR,-HW},{-REAR,HW}};
    Coordinate[] c = new Coordinate[5];
    for (int i=0;i<4;i++) c[i]=new Coordinate(x+l[i][0]*Math.cos(a)-l[i][1]*Math.sin(a), y+l[i][0]*Math.sin(a)+l[i][1]*Math.cos(a));
    c[4]=c[0];
    return f.createPolygon(c);
  }
  static boolean poseSafe(double x,double y,double h){ return prep.containsProperly(footprint(x,y,h)); }
  static boolean straightSafe(double x1,double y1,double x2,double y2,double h){ return prep.containsProperly(footprint(x1,y1,h).union(footprint(x2,y2,h)).convexHull()); }
  static double lastNewGround;
  static boolean leaves(double fx,double fy,double tx,double ty,double h){
    lastNewGround = -1;
    if (poseSafe(fx,fy,h)) return straightSafe(fx,fy,tx,ty,h);
    double dx=fx-tx, dy=fy-ty; double length=Math.sqrt(dx*dx+dy*dy);
    if (length<1e-9) return false;
    double ux=(tx-fx)/length, uy=(ty-fy)/length; double step=0.02;
    double along=step;
    while (along<length && !poseSafe(fx+ux*along,fy+uy*along,h)) along+=step;
    if (along>=length) return false;
    double ix=fx+ux*along, iy=fy+uy*along;
    Geometry s=footprint(fx,fy,h);
    Geometry swept=s.union(footprint(ix,iy,h)).convexHull();
    lastNewGround = swept.difference(area).difference(s).getArea();
    if (lastNewGround>0.002) return false;
    return straightSafe(ix,iy,tx,ty,h);
  }
  static void bytes(String name, byte[] b) {
    out.append("static const uint8_t ").append(name).append("[] = {");
    for (int i=0;i<b.length;i++){ if(i%24==0) out.append("\n  "); out.append(String.format("0x%02X,", b[i]&255)); }
    out.append("\n};\n");
  }
  static String d(double v){ return String.format(Locale.ROOT,"%.17g",v); }

  public static void main(String[] args) throws Exception {
    WKBWriter w = new WKBWriter();
    // Small surfaces of TrajectoryEnvelopeTest / AreaIndexTest.
    bytes("WKB_ROOM", w.write(room()));
    bytes("WKB_ROOM_LE", new WKBWriter(2, ByteOrderValues.LITTLE_ENDIAN).write(room()));
    bytes("WKB_REAR_OBSTACLE", w.write(room().difference(obstacle(-0.45,0,0.02))));
    bytes("WKB_WALL_OBSTACLE", w.write(room().difference(obstacle(-3.0,0,0.05))));
    bytes("WKB_THIN", w.write(room().difference(rect(-0.001,-0.01,0.002,0.02))));
    bytes("WKB_PIVOT", w.write(room().difference(obstacle(0.42,0,0.008))));
    double ang=Math.toRadians(2.5); double cx=FRONT*Math.cos(ang)-HW*Math.sin(ang), cy=FRONT*Math.sin(ang)+HW*Math.cos(ang);
    bytes("WKB_TUNNEL", w.write(room().difference(obstacle(cx,cy,0.00005))));
    bytes("WKB_CORRIDOR", w.write(rect(-3,-0.30,6,0.60)));
    bytes("WKB_SQUARE2", w.write(rect(0,0,2,2)));
    bytes("WKB_ARC", w.write(room().difference(obstacle(1.1,1.0,0.02))));
    bytes("WKB_TWO", w.write(f.createMultiPolygon(new Polygon[]{rect(0,0,4,4), rect(8,0,4,4)})));
    bytes("WKB_BOWTIE", w.write(f.createPolygon(new Coordinate[]{new Coordinate(0,0),new Coordinate(2,2),new Coordinate(2,0),new Coordinate(0,2),new Coordinate(0,0)})));
    bytes("WKB_EMPTY", w.write(f.createPolygon()));
    bytes("WKB_PLANNER", w.write(rect(0,0,8,6).difference(rect(3,2,1,1))));
    // Realistic surface: recorded zones grown by the physical tolerance, no-go zones shrunk, some obstacles.
    List<Geometry> zones=new ArrayList<>(), nogo=new ArrayList<>();
    for (String line: Files.readAllLines(Paths.get("shapes.txt"))) {
      String[] t=line.trim().split(" "); List<double[]> p=new ArrayList<>();
      for (int i=1;i+1<t.length;i+=2) p.add(new double[]{Double.parseDouble(t[i]),Double.parseDouble(t[i+1])});
      Polygon g=poly(p); if(!g.isValid()) g=(Polygon)g.buffer(0);
      if (t[0].equals("0")) zones.add(g.buffer(0.15,16)); else nogo.add(g.buffer(-0.15,16));
    }
    Geometry s=f.buildGeometry(zones).union();
    for (Geometry n: nogo) if(!n.isEmpty()) s=s.difference(n);
    Random r=new Random(42);
    Envelope env=s.getEnvelopeInternal();
    int obstacles=0;
    while (obstacles<12) {
      double x=env.getMinX()+r.nextDouble()*env.getWidth(), y=env.getMinY()+r.nextDouble()*env.getHeight();
      Geometry o=f.createPoint(new Coordinate(x,y)).buffer(0.3+r.nextDouble()*0.3,16);
      if (s.contains(o)) { s=s.difference(o); obstacles++; }
    }
    area=s; prep=PreparedGeometryFactory.prepare(s);
    if (!s.isValid()) throw new RuntimeException("invalid");
    byte[] big=w.write(s);
    bytes("WKB_GARDEN", big);
    out.append("// ").append(s.getGeometryType()).append(", ").append(s.getNumPoints()).append(" vertices, area ").append(d(s.getArea())).append("\n");
    System.err.println(s.getGeometryType()+" "+s.getNumPoints()+" pts, "+big.length+" bytes, polys "+s.getNumGeometries());
    Coordinate[] vs=s.getCoordinates();
    // Poses near the edges.
    out.append("struct PoseCase { double x, y, h; bool safe; bool body; };\nstatic const PoseCase POSES[] = {\n");
    int ps=0;
    for (int k=0;k<1000;k++){
      Coordinate v=vs[r.nextInt(vs.length)];
      double x=v.x+(r.nextDouble()-0.5)*1.6, y=v.y+(r.nextDouble()-0.5)*1.6, h=r.nextDouble()*360-180;
      boolean safe=poseSafe(x,y,h);
      Point pt=f.createPoint(new Coordinate(x,y));
      boolean body=prep.contains(pt) && s.getBoundary().distance(pt)>HW;
      if (safe) ps++;
      out.append("  {").append(d(x)).append(",").append(d(y)).append(",").append(d(h)).append(",").append(safe).append(",").append(body).append("},\n");
    }
    out.append("};\n");
    System.err.println("poses safe "+ps);
    out.append("struct StraightCase { double x1, y1, x2, y2, h; bool safe; };\nstatic const StraightCase STRAIGHTS[] = {\n");
    int ss=0, sc=0;
    while (sc<800){
      Coordinate v=vs[r.nextInt(vs.length)];
      double x=v.x+(r.nextDouble()-0.5)*3, y=v.y+(r.nextDouble()-0.5)*3, h=r.nextDouble()*360-180;
      if (!poseSafe(x,y,h)) continue;
      double len=r.nextDouble()*3, a=Math.toRadians(h+(r.nextBoolean()?0:180));
      double x2=x+len*Math.cos(a), y2=y+len*Math.sin(a);
      boolean safe=straightSafe(x,y,x2,y2,h); if (safe) ss++; sc++;
      out.append("  {").append(d(x)).append(",").append(d(y)).append(",").append(d(x2)).append(",").append(d(y2)).append(",").append(d(h)).append(",").append(safe).append("},\n");
    }
    out.append("};\n");
    System.err.println("straights safe "+ss);
    out.append("struct EscapeCase { double fx, fy, tx, ty, h; bool leaves; double newGround; };\nstatic const EscapeCase ESCAPES[] = {\n");
    int ec=0, el=0, eg=0;
    while (ec<1000){
      Coordinate v=vs[r.nextInt(vs.length)];
      double x=v.x+(r.nextDouble()-0.5)*0.8, y=v.y+(r.nextDouble()-0.5)*0.8, h=r.nextDouble()*360-180;
      if (!prep.contains(f.createPoint(new Coordinate(x,y))) || poseSafe(x,y,h)) continue;
      double dev=(r.nextInt(4)==0)? (r.nextDouble()-0.5)*90 : (r.nextDouble()-0.5)*8;
      double len=0.1+r.nextDouble()*1.5, a=Math.toRadians(h+dev+(r.nextInt(3)==0?180:0));
      double x2=x+len*Math.cos(a), y2=y+len*Math.sin(a);
      boolean ok=leaves(x,y,x2,y2,h); if(ok) el++; if (lastNewGround>=0) eg++; ec++;
      out.append("  {").append(d(x)).append(",").append(d(y)).append(",").append(d(x2)).append(",").append(d(y2)).append(",").append(d(h)).append(",").append(ok).append(",").append(d(lastNewGround)).append("},\n");
    }
    out.append("};\n");
    System.err.println("escapes leave "+el+", area computed "+eg);
    // Room case of chassisOverlappingAnEdgeMayOnlyMoveAway: reference new-ground areas.
    area=room(); prep=PreparedGeometryFactory.prepare(area);
    double[][] rc={{-3.5,0},{-3.5,0.05},{-3.8,1.0}};
    for (double[] c: rc){ boolean ok=leaves(-4.8,0,c[0],c[1],0); System.err.println("room "+c[0]+","+c[1]+" "+ok+" "+lastNewGround); }
    Files.write(Paths.get(args[0]), ("// Generated by test/test_geom/gen/Gen.java with JTS 1.20 (WKBWriter() defaults: big endian, 2D).\n#pragma once\n#include <cstdint>\n"+out).getBytes());
  }
}
