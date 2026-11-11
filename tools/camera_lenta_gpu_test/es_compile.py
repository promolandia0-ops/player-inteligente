import ctypes, re, sys, os
egl=ctypes.CDLL("libEGL.so.1"); gl=ctypes.CDLL("libGLESv2.so.2")
egl.eglGetProcAddress.restype=ctypes.c_void_p
EGL_PLATFORM_SURFACELESS_MESA=0x31DD
egl.eglGetPlatformDisplay.restype=ctypes.c_void_p; egl.eglGetPlatformDisplay.argtypes=[ctypes.c_uint,ctypes.c_void_p,ctypes.c_void_p]
dpy=egl.eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA,None,None)
mj=ctypes.c_int(); mn=ctypes.c_int()
egl.eglInitialize.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p]
assert egl.eglInitialize(ctypes.c_void_p(dpy),ctypes.byref(mj),ctypes.byref(mn)), "init"
egl.eglBindAPI(0x30A0)  # OPENGL_ES_API
cfg=ctypes.c_void_p(); n=ctypes.c_int()
attr=(ctypes.c_int*5)(0x3040,0x40,0x3033,0x1,0x3038)  # RENDERABLE_TYPE=ES3, SURFACE_TYPE=?? 
attr=(ctypes.c_int*5)(0x3040,0x40,0x3038,0,0)
egl.eglChooseConfig.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_int,ctypes.c_void_p]
egl.eglChooseConfig(ctypes.c_void_p(dpy),attr,ctypes.byref(cfg),1,ctypes.byref(n))
ca=(ctypes.c_int*3)(0x3098,3,0x3038)
egl.eglCreateContext.restype=ctypes.c_void_p; egl.eglCreateContext.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p]
c=egl.eglCreateContext(ctypes.c_void_p(dpy),cfg,None,ca)
egl.eglMakeCurrent.argtypes=[ctypes.c_void_p]*4
assert egl.eglMakeCurrent(ctypes.c_void_p(dpy),None,None,ctypes.c_void_p(c)), "makecurrent"
gl.glGetString.restype=ctypes.c_char_p
print(gl.glGetString(0x1F01), gl.glGetString(0x1F02), gl.glGetString(0x8B8C))
src=open('/workspace/player-inteligente/app/src/main/java/br/geracaoai/player/CameraLentaGl.kt').read()
hdr=re.search(r'HEADER = "(.*?)"\n',src).group(1).encode().decode('unicode_escape')
oes=re.search(r'COPIA_OES = """(.*?)"""',src,re.S).group(1)
SH='/workspace/player-inteligente/app/src/main/assets/camera_lenta/'
def compile(kind,s,name):
    sh=gl.glCreateShader(kind)
    b=s.encode(); p=ctypes.c_char_p(b); ln=ctypes.c_int(len(b))
    gl.glShaderSource(sh,1,ctypes.byref(p),ctypes.byref(ln)); gl.glCompileShader(sh)
    ok=ctypes.c_int(); gl.glGetShaderiv(sh,0x8B81,ctypes.byref(ok))
    if not ok.value:
        buf=ctypes.create_string_buffer(2000); gl.glGetShaderInfoLog(sh,2000,None,buf); print("FALHOU",name,buf.value.decode()); return None
    return sh
def link(vs,fs,name):
    a=compile(0x8B31,vs,name+".vert"); b=compile(0x8B30,fs,name+".frag")
    if not a or not b: return False
    p=gl.glCreateProgram(); gl.glAttachShader(p,a); gl.glAttachShader(p,b); gl.glLinkProgram(p)
    ok=ctypes.c_int(); gl.glGetProgramiv(p,0x8B82,ctypes.byref(ok))
    if not ok.value:
        buf=ctypes.create_string_buffer(2000); gl.glGetProgramInfoLog(p,2000,None,buf); print("LINK FALHOU",name,buf.value.decode()); return False
    print("ok",name); return True
vs=hdr+open(SH+'quad.vert').read()
res=[]
for n in ['luma','down','smooth','interp','stat','conf','temporal','stat2','enhance']:
    res.append(link(vs,hdr+open(SH+n+'.frag').read(),n))
fl=open(SH+'flow.frag').read()
for r in (1,2,3): res.append(link(vs,hdr+f"#define RADIUS {r}\n"+fl,f"flow{r}"))
res.append(link(vs,oes,"oes"))
sys.exit(0 if all(res) else 1)
