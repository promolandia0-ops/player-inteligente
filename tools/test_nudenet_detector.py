import glob,time,onnxruntime as ort
from nudenet import NudeDetector
d=NudeDetector(model_path="320n.onnx")
s=d.onnx_session;print([ (i.name,i.shape) for i in s.get_inputs()],[(o.name,o.shape) for o in s.get_outputs()])
for f in sorted(glob.glob("s/*")):
  t=time.time();r=d.detect(f);print(f,"%.0fms"%((time.time()-t)*1000),[(x['class'],round(x['score'],2)) for x in r])
