"""Package the x64 Windows app from staged, version-pinned dependencies."""
from pathlib import Path
import os,subprocess,sys,shutil,zipfile,hashlib,json
from PIL import Image
project=Path(__file__).resolve().parents[1];os.chdir(project)
deps=Path(os.environ.get('DEPS',project/'build/deps')).resolve()
jsonjar=Path(os.environ.get('JSON_JAR',deps/'json.jar')).resolve()
stage=project/'build/CopyTerminal';stage.mkdir(exist_ok=True,parents=True)
lib=stage/'lib';lib.mkdir(exist_ok=True)
shutil.copytree(next((deps/'winjre').iterdir()),stage/'runtime',dirs_exist_ok=True)
shutil.copytree('ui',stage/'ui',dirs_exist_ok=True)
shutil.copyfile('resources/order_alert.wav',stage/'order_alert.wav')
for name in ['sqlite.jar','slf4j-api.jar','slf4j-nop.jar']:shutil.copy2(deps/name,lib/name)
shutil.copy2(jsonjar,lib/'json.jar')
subprocess.run(['sh','build-tools/compile.sh'],check=True)
with zipfile.ZipFile(lib/'terminal.jar','w',zipfile.ZIP_DEFLATED) as z:
 for f in Path('build/classes').rglob('*.class'):z.write(f,f.relative_to('build/classes'))
 z.write('resources/website-capture.js','website-capture.js')
wv=deps/'webview';framework=deps/'ref/build/.NETFramework/v4.7.2'
for name in ['Core','WinForms']:shutil.copy2(wv/('lib/net462/Microsoft.Web.WebView2.'+name+'.dll'),stage)
shutil.copy2(wv/'runtimes/win-x64/native/WebView2Loader.dll',stage)
shutil.copy2('host/CopyTerminal.exe.config',stage)
licenses=stage/'licenses';licenses.mkdir(exist_ok=True)
for src,dst in [(wv/'LICENSE.txt','WebView2.txt'),(wv/'NOTICE.txt','WebView2-notice.txt'),(Path('resources/font-license.txt'),'font.txt')]:shutil.copyfile(src,licenses/dst)
for jar in lib.glob('*.jar'):
 with zipfile.ZipFile(jar) as z:
  for member in z.namelist():
   if not member.endswith('/') and ('license' in member.lower() or 'notice' in member.lower()) and 'meta-inf/' in member.lower() and not member.endswith('.class'):
    (licenses/(jar.stem+'-'+Path(member).name)).write_bytes(z.read(member))
if Path('使用说明.txt').exists():shutil.copy2('使用说明.txt',stage)
ico=Path('build/terminal.ico');im=Image.open('resources/coin.png').convert('RGBA');im.thumbnail((256,256));im.save(ico,format='ICO',sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
manifest=''.join(digest(f)+'  '+f.relative_to(stage).as_posix()+'\n' for f in sorted(stage.rglob('*')) if f.is_file() and f.name not in {'integrity.sha256','CopyTerminal.exe','使用说明.txt'} and 'licenses' not in f.relative_to(stage).parts)
manifestPath=stage/'integrity.sha256';manifestPath.write_bytes(manifest.encode())
integrity=Path('build/Integrity.cs')
integrity.write_text('''using System;using System.IO;using System.Security.Cryptography;class Integrity{
static string Digest(string path){using(var h=SHA256.Create())using(var stream=File.OpenRead(path)){return BitConverter.ToString(h.ComputeHash(stream)).Replace("-","").ToLowerInvariant();}}
internal static void Check(string root){string file=Path.Combine(root,"integrity.sha256");
if(Digest(file)!="'''+digest(manifestPath)+'''")throw new IOException("发布清单损坏，请重新解压原始发布包");
foreach(string line in File.ReadAllLines(file)){if(line.Length<67||Digest(Path.Combine(root,line.Substring(66)))!=line.Substring(0,64))throw new IOException("程序文件不完整，请重新解压原始发布包");}
}}''')
refs=[str(framework/(name+'.dll')) for name in ['mscorlib','System','System.Core','System.Windows.Forms','System.Drawing','System.Web.Extensions','System.Security','System.Xml']]+[str(x) for x in stage.glob('Microsoft*.dll')]
args=[str(deps/'dotnet/dotnet'),str(deps/'csc/tasks/netcore/bincore/csc.dll'),'/nologo','/target:winexe','/platform:x64','/nostdlib+','/optimize+','/deterministic+','/win32manifest:host/app.manifest','/win32icon:'+str(ico),'/out:'+str(stage/'CopyTerminal.exe')]+['/r:'+x for x in refs]+['host/Terminal.cs','host/DesktopNotices.cs','host/ToastPlacement.cs',str(integrity)]
subprocess.run(args,check=True)
print(stage/'CopyTerminal.exe')
