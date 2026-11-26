package com.localcopy.terminal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

final class Bridge{
    static final Map<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    static synchronized void send(JSONObject msg){System.out.println(msg.toString());System.out.flush();}
    static JSONObject call(String op,JSONObject data)throws Exception{
        return call(op,data,op.equals("saveCsv")?600:40);
    }
    static JSONObject call(String op,JSONObject data,long timeoutSeconds)throws Exception{
        String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> reply=new CompletableFuture<>();
        pending.put(id,reply);try{
            send(new JSONObject().put("host",op).put("id",id).put("data",data));
            JSONObject msg=reply.get(timeoutSeconds,TimeUnit.SECONDS);if(msg.has("error"))throw new IllegalStateException(msg.getString("error"));
            return msg.getJSONObject("data");
        }finally{pending.remove(id);}
    }
    static void accept(JSONObject msg){var f=pending.get(msg.optString("reply"));if(f!=null)f.complete(msg);}
}
final class DesktopPage{
    static final Map<String,DesktopPage> pages=new ConcurrentHashMap<>();
    final String id=UUID.randomUUID().toString(),url;final Consumer<JSONObject> errors;
    String current="";
    DesktopPage(String url,Consumer<JSONObject> errors){this.url=url;this.errors=errors;pages.put(id,this);try{command("create");}catch(RuntimeException e){pages.remove(id);throw e;}}
    String getUrl(){return current;}
    JSONObject call(String op,JSONObject data){try{return Bridge.call("web."+op,data.put("page",id).put("url",url));}catch(Exception e){throw new IllegalStateException("官网浏览器请求失败",e);}}
    void command(String op){call(op,new JSONObject());}
    void loadUrl(String s){command("load");current=s;}
    void reload(){command("reload");}
    String evaluateJavascript(String script, long timeoutSeconds) throws Exception {
        return Bridge.call("web.eval", new JSONObject().put("script", script).put("page", id).put("url", url),
                timeoutSeconds).optString("value", "null");
    }
    void stopLoading(){try{command("stop");}catch(Exception ignored){}}
    void destroy(){pages.remove(id);try{command("destroy");}catch(Exception ignored){}}
}
final class Notices {
    static boolean trade(DesktopContext c,String id,String title,String message,boolean sound){
        try{return Bridge.call("notice",new JSONObject().put("id",id).put("title",title).put("message",message).put("sound",sound)).optBoolean("ok");}catch(Exception e){return false;}
    }
    static boolean balance(DesktopContext c,String id,String title,String message){return trade(c,id,title+" · 保证金划转",message,true);}
}
