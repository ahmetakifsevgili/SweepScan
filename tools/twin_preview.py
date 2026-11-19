"""
Draws the app's 3D scenes on a PC, with the app's own shaders (OpenGL ES 3.0 GLSL runs as is in
WebGL2), to check the look without a phone.

    tools\twin_preview.bat          (exports the scenes with a unit test, then writes the page)

or by hand:

    set TWIN_EXPORT=%TEMP%\twin_scenes.js
    gradlew :core:test --tests com.sweepscan.twin.TwinExportTest --rerun
    python tools\twin_preview.py %TEMP%\twin_scenes.js build\twin_preview.html

Labels are placed where the Kotlin projection put them, so the page also checks that maths.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RENDERER = os.path.join(ROOT, "app", "src", "main", "java", "com", "sweepscan", "twin", "TwinRenderer.kt")


def shaders():
    src = open(RENDERER, encoding="utf-8").read()
    found = dict(re.findall(r'private const val (\w+) = """(.*?)"""', src, re.S))
    return {k: found[k] for k in ("BOX_VS", "BOX_FS", "FLOOR_VS", "FLOOR_FS")}


PAGE = r"""<!doctype html>
<html><head><meta charset="utf-8"><title>SweepScan 3D preview</title>
<style>
 body { margin: 0; background: #0E1013; color: #E3E8ED; font: 14px system-ui, sans-serif; }
 .row { display: flex; flex-wrap: wrap; gap: 12px; padding: 12px; }
 figure { margin: 0; position: relative; }
 figcaption { padding: 4px 0; color: #8B95A1; }
 .view { position: relative; width: 360px; height: 667px; border-radius: 18px; overflow: hidden; border: 1px solid #2A3038; }
 .view canvas { position: absolute; inset: 0; width: 100%; height: 100%; }
 .sheet { position: absolute; left: 0; right: 0; bottom: 0; background: #171A1E; border-radius: 18px 18px 0 0; }
 #log { padding: 0 12px; color: #FF8A80; white-space: pre-wrap; }
</style></head><body>
<div class="row" id="views"></div>
<div id="log"></div>
<script>
const SHADERS = __SHADERS__;
</script>
<script>
__SCENES__
</script>
<script>
const log = (m) => { document.getElementById('log').textContent += m + '\n'; };
const STRIDE = 13, DENSITY = 3;

function compile(gl, type, src) {
  const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) log('shader: ' + gl.getShaderInfoLog(s));
  return s;
}
function program(gl, vs, fs) {
  const p = gl.createProgram();
  gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, vs));
  gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, fs));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) log('link: ' + gl.getProgramInfoLog(p));
  return p;
}
const CUBE = (() => {
  const f = [
    [0,0,1, -.5,-.5,.5, .5,-.5,.5, .5,.5,.5, -.5,.5,.5],
    [0,0,-1, .5,-.5,-.5, -.5,-.5,-.5, -.5,.5,-.5, .5,.5,-.5],
    [1,0,0, .5,-.5,.5, .5,-.5,-.5, .5,.5,-.5, .5,.5,.5],
    [-1,0,0, -.5,-.5,-.5, -.5,-.5,.5, -.5,.5,.5, -.5,.5,-.5],
    [0,1,0, -.5,.5,.5, .5,.5,.5, .5,.5,-.5, -.5,.5,-.5],
    [0,-1,0, -.5,-.5,-.5, .5,-.5,-.5, .5,-.5,.5, -.5,-.5,.5]];
  const out = [];
  for (const q of f) for (const i of [0,1,2,0,2,3]) out.push(q[3+i*3], q[4+i*3], q[5+i*3], q[0], q[1], q[2]);
  return new Float32Array(out);
})();

