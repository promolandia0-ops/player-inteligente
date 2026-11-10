import numpy as np, moderngl, sys, os
SH = '/workspace/player-inteligente/app/src/main/assets/camera_lenta/'
HDR = "#version 330 core\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
ctx = moderngl.create_standalone_context(backend='egl', require=330)
def src(n, defs=""):
    s = open(SH+n).read()
    return HDR + defs + s
vs = src('quad.vert')
def prog(f, defs=""):
    return ctx.program(vertex_shader=vs, fragment_shader=src(f, defs))
vao_cache = {}
def tex(w,h,comp,dtype,data=None,filt=moderngl.LINEAR):
    t = ctx.texture((w,h), comp, data, dtype=dtype)
    t.filter=(filt,filt); t.repeat_x=False; t.repeat_y=False
    return t
def run(p, out, uniforms, texs):
    fbo = ctx.framebuffer(out)
    fbo.use(); ctx.viewport=(0,0,out.width,out.height)
    for i,(name,t) in enumerate(texs.items()):
        t.use(i); p[name].value=i
    for k,v in uniforms.items():
        if k in p: p[k].value=v
    vao = ctx.vertex_array(p, [])
    vao.render(moderngl.TRIANGLES, vertices=3)
    ctx.finish()

class Interp:
    W0=384
    def __init__(self, w, h):
        self.w,self.h=w,h
        self.sizes=[]
        W=self.W0; H=int(round(self.W0*h/w))
        for i in range(4): self.sizes.append((max(W>>i,8),max(H>>i,8)))
        self.luma=prog('luma.frag'); self.down=prog('down.frag'); self.smooth=prog('smooth.frag'); self.interp=prog('interp.frag')
        self.flowp={r:prog('flow.frag',f"#define RADIUS {r}\n") for r in (1,2,3)}
    def pyramid(self, frame):
        out=[]
        taps=max(1,min(4,int(round(self.w/self.sizes[0][0]/1))))
        t0=tex(*self.sizes[0],1,'f1'); run(self.luma,t0,{'uTaps':min(taps,4),'uDst':self.sizes[0]},{'uSrc':frame}); out.append(t0)
        for i in range(1,4):
            t=tex(*self.sizes[i],1,'f1'); run(self.down,t,{},{'uSrc':out[-1]}); out.append(t)
        return out
    def flow(self, pa, pb):
        radii=[1,2,2,3]   # nível 0..3
        prior=None
        for lvl in (3,2,1,0):
            f=tex(*self.sizes[lvl],4,'f2')
            f.filter=(moderngl.LINEAR,moderngl.LINEAR)
            run(self.flowp[radii[lvl]],f,{'uPriorOn':0.0 if prior is None else 1.0,'uLambda':0.004},
                {'uA':pa[lvl],'uB':pb[lvl],'uPrior':prior if prior is not None else pa[lvl]})
            prior=f
        # nearest filter p/ texelFetch já usa; mediana no nível 0
        s=tex(*self.sizes[0],4,'f2'); run(self.smooth,s,{},{'uFlow':prior})
        return s
    def prepare(self, A, B):
        fa=tex(self.w,self.h,4,'f1',A.astype('f4').tobytes() if False else None)
    def make_frame(self, img):
        # img float32 HxWx3 in 0..1, origem topo → textura (origem baixo): inverte
        d=np.dstack([img,np.ones(img.shape[:2],np.float32)])[::-1].astype('f4')
        t=tex(self.w,self.h,4,'f4',d.tobytes()); return t
    def prep(self, A, B):
        self.tA=self.make_frame(A); self.tB=self.make_frame(B)
        pa=self.pyramid(self.tA); pb=self.pyramid(self.tB)
        self.fab=self.flow(pa,pb); self.fba=self.flow(pb,pa)
    def frame(self, t, modo=0):
        out=tex(self.w,self.h,4,'f4')
        run(self.interp,out,{'uT':float(t),'uModo':modo},{'uA':self.tA,'uB':self.tB,'uFab':self.fab,'uFba':self.fba})
        a=np.frombuffer(out.read(),dtype='f4').reshape(self.h,self.w,4)[::-1,:,:3]
        return np.clip(a,0,1)

def psnr(a,b):
    m=np.mean((a-b)**2); return 99.0 if m<1e-10 else 10*np.log10(1/m)
