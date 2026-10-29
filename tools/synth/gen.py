import random, sys, json
from PIL import Image, ImageDraw
def gen(seed, nc, nr, W=1080, H=2340, headmode="scaled", out="x.png"):
    random.seed(seed)
    P = (W*0.94)/(nc-1)
    P = min(P, (H*0.55)/(nr-1))
    x0=(W-(nc-1)*P)/2; y0=H*0.5-(nr-1)*P/2
    SS=3
    bg=(35,45,55); col=(140,150,175)
    im=Image.new("RGB",(W*SS,H*SS),bg); d=ImageDraw.Draw(im)
    def pt(c,r): return ((x0+c*P)*SS,(y0+r*P)*SS)
    # dots
    for r in range(nr):
        for c in range(nc):
            x,y=pt(c,r); d.ellipse((x-2*SS,y-2*SS,x+2*SS,y+2*SS),fill=(48,58,68))
    used=set(); arrows=[]
    cells=[(c,r) for c in range(nc) for r in range(nr)]
    random.shuffle(cells)
    for start in cells:
        if start in used: continue
        path=[start]; used.add(start)
        L=random.randint(2,7)
        while len(path)<L:
            c,r=path[-1]
            opts=[(c+1,r),(c-1,r),(c,r+1),(c,r-1)]
            opts=[o for o in opts if 0<=o[0]<nc and 0<=o[1]<nr and o not in used]
            if not opts: break
            n=random.choice(opts); path.append(n); used.add(n)
        if len(path)<2: used.discard(start); continue
        arrows.append(path)
    stroke=P*0.13 if True else 6
    if headmode=="scaled": hl=P*0.6; hw=P*0.5
    else: hl=26; hw=22
    truth=[]
    for path in arrows:
        pts=[pt(*p) for p in path]
        # shorten line end to the head base
        (xe,ye),(xp,yp)=pts[-1],pts[-2]
        dx,dy=xe-xp,ye-yp; n=(dx*dx+dy*dy)**.5; ux,uy=dx/n,dy/n
        tip=(xe+ux*12*SS*(P/42 if headmode=="scaled" else 1), ye+uy*12*SS*(P/42 if headmode=="scaled" else 1))
        base=(tip[0]-ux*hl*SS, tip[1]-uy*hl*SS)
        pts2=pts[:-1]+[base]
        d.line(pts2,fill=col,width=int(stroke*SS),joint="curve")
        qx,qy=-uy,ux
        d.polygon([tip,(base[0]+qx*hw*SS/2,base[1]+qy*hw*SS/2),(base[0]-qx*hw*SS/2,base[1]-qy*hw*SS/2)],fill=col)
        c,r=path[-1]; pc,pr=path[-2]
        truth.append([x0+c*P,y0+r*P,c-pc,r-pr])
    im=im.resize((W,H),Image.LANCZOS); im.save(out)
    json.dump({"P":P,"truth":truth},open(out+".json","w"))
if __name__=="__main__":
    seed=int(sys.argv[1]); nc=int(sys.argv[2]); nr=int(sys.argv[3]); mode=sys.argv[4]
    gen(seed,nc,nr,headmode=mode,out=sys.argv[5])