function draw(scene, time) {
  const fig = document.createElement('figure');
  const cap = document.createElement('figcaption');
  cap.textContent = scene.name + ' — ' + (scene.opaque.length / STRIDE) + ' boxes';
  const view = document.createElement('div'); view.className = 'view';
  const c = document.createElement('canvas'); c.width = scene.w; c.height = scene.h;
  const labels = document.createElement('canvas'); labels.width = scene.w; labels.height = scene.h;
  const sheet = document.createElement('div'); sheet.className = 'sheet';
  sheet.style.height = (scene.inset / scene.h * 100) + '%';
  view.append(c, labels, sheet); fig.append(cap, view); document.getElementById('views').append(fig);

  const gl = c.getContext('webgl2', { antialias: true, preserveDrawingBuffer: true });
  if (!gl) { log('no WebGL2'); return; }
  gl.viewport(0, 0, scene.w, scene.h);
  gl.clearColor(0x0E/255, 0x10/255, 0x13/255, 1);
  gl.enable(gl.DEPTH_TEST); gl.enable(gl.CULL_FACE);
  const box = program(gl, SHADERS.BOX_VS, SHADERS.BOX_FS);
  const floor = program(gl, SHADERS.FLOOR_VS, SHADERS.FLOOR_FS);
  const cube = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, cube); gl.bufferData(gl.ARRAY_BUFFER, CUBE, gl.STATIC_DRAW);
  function vao(data) {
    const v = gl.createVertexArray(); gl.bindVertexArray(v);
    gl.bindBuffer(gl.ARRAY_BUFFER, cube);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 24, 0);
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 24, 12);
    const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data), gl.STATIC_DRAW);
    const a = (i, n, off) => { gl.enableVertexAttribArray(i); gl.vertexAttribPointer(i, n, gl.FLOAT, false, STRIDE*4, off); gl.vertexAttribDivisor(i, 1); };
    a(2, 3, 0); a(3, 3, 12); a(4, 4, 24); a(5, 3, 40);
    gl.bindVertexArray(null);
    return v;
  }
  const vo = vao(scene.opaque), vt = vao(scene.transparent);
  const fv = gl.createVertexArray(); gl.bindVertexArray(fv);
  const fb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, fb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(scene.floor), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
  gl.bindVertexArray(null);

  gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  gl.useProgram(floor);
  gl.uniformMatrix4fv(gl.getUniformLocation(floor, 'uViewProj'), false, scene.vp);
  gl.uniform3fv(gl.getUniformLocation(floor, 'uEye'), scene.eye);
  gl.uniform2fv(gl.getUniformLocation(floor, 'uFog'), scene.fog);
  gl.disable(gl.CULL_FACE); gl.bindVertexArray(fv); gl.drawArrays(gl.TRIANGLES, 0, scene.floor.length / 3); gl.enable(gl.CULL_FACE);
  gl.useProgram(box);
  gl.uniformMatrix4fv(gl.getUniformLocation(box, 'uViewProj'), false, scene.vp);
  gl.uniform3fv(gl.getUniformLocation(box, 'uEye'), scene.eye);
  gl.uniform1f(gl.getUniformLocation(box, 'uTime'), time);
  gl.uniform2fv(gl.getUniformLocation(box, 'uFog'), scene.fog);
  gl.bindVertexArray(vo); gl.drawArraysInstanced(gl.TRIANGLES, 0, 36, scene.opaque.length / STRIDE);
  if (scene.transparent.length) {
    gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE); gl.depthMask(false); gl.disable(gl.CULL_FACE);
    gl.bindVertexArray(vt); gl.drawArraysInstanced(gl.TRIANGLES, 0, 36, scene.transparent.length / STRIDE);
    gl.depthMask(true); gl.disable(gl.BLEND); gl.enable(gl.CULL_FACE);
  }
  const err = gl.getError(); if (err) log(scene.name + ': GL error ' + err);

  // Labels, as DepotTwinView's overlay draws them.
  const g = labels.getContext('2d'); g.textAlign = 'center';
  const near = true;
  for (const l of scene.labels) {
    if (!l.v || l.x < -40 || l.x > scene.w + 40 || l.y < -40 || l.y > scene.h + 40) continue;
    if (l.s === 'DEPOT') { g.font = `bold ${15*DENSITY}px system-ui`; g.fillStyle = '#F2F5F7'; g.shadowColor = '#000c'; g.shadowBlur = 9; g.fillText(l.t, l.x, l.y); g.shadowBlur = 0; }
    else if (l.s === 'ROW') {
      g.font = `bold ${(l.a ? 12 : 11)*DENSITY}px system-ui`;
      const w = g.measureText(l.t).width + 12*DENSITY, h = 18*DENSITY;
      g.fillStyle = l.a ? '#FFB300' : '#1B2026D9';
      g.beginPath(); g.roundRect(l.x - w/2, l.y - h/2, w, h, h/2); g.fill();
      g.fillStyle = l.a ? '#241A00' : '#E3E8ED'; g.fillText(l.t, l.x, l.y + 4*DENSITY);
    } else if (l.s === 'STOP') { g.fillStyle = '#FFB300'; g.beginPath(); g.arc(l.x, l.y, 12*DENSITY, 0, 7); g.fill(); g.fillStyle = '#241A00'; g.font = `bold ${13*DENSITY}px system-ui`; g.fillText(l.t, l.x, l.y + 4.5*DENSITY); }
    else { g.font = `bold ${12*DENSITY}px system-ui`; g.fillStyle = '#3DDC97'; g.fillText(l.t, l.x, l.y); }
  }
}
try { for (const s of window.SCENES) draw(s, 0.4); } catch (e) { log(String(e && e.stack || e)); }
</script>
</body></html>
"""


def main():
    scenes_js, out = sys.argv[1], sys.argv[2]
    page = PAGE.replace("__SHADERS__", json.dumps(shaders())).replace("__SCENES__", open(scenes_js, encoding="utf-8").read())
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    open(out, "w", encoding="utf-8").write(page)
    print(out)


if __name__ == "__main__":
    main()
