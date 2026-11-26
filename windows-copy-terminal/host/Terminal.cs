using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Linq;
using System.Media;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Obj = System.Collections.Generic.Dictionary<string,object>;

[assembly:System.Reflection.AssemblyVersion("4.2.18.0")]
[assembly:System.Reflection.AssemblyProduct("合约跟单台 · Windows")]
internal static class Program {
    [STAThread] static void Main() {
        using(var mutex=new Mutex(true,@"Local\CopyTerminal.Windows",out bool first)){
            Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
            if(!first){MessageBox.Show("合约跟单台已运行，请从系统托盘打开。","合约跟单台");return;}
            try{Application.Run(new Terminal());}catch(Exception e){MessageBox.Show(e.Message,"合约跟单台");}
        }
    }
}
sealed class SignalWindow:Form {
    public readonly WebView2 Browser=new WebView2 { Dock=DockStyle.Fill };
    public bool Inspecting=false;
    protected override bool ShowWithoutActivation {get{return !Inspecting;}}
    public SignalWindow(){
        Text="币安官网 · 公开组合"; ShowInTaskbar=false;StartPosition=FormStartPosition.Manual;Bounds=new Rectangle(-24000,-24000,1120,850);
        Controls.Add(Browser);
        FormClosing+=(s,e)=>{if(e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Inspecting=false;ShowInTaskbar=false;Location=new Point(-24000,-24000);}};
    }
    public void Inspect(){Inspecting=true;ShowInTaskbar=true;CenterToScreen();Show();Activate();}
}
sealed class Terminal:Form{
    static readonly string Root=AppDomain.CurrentDomain.BaseDirectory;
    static readonly string HomePath=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"CopyTerminal");
    const string Local="https://terminal.localhost/";
    readonly JavaScriptSerializer json=new JavaScriptSerializer { MaxJsonLength=16000000,RecursionLimit=160 };
    readonly WebView2 face=new WebView2 { Dock=DockStyle.Fill };
    readonly NotifyIcon tray=new NotifyIcon(); readonly Dictionary<string,SignalWindow> pages=new Dictionary<string,SignalWindow>();
    readonly DesktopNotices notifications;
    readonly Icon logo; readonly SoundPlayer tone=new SoundPlayer(Path.Combine(Root,"order_alert.wav"));
    readonly HashSet<string> inFlight=new HashSet<string>();
    Process engine;CoreWebView2Environment publicEnv;
    readonly object inputLock=new object();bool closing,exiting,trading,ready;
    [DllImport("kernel32.dll")]static extern uint SetThreadExecutionState(uint flags);
    static Obj O(params object[] values){var o=new Obj();for(int i=0;i<values.Length;i+=2)o[(string)values[i]]=values[i+1];return o;}
    static string S(Obj o,string key){return o.ContainsKey(key)?Convert.ToString(o[key]):"";}
    static bool B(Obj o,string key){return o.ContainsKey(key)&&o[key] is bool &&(bool)o[key];}
    static bool Trusted(string address){return address==Local || address==Local+"index.html";}
    static bool Public(Uri uri){return uri!=null && uri.Scheme=="https"&&uri.Host=="www.binance.com"&&uri.Port==443&&uri.UserInfo=="";}
    public Terminal(){
        Text="合约跟单台";StartPosition=FormStartPosition.CenterScreen;AutoScaleMode=AutoScaleMode.Dpi;
        MinimumSize=new Size(620,440);Width=Math.Min(1480,Screen.PrimaryScreen.WorkingArea.Width);Height=Math.Min(920,Screen.PrimaryScreen.WorkingArea.Height);
        BackColor=Color.FromArgb(246,248,251);Controls.Add(face);
        notifications = new DesktopNotices(this, PlayNotice);
        logo=Icon.ExtractAssociatedIcon(Application.ExecutablePath);Icon=logo;tray.Icon=logo;tray.Text="合约跟单台";tray.Visible=true;
        var menu=new ContextMenuStrip();menu.Items.Add("打开跟单台",null,(s,e)=>Restore());menu.Items.Add("停止并退出",null,async(s,e)=>await Quit());tray.ContextMenuStrip=menu;tray.DoubleClick+=(s,e)=>Restore();
        FormClosing+=(s,e)=>{if(!closing&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Hide();notifications.Push("合约跟单台","窗口已收至托盘，运行中的任务会继续。",false);}};
        Shown+=async(s,e)=>{try{await Start();}catch(Exception ex){MessageBox.Show("启动失败。请检查完整解压目录以及 Microsoft Edge WebView2 Runtime。\n\n"+ex.Message,Text);closing=true;Close();}};
        FormClosed+=(s,e)=>{NetworkChange.NetworkAvailabilityChanged-=NetworkChanged;SetThreadExecutionState(0x80000000);tray.Visible=false;tray.Dispose();
            if(engine!=null){try{engine.StandardInput.Close();if(!engine.WaitForExit(3500))engine.Kill();}catch{}}
            foreach(var w in pages.Values)w.Dispose();notifications.Dispose();tone.Dispose();};
    }
    void NetworkChanged(object sender,NetworkAvailabilityEventArgs e){try{BeginInvoke(new Action(()=>{if(ready&&!closing)Write(O("network",e.IsAvailable));}));}catch(InvalidOperationException){}}
    void Restore(){Show();if(WindowState==FormWindowState.Minimized)WindowState=FormWindowState.Normal;Activate();}
    async Task Start(){
        Directory.CreateDirectory(HomePath);Integrity.Check(Root);CoreWebView2Environment.SetLoaderDllFolderPath(Root);
        var env=await CoreWebView2Environment.CreateAsync(null,Path.Combine(HomePath,"DesktopView"));
        publicEnv=await CoreWebView2Environment.CreateAsync(null,Path.Combine(HomePath,"OfficialView"));
        await face.EnsureCoreWebView2Async(env);var core=face.CoreWebView2;
        core.Settings.AreHostObjectsAllowed=false;core.Settings.AreDevToolsEnabled=false;core.Settings.AreDefaultContextMenusEnabled=false;
        core.SetVirtualHostNameToFolderMapping("terminal.localhost",Path.Combine(Root,"ui"),CoreWebView2HostResourceAccessKind.Deny);
        core.NavigationStarting+=(s,e)=>{if(!Trusted(e.Uri))e.Cancel=true;};
        core.NewWindowRequested+=(s,e)=>e.Handled=true;core.DownloadStarting+=(s,e)=>e.Cancel=true;core.PermissionRequested+=(s,e)=>e.State=CoreWebView2PermissionState.Deny;
        core.WebMessageReceived+=(s,e)=>{if(Trusted(e.Source)&&Trusted(core.Source))ReceiveUI(e.WebMessageAsJson);};
        var info=new ProcessStartInfo(Path.Combine(Root,"runtime","bin","java.exe"),"-XX:-UsePerfData -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8 -Djava.net.useSystemProxies=true -cp \"lib/terminal.jar;lib/json.jar;lib/sqlite.jar;lib/slf4j-api.jar;lib/slf4j-nop.jar\" com.localcopy.terminal.DesktopApp");
        info.WorkingDirectory=Root;info.UseShellExecute=false;info.CreateNoWindow=true;info.RedirectStandardInput=true;info.RedirectStandardOutput=true;info.RedirectStandardError=true;info.StandardOutputEncoding=new UTF8Encoding(false);info.StandardErrorEncoding=new UTF8Encoding(false);
        foreach(var k in new[]{"JDK_JAVA_OPTIONS","JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","CLASSPATH"})info.EnvironmentVariables.Remove(k);
        info.EnvironmentVariables["LOCALAPPDATA"]=Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        engine=new Process{StartInfo=info,EnableRaisingEvents=true};
        engine.OutputDataReceived+=(s,e)=>{if(e.Data!=null&&!IsDisposed)try{BeginInvoke(new Action(()=>ReceiveEngine(e.Data)));}catch(InvalidOperationException){}};
        engine.Exited+=(s,e)=>{if(!IsDisposed&&!closing)try{BeginInvoke(new Action(()=>{if(!closing){ready=false;SetThreadExecutionState(0x80000000);Restore();MessageBox.Show("跟单引擎已停止。请重新打开软件，并核对账户仓位及在途委托。",Text);closing=true;Close();}}));}catch(InvalidOperationException){}};
        engine.Start();engine.BeginOutputReadLine();engine.BeginErrorReadLine();ready=true;NetworkChange.NetworkAvailabilityChanged+=NetworkChanged;try{Write(O("network",NetworkInterface.GetIsNetworkAvailable()));}catch{}Write(O("op","restore","id","@restore","data",O()));core.Navigate(Local+"index.html");
    }
    void Write(Obj o){
        byte[] bytes=Encoding.UTF8.GetBytes(json.Serialize(o)+"\n");
        lock(inputLock){if(engine==null||engine.HasExited)throw new IOException("交易引擎已退出");
            var stream=engine.StandardInput.BaseStream;stream.Write(bytes,0,bytes.Length);stream.Flush();}
    }
    void Post(Obj o){if(face.CoreWebView2!=null&&Trusted(face.CoreWebView2.Source))face.CoreWebView2.PostWebMessageAsJson(json.Serialize(o));}
    async Task Quit(){
        if(exiting)return;
        if(!ready){closing=true;Close();return;}
        if(trading&&MessageBox.Show("将停止本机跟单并退出。已持有的仓位仍在交易所。","停止并退出",MessageBoxButtons.OKCancel,MessageBoxIcon.Warning)!=DialogResult.OK)return;
        exiting=true;try{Write(O("id","@quit","op","quit","data",O()));}catch{closing=true;Close();}await Task.CompletedTask;
    }
    void ReceiveUI(string raw){
        string id="";try{
            var msg=json.Deserialize<Obj>(raw);id=S(msg,"id");if(!Guid.TryParse(id,out var unused)||!inFlight.Add(id))return;
            string op=S(msg,"op");Obj d=msg.ContainsKey("data")?(Obj)msg["data"]:O();
            if(op=="clip"){string v=S(d,"value");if(!new[]{"1218112316","0x46CE8C06265b4d7667AA2cb693B71193f9F0C6ae","wuy15525@gmail.com","@wuyanzuWa"}.Contains(v))throw new Exception("复制项无效");Clipboard.SetText(v);inFlight.Remove(id);Post(O("ui",id,"data",O("ok",true)));return;}
            if(op=="start"&&S(d,"mode")=="mainnet-web"){
                if(MessageBox.Show(this,"当前为实盘跟单。确认后，软件可以用你的币安账户提交真实合约委托。","确认启动实盘跟单",MessageBoxButtons.YesNo,MessageBoxIcon.Warning,MessageBoxDefaultButton.Button2)!=DialogResult.Yes)throw new Exception("已取消实盘跟单");
                d["liveConfirmed"]=true;
            }
            if(op=="exit"){inFlight.Remove(id);Post(O("ui",id,"data",O()));var quitTask=Quit();return;}
            if(!new[]{"state","history","introSeen","accept","rules","symbols","credentials","account","start","stop","pause","resume","retry-web","initial-choice","copy-factor","region","cancel","export","sound"}.Contains(op))throw new Exception("未识别的操作");
            if(op=="quit"||op=="restore")throw new Exception("操作不可用");
            Write(O("op",op,"id",id,"data",d));
        }catch(Exception e){inFlight.Remove(id);Post(O("ui",id,"error",e.Message));}
    }
    async void ReceiveEngine(string raw){
        Obj msg;try{msg=json.Deserialize<Obj>(raw);}catch{return;}
        if(msg.ContainsKey("active")){trading=B(msg,"active");SetThreadExecutionState(trading?0x80000001u:0x80000000u);return;}
        if(msg.ContainsKey("ui")){
            string id=S(msg,"ui");
            if(id=="@quit"){exiting=false;if(msg.ContainsKey("error"))MessageBox.Show(S(msg,"error"),Text);else{closing=true;Close();}return;}
            if(!inFlight.Remove(id))return;
            Post(msg);return;
        }
        if(!msg.ContainsKey("host"))return;
        var reply=O("reply",S(msg,"id"));
        try{reply["data"]=await HostCall(S(msg,"host"),(Obj)msg["data"]);}catch(Exception e){reply["error"]=e.Message;}
        try{Write(reply);}catch{}
    }
    void PlayNotice()
    {
        try { tone.Play(); }
        catch { try { SystemSounds.Exclamation.Play(); } catch { } }
    }

    async Task<Obj> HostCall(string op,Obj d){
        if(op=="protect"||op=="unprotect"){
            var input=Convert.FromBase64String(S(d,"value"));try{var result=op=="protect"?ProtectedData.Protect(input,null,DataProtectionScope.CurrentUser):ProtectedData.Unprotect(input,null,DataProtectionScope.CurrentUser);
                try{return O("value",Convert.ToBase64String(result));}finally{Array.Clear(result,0,result.Length);}
            }finally{Array.Clear(input,0,input.Length);}
        }
        if(op=="notice")
        {
            notifications.Push(S(d,"title"), S(d,"message"), B(d,"sound"));
            return O("ok", true);
        }
        if(op=="saveCsv"){
            string name=S(d,"temp");if(Path.GetFileName(name)!=name||!name.StartsWith("history-")||!name.EndsWith(".csv"))throw new Exception("无效导出文件");
            using(var dialog=new SaveFileDialog{Filter="CSV 文件 (*.csv)|*.csv",FileName="成交记录-"+S(d,"portfolio")+".csv"}){
                if(dialog.ShowDialog(this)!=DialogResult.OK)return O("cancelled",true);
                File.Copy(Path.Combine(HomePath,name),dialog.FileName,true);return O("ok",true);}
        }
        if(!op.StartsWith("web."))throw new Exception("未知桌面操作");
        string id=S(d,"page");var url=new Uri(S(d,"url"));
        if(!Public(url)||!System.Text.RegularExpressions.Regex.IsMatch(url.AbsolutePath,@"^/zh-CN/copy-trading/lead-details/[0-9]{8,25}$"))throw new Exception("官网组合地址无效");
        if(op=="web.create"){
            var pane=new SignalWindow();try{
                pane.Show();await pane.Browser.EnsureCoreWebView2Async(publicEnv);var core=pane.Browser.CoreWebView2;
                core.Settings.IsWebMessageEnabled=false;core.Settings.AreHostObjectsAllowed=false;core.Settings.AreDevToolsEnabled=false;
                core.NewWindowRequested+=(s,e)=>e.Handled=true;core.DownloadStarting+=(s,e)=>e.Cancel=true;core.PermissionRequested+=(s,e)=>e.State=CoreWebView2PermissionState.Deny;
                core.NavigationStarting+=(s,e)=>{if(!Uri.TryCreate(e.Uri,UriKind.Absolute,out var u)||!Public(u)||u.AbsolutePath.TrimEnd('/')!=url.AbsolutePath){
                    e.Cancel=true;if(pages.ContainsKey(id))try{Write(O("webError",id,"redirect",u==null?"":u.GetLeftPart(UriPartial.Path)));}catch{}}};
                core.WebResourceResponseReceived+=(s,e)=>{try{var u=new Uri(e.Request.Uri);bool main=Public(u)&&u.AbsolutePath.TrimEnd('/')==url.AbsolutePath;
                    if(Public(u)&&(main||u.AbsolutePath.EndsWith("/lead-portfolio/order-history"))&&e.Response.StatusCode>=400){
                        string retry=e.Response.Headers.Contains("Retry-After")?e.Response.Headers.GetHeader("Retry-After"):"";WebError(id,e.Response.StatusCode,main,"",retry);}
                }catch{}};
                core.NavigationCompleted+=(s,e)=>{if(!e.IsSuccess&&e.WebErrorStatus!=CoreWebView2WebErrorStatus.OperationCanceled&&e.HttpStatusCode==0)WebError(id,0,true,"官网连接失败 · "+e.WebErrorStatus,"");};
                core.ProcessFailed+=(s,e)=>WebError(id,0,true,"官网浏览器进程中断","");
                pages.Add(id,pane);return O();
            }catch{pane.Dispose();throw;}
        }
        SignalWindow w;if(!pages.TryGetValue(id,out w))throw new Exception("官网浏览器已关闭");var browser=w.Browser.CoreWebView2;
        switch(op){
            case "web.load": browser.Navigate(url.AbsoluteUri);break;
            case "web.reload":browser.Reload();break;
            case "web.stop": browser.Stop();break;
            case "web.show":w.Inspect();break;
            case "web.destroy":pages.Remove(id);w.Dispose();break;
            case "web.eval":
                Uri at;if(!Uri.TryCreate(browser.Source,UriKind.Absolute,out at)||!Public(at)||at.AbsolutePath.TrimEnd('/')!=url.AbsolutePath)return O("value","null");
                return O("value",await browser.ExecuteScriptAsync(S(d,"script")));
            default:throw new Exception("未知网页操作");
        }
        return O();
    }
    void WebError(string id,int status,bool main,string text,string retry){if(pages.ContainsKey(id))try{Write(O("webError",id,"status",status,"main",main,"message",text,"retry",retry));}catch{}}
}
