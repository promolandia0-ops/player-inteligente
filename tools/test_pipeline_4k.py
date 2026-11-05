import subprocess,numpy as np,onnxruntime as ort,time,sys
from PIL import Image
s=ort.InferenceSession("320n.onnx");n=s.get_inputs()[0].name
def score(img):
    c=Image.new("RGB",(320,320));e=320/max(img.size);c.paste(img.resize((max(1,int(img.width*e)),max(1,int(img.height*e)))),(0,0))
    x=(np.asarray(c,np.float32)/255.).transpose(2,0,1)[None]
    o=s.run(None,{n:x})[0][0]; return {k:float(o[4+i].max()) for k,i in (("Peito",3),("Bunda",2),("Buceta",4))}, o.shape
f=sys.argv[1]; t=time.time()
raw=subprocess.run(["ffmpeg","-loglevel","error","-i",f,"-vf","fps=2,scale=320:320:force_original_aspect_ratio=decrease","-f","rawvideo","-pix_fmt","rgb24","-"],capture_output=True).stdout
fr=len(raw)//(320*180*3); print("frames",fr)
for i in range(fr):
    im=Image.frombytes("RGB",(320,180),raw[i*320*180*3:(i+1)*320*180*3]); sc,sh=score(im)
    if i<3: print(i*0.5,sh,{k:round(v,3) for k,v in sc.items()})
print("total %.2fs"%(time.time()-t))
for p in ["s/face2.jpg","s/picsum_64.jpg"]: print(p,score(Image.open(p).convert("RGB"))[0])
