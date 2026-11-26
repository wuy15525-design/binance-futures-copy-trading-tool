from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import urlopen,Request
import sys,time,os,zipfile,tarfile,shutil,hashlib
root=Path(__file__).resolve().parents[1]/'build/deps'
root.mkdir(parents=True,exist_ok=True)
nu='https://api.nuget.org/v3-flatcontainer/'
mvn='https://maven-central.storage-download.googleapis.com/maven2/'
files={
    'winjre.zip':'https://cdn.azul.com/zulu/bin/zulu21.54.201-ca-jre21.0.13.1-win_x64.zip',
    'dotnet.tgz':'https://dotnetcli.blob.core.windows.net/dotnet/Runtime/8.0.34/dotnet-runtime-8.0.34-linux-x64.tar.gz',
    'csc.zip':nu+'microsoft.net.compilers.toolset/4.11.0/microsoft.net.compilers.toolset.4.11.0.nupkg',
    'ref.zip':nu+'microsoft.netframework.referenceassemblies.net472/1.0.3/microsoft.netframework.referenceassemblies.net472.1.0.3.nupkg',
    'webview.zip':nu+'microsoft.web.webview2/1.0.3179.45/microsoft.web.webview2.1.0.3179.45.nupkg',
    'json.jar':mvn+'org/json/json/20240303/json-20240303.jar',
    'sqlite.jar':mvn+'org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar',
    'slf4j-api.jar':mvn+'org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar',
    'slf4j-nop.jar':mvn+'org/slf4j/slf4j-nop/2.0.17/slf4j-nop-2.0.17.jar',
}
import json
hashes=json.loads((Path(__file__).parent/'dependencies.json').read_text())
def job(i):
    fn,u=i;p=root/fn
    for t in range(3):
        try:
            with urlopen(Request(u,headers={'User-Agent':'CopyTerminal-Windows-build'}),timeout=50) as r,open(str(p)+'.part','wb') as f:
                shutil.copyfileobj(r,f,1024*1024)
            if hashlib.sha256(Path(str(p)+'.part').read_bytes()).hexdigest()!=hashes[fn]:raise ValueError('Dependency checksum mismatch: '+fn)
            os.replace(str(p)+'.part',p)
            if fn.endswith('.zip'):
                with zipfile.ZipFile(p) as z: z.extractall(root/fn[:-4])
            if fn=='dotnet.tgz':
                with tarfile.open(p) as z:z.extractall(root/'dotnet',filter='data')
            print(fn,p.stat().st_size,hashlib.sha256(p.read_bytes()).hexdigest(),flush=True);return
        except Exception as e: print(fn,t+1,e,flush=True);time.sleep(1+t)
    raise RuntimeError(fn)
with ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(job,files.items()))
