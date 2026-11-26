package com.localcopy.terminal;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

class DesktopContext {
    static volatile boolean online=true;
    static final Path home=Path.of(System.getProperty("terminal.home", Path.of(System.getenv().getOrDefault("LOCALAPPDATA",Path.of(System.getProperty("user.home"),"AppData","Local").toString()),"CopyTerminal").toString()));
    static final Preferences prefs=new Preferences(home);
    DesktopContext getApplicationContext(){return this;}
}
final class AtomicFile {
    final File target, temporary;
    AtomicFile(File target){this.target=target; temporary=new File(target+".pending");}
    byte[] readFully() throws IOException {try(FileInputStream in=new FileInputStream(target)){return in.readAllBytes();}}
    FileOutputStream startWrite() throws IOException{return new FileOutputStream(temporary);}
    void finishWrite(FileOutputStream out) throws IOException {
        out.getFD().sync();out.close();
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    void failWrite(FileOutputStream out){try{out.close();Files.deleteIfExists(temporary.toPath());}catch(IOException ignored){}}
}
final class Preferences {
    final AtomicFile file; JSONObject values;
    Preferences(Path directory){
        try{Files.createDirectories(directory);file=new AtomicFile(directory.resolve("settings.json").toFile());
            values=file.target.exists()?new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8)):new JSONObject();
        }catch(Exception e){throw new IllegalStateException("本地设置读取失败",e);}
    }
    synchronized String getString(String k,String d){return values.optString(k,d);}
    synchronized long getLong(String k,long d){return values.optLong(k,d);}
    synchronized boolean getBoolean(String k,boolean d){return values.optBoolean(k,d);}
    synchronized boolean contains(String k){return values.has(k);}
    synchronized Set<String> getStringSet(String k,Set<String> d){JSONArray a=values.optJSONArray(k);if(a==null)return d;Set<String>s=new HashSet<>();for(Object x:a)s.add((String)x);return s;}
    Editor edit(){return new Editor();}
    class Editor{
        final Map<String,Object> changes=new LinkedHashMap<>();
        Editor putString(String k,String v){changes.put(k,v);return this;}
        Editor putLong(String k,long v){changes.put(k,v);return this;}
        Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
        Editor putStringSet(String k,Set<String> v){changes.put(k,new JSONArray(v));return this;}
        Editor remove(String k){changes.put(k,null);return this;}
        boolean commit(){synchronized(Preferences.this){
            JSONObject next=new JSONObject(values.toString());changes.forEach(next::put);
            FileOutputStream out=null;
            try{out=file.startWrite();out.write(next.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);values=next;return true;}
            catch(IOException e){if(out!=null)file.failWrite(out);return false;}
        }}
        void apply(){if(!commit())throw new IllegalStateException("无法写入本地设置");}
    }
}
final class SystemClock{
    static long elapsedRealtime(){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());}
    static long elapsedRealtimeNanos(){return System.nanoTime();}
}
final class Handler{
    static final ScheduledExecutorService lane=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"capture");t.setDaemon(true);return t;});
    void post(Runnable r){lane.execute(r);}
    void postDelayed(Runnable r,long ms){lane.schedule(r,ms,TimeUnit.MILLISECONDS);}
}
final class Intent{
    final String action;final JSONObject data;
    Intent(String action,JSONObject data){this.action=action;this.data=data;}
    String getAction(){return action;}
    String getStringExtra(String k){return data.has(k)?data.getString(k):null;}
    long getLongExtra(String k,long d){return data.optLong(k,d);}
    String[] getStringArrayExtra(String k){JSONArray values=data.optJSONArray(k);return values==null?null:values.toList().toArray(String[]::new);}
}
