import json,subprocess,sys
jar,img=sys.argv[1],sys.argv[2]
env={"DUMP":"1"}
import os; e=dict(os.environ); e.update(env)
out=subprocess.run(["java","-cp",jar,"CoreTestKt",img],capture_output=True,text=True,env=e).stdout.splitlines()
t=json.load(open(img+".json"))["truth"]; P=json.load(open(img+".json"))["P"]
info=[l for l in out if l.startswith(os.path.basename(img))][0]
got=[tuple(map(float,l.split()[1:])) for l in out if l.startswith("ARROW")]
ok=0
for x,y,dx,dy in t:
    if any(abs(g[0]-x)<P*0.3 and abs(g[1]-y)<P*0.3 and int(g[2])==dx and int(g[3])==dy for g in got): ok+=1
wrong=len(got)-ok
print(os.path.basename(img),"true",len(t),"read ok",ok,"wrong/extra",wrong,"|",info.split("lattice")[0],info[info.find("arrows"):info.find("rounds")])
